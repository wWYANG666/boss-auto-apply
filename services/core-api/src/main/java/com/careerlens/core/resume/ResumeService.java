package com.careerlens.core.resume;

import com.careerlens.core.common.ApiException;
import com.careerlens.core.common.Jsons;
import com.careerlens.core.domain.DataStore;
import com.careerlens.core.domain.Entities.Resume;
import com.careerlens.core.domain.Entities.ResumeDraft;
import com.careerlens.core.domain.Entities.ResumeVersion;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ResumeService {
    private static final Set<String> SOURCES = Set.of("AUTO_SAVE", "MANUAL_PUBLISH", "AI_APPLY", "IMPORT", "RESTORE");
    private final DataStore store;
    private final Jsons jsons;

    @Transactional(readOnly = true)
    public List<ResumeView> list(UUID userId) {
        return store.query("select r from Resume r where r.userId=:uid order by r.updatedAt desc",
                Resume.class, Map.of("uid", userId)).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ResumeDetail get(UUID userId, UUID id) {
        Resume resume = owned(userId, id);
        ResumeDraft draft = draftEntity(userId, id).orElse(null);
        return new ResumeDetail(view(resume), draft == null ? null : draftView(draft));
    }

    @Transactional
    public ResumeDetail create(UUID userId, String title, String headline, JsonNode content, String source) {
        if(content!=null&&!content.isObject())throw ApiException.badRequest("INVALID_RESUME_SCHEMA","简历内容必须为JSON对象");
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setTitle(title == null || title.isBlank() ? "未命名简历" : title.trim());
        resume.setHeadline(headline);
        store.persist(resume);
        store.flush();

        ResumeDraft draft = new ResumeDraft();
        draft.setUserId(userId);
        draft.setResumeId(resume.getId());
        draft.setRevision(1);
        draft.setContentText(jsons.write(content == null ? Map.of() : content));
        store.persist(draft);

        if (source != null) {
            publishInternal(resume, draft, source == null ? "MANUAL_PUBLISH" : source, null);
        }
        return new ResumeDetail(view(resume), draftView(draft));
    }

    @Transactional
    public DraftView saveDraft(UUID userId, UUID resumeId, long baseRevision, JsonNode content) {
        if(content==null||!content.isObject())throw ApiException.badRequest("INVALID_RESUME_SCHEMA","草稿内容必须为JSON对象");
        owned(userId, resumeId);
        ResumeDraft draft = draftEntity(userId, resumeId).orElseThrow(() -> ApiException.notFound("简历草稿"));
        if (draft.getRevision() != baseRevision) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "DRAFT_REVISION_CONFLICT",
                    "草稿已在其他位置更新，请重新加载后合并");
        }
        draft.setContentText(jsons.write(content));
        draft.setRevision(draft.getRevision() + 1);
        return draftView(draft);
    }

    @Transactional(readOnly = true)
    public DraftView getDraft(UUID userId, UUID resumeId) {
        owned(userId, resumeId);
        return draftEntity(userId, resumeId).map(this::draftView)
                .orElseThrow(() -> ApiException.notFound("简历草稿"));
    }

    @Transactional
    public VersionView publishExpected(UUID userId,UUID resumeId,String source,JsonNode content,long expectedRevision) {
        ResumeDraft draft=draftEntity(userId,resumeId).orElseThrow(()->ApiException.notFound("简历草稿"));
        if(draft.getRevision()!=expectedRevision)throw new ApiException(HttpStatus.PRECONDITION_FAILED,"DRAFT_REVISION_CONFLICT","草稿修订已变化，请重新加载再发布");
        return publish(userId,resumeId,source,content);
    }

    @Transactional
    public VersionView publish(UUID userId, UUID resumeId, String source, JsonNode content) {
        if(content!=null&&!content.isObject())throw ApiException.badRequest("INVALID_RESUME_SCHEMA","发布内容必须为JSON对象");
        Resume resume = owned(userId, resumeId);
        ResumeDraft draft = draftEntity(userId, resumeId).orElseThrow(() -> ApiException.notFound("简历草稿"));
        JsonNode checked=content==null ? jsons.tree(draft.getContentText()) : content;
        if(checked.path("importReviewPending").asBoolean(false)) throw ApiException.conflict("IMPORT_REVIEW_REQUIRED","请先核对导入字段再发布");
        if (content != null) {
            draft.setContentText(jsons.write(content));
            draft.setRevision(draft.getRevision() + 1);
        }
        return versionView(publishInternal(resume, draft, source == null ? "MANUAL_PUBLISH" : source, null), resume);
    }

    @Transactional(readOnly = true)
    public List<VersionView> versions(UUID userId, UUID resumeId) {
        Resume resume = owned(userId, resumeId);
        return store.query("select v from ResumeVersion v where v.userId=:uid and v.resumeId=:rid order by v.versionNumber desc",
                ResumeVersion.class, Map.of("uid", userId, "rid", resumeId)).stream()
                .map(version -> versionView(version, resume)).toList();
    }

    @Transactional(readOnly = true)
    public ResumeVersion version(UUID userId, UUID versionId) {
        return store.one("select v from ResumeVersion v where v.id=:id and v.userId=:uid",
                        ResumeVersion.class, Map.of("id", versionId, "uid", userId))
                .orElseThrow(() -> ApiException.notFound("简历版本"));
    }

    @Transactional
    public VersionView restore(UUID userId, UUID resumeId, UUID versionId) {
        Resume resume = owned(userId, resumeId);
        ResumeVersion source = version(userId, versionId);
        if (!source.getResumeId().equals(resumeId)) throw ApiException.badRequest("VERSION_MISMATCH", "版本不属于该简历");
        ResumeDraft draft = draftEntity(userId, resumeId).orElseThrow(() -> ApiException.notFound("简历草稿"));
        draft.setContentText(source.getContentText());
        draft.setRevision(draft.getRevision() + 1);
        return versionView(publishInternal(resume, draft, "RESTORE", source.getId()), resume);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> diff(UUID userId, UUID resumeId, UUID leftId, UUID rightId) {
        owned(userId, resumeId);
        ResumeVersion left = version(userId, leftId);
        ResumeVersion right = version(userId, rightId);
        if (!left.getResumeId().equals(resumeId) || !right.getResumeId().equals(resumeId))
            throw ApiException.badRequest("VERSION_MISMATCH", "版本不属于该简历");
        JsonNode a = jsons.tree(left.getContentText());
        JsonNode b = jsons.tree(right.getContentText());
        Set<String> names = new TreeSet<>();
        a.fieldNames().forEachRemaining(names::add);
        b.fieldNames().forEachRemaining(names::add);
        List<Map<String, Object>> changes = names.stream().filter(name -> !Objects.equals(a.get(name), b.get(name)))
                .map(name -> Map.<String, Object>of("path", "/" + name,
                        "before", a.has(name) ? a.get(name) : com.fasterxml.jackson.databind.node.NullNode.instance,
                        "after", b.has(name) ? b.get(name) : com.fasterxml.jackson.databind.node.NullNode.instance))
                .toList();
        return Map.of("leftVersionId", leftId, "rightVersionId", rightId, "changes", changes);
    }

    @Transactional
    public ResumeDetail duplicate(UUID userId, UUID id) {
        Resume source = owned(userId, id);
        JsonNode content = draftEntity(userId, id).map(d -> jsons.tree(d.getContentText())).orElse(jsons.tree("{}"));
        return create(userId, source.getTitle() + " - 副本", source.getHeadline(), content, "MANUAL_PUBLISH");
    }
    @Transactional
    public ResumeView rename(UUID userId,UUID id,String title){
        Resume resume=owned(userId,id);resume.setTitle(title.trim());return view(resume);
    }

    @Transactional
    public void delete(UUID userId, UUID id) { store.remove(owned(userId, id)); }

    public Resume owned(UUID userId, UUID id) {
        return store.one("select r from Resume r where r.id=:id and r.userId=:uid", Resume.class,
                        Map.of("id", id, "uid", userId))
                .orElseThrow(() -> ApiException.notFound("简历"));
    }

    private Optional<ResumeDraft> draftEntity(UUID userId, UUID resumeId) {
        return store.one("select d from ResumeDraft d where d.resumeId=:rid and d.userId=:uid",
                ResumeDraft.class, Map.of("rid", resumeId, "uid", userId));
    }

    private ResumeVersion publishInternal(Resume resume, ResumeDraft draft, String source, UUID sourceVersionId) {
        if (!SOURCES.contains(source)) throw ApiException.badRequest("INVALID_VERSION_SOURCE", "不支持的版本来源");
        ResumeVersion version = new ResumeVersion();
        version.setUserId(resume.getUserId());
        version.setResumeId(resume.getId());
        version.setVersionNumber(resume.getCurrentVersionNumber() + 1);
        version.setContentText(draft.getContentText());
        version.setSource(source);
        version.setSourceVersionId(sourceVersionId);
        store.persist(version);
        store.flush();
        resume.setCurrentVersionNumber(version.getVersionNumber());
        resume.setCurrentVersionId(version.getId());
        JsonNode content = jsons.tree(draft.getContentText());
        resume.setHeadline(content.path("profile").path("headline").asText(""));
        return version;
    }

    private ResumeView view(Resume resume) {
        int completion = draftEntity(resume.getUserId(), resume.getId())
                .map(d -> completion(jsons.tree(d.getContentText()))).orElse(0);
        return new ResumeView(resume.getId(), resume.getTitle(), "MASTER", resume.getHeadline(),
                resume.getCurrentVersionId(), resume.getCurrentVersionNumber(), completion,
                "clear-single-column", resume.getCreatedAt(), resume.getUpdatedAt());
    }

    private DraftView draftView(ResumeDraft draft) {
        JsonNode content = jsons.tree(draft.getContentText());
        int completion = completion(content);
        return new DraftView(draft.getResumeId(), draft.getRevision(), content, draft.getUpdatedAt(), completion,
                0, 0, false);
    }

    private int completion(JsonNode content) {
        if (content == null || content.isNull()) return 0;
        JsonNode profile = content.path("profile");
        int populated = 0;
        for (String field : List.of("name","headline","email","phone","location","summary")) {
            if (!profile.path(field).asText("").isBlank()) populated++;
        }
        Set<String> completedSections=new HashSet<>();
        for (JsonNode section : content.path("sections")) {
            if(section.path("hidden").asBoolean(false)||section.path("visible").isBoolean()&&!section.path("visible").asBoolean())continue;
            String type = section.path("type").asText();
            if ("PROJECT".equals(type) && hasContent(section,"title") && completedSections.add(type)) populated++;
            if ("EDUCATION".equals(type) && hasContent(section,"school") && completedSections.add(type)) populated++;
            if ("SKILLS".equals(type) && section.path("items").isArray() && completedSections.add(type)) {
                for(JsonNode item:section.path("items"))if(item.isTextual()&&!item.asText().isBlank()){populated++;break;}
            }
        }
        return Math.min(100, (int) Math.round(populated * 100.0 / 9));
    }

    private boolean hasContent(JsonNode section,String field){
        if(!section.path(field).asText("").isBlank())return true;
        for(JsonNode item:section.path("items"))
            if(!item.path("hidden").asBoolean(false)&&!(item.path("visible").isBoolean()&&!item.path("visible").asBoolean())
               && !item.path(field).asText("").isBlank())return true;
        return false;
    }
    private VersionView versionView(ResumeVersion version, Resume resume) {
        return new VersionView(version.getId(), version.getResumeId(), version.getVersionNumber(),
                version.getVersionNumber() == resume.getCurrentVersionNumber() ? "当前版本" : "历史版本 v" + version.getVersionNumber(),
                sourceLabel(version.getSource()), version.getSource(), version.getSourceVersionId(),
                version.getId().equals(resume.getCurrentVersionId()), version.getCreatedAt(), jsons.tree(version.getContentText()));
    }

    private String sourceLabel(String source) {
        return switch (source) {
            case "AI_APPLY" -> "应用优化建议生成";
            case "IMPORT" -> "导入文档生成";
            case "RESTORE" -> "恢复历史版本生成";
            case "AUTO_SAVE" -> "自动保存";
            default -> "手动发布";
        };
    }

    public record ResumeView(UUID id, String title, String kind, String headline, UUID currentVersionId,
                             int currentVersion, int completionRate, String templateId,
                             Instant createdAt, Instant updatedAt) {}
    public record ResumeDetail(ResumeView resume, DraftView draft) {}
    public record DraftView(UUID resumeId, long revision, JsonNode content, Instant savedAt, int completionRate,
                            int pageUsageRate, int pageCount, boolean textLayerReadable) {}
    public record VersionView(UUID id, UUID resumeId, int version, String title, String description, String source,
                              UUID sourceVersionId, boolean current, Instant createdAt, JsonNode content) {}
}
