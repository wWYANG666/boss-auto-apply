package com.careerlens.core.suggestion;

import com.careerlens.core.common.ApiException;
import com.careerlens.core.common.Jsons;
import com.careerlens.core.domain.DataStore;
import com.careerlens.core.domain.Entities.*;
import com.careerlens.core.integration.AiWorkerClient;
import com.careerlens.core.match.MatchingService;
import com.careerlens.core.resume.ResumeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="careerlens.legacy-resume-optimization.enabled",havingValue="true")
@RequiredArgsConstructor
public class SuggestionService {
    private static final Set<String> STATUSES = Set.of("pending", "accepted", "rejected", "applied");
    private final DataStore store;
    private final Jsons jsons;
    private final MatchingService matching;
    private final ResumeService resumes;
    private final AiWorkerClient aiWorker;

    @Transactional
    public BatchView generate(UUID userId, UUID matchId) {
        MatchRun run = matching.owned(userId, matchId);
        ResumeVersion version = resumes.version(userId, run.getResumeVersionId());
        SuggestionBatch batch = new SuggestionBatch();
        batch.setUserId(userId);
        batch.setMatchRunId(matchId);
        batch.setResumeVersionId(version.getId());
        batch.setResumeId(version.getResumeId());
        batch.setStatus("OPEN");
        store.persist(batch);
        store.flush();

        List<MatchingService.ItemView> items = matching.items(userId, matchId);
        Map<String,Object> suggestionRequest=Map.of(
                "resumeElements", resumeElements(jsons.tree(version.getContentText())),
                "requirements", items.stream().map(SuggestionService::aiRequirement).toList());
        Map<String,Object> response=aiWorker.suggestionResponse(suggestionRequest);
        @SuppressWarnings("unchecked") List<Map<String,Object>> generated=response.get("suggestions") instanceof List<?> values?(List<Map<String,Object>>)(List<?>)values:List.of();
        batch.setProviderMode(Objects.toString(response.get("providerMode"),"core-rules-fallback"));
        batch.setWarningsText(jsons.write(response.getOrDefault("warnings",List.of())));
        int count = addValidatedAiSuggestions(userId, batch, jsons.tree(version.getContentText()), generated);
        if (count == 0) addDeterministicSuggestions(userId, batch, items);
        return get(userId, batch.getId());
    }

    @Transactional
    public BatchView generateForResume(UUID userId, UUID resumeId) {
        Resume resume = resumes.owned(userId, resumeId);
        ResumeService.DraftView draft = resumes.getDraft(userId, resumeId);
        JsonNode content = draft.content();
        if (resumeElements(content).isEmpty()) {
            throw ApiException.badRequest("EMPTY_RESUME", "请先填写简历内容再生成建议");
        }
        SuggestionBatch batch = new SuggestionBatch();
        batch.setUserId(userId);
        batch.setMatchRunId(null);
        batch.setResumeVersionId(null);
        batch.setResumeId(resumeId);
        batch.setDraftRevision(draft.revision());
        batch.setStatus("OPEN");
        store.persist(batch);
        store.flush();

        Map<String, Object> request = Map.of(
                "resumeElements", resumeElements(content),
                "requirements", List.of(),
                "maxSuggestions", 20
        );
        Map<String, Object> response = aiWorker.suggestionResponse(request);
        @SuppressWarnings("unchecked") List<Map<String, Object>> generated =
                response.get("suggestions") instanceof List<?> values
                        ? (List<Map<String, Object>>) (List<?>) values : List.of();
        batch.setProviderMode(Objects.toString(response.get("providerMode"), "offline-rules"));
        batch.setWarningsText(jsons.write(response.getOrDefault("warnings", List.of())));
        addValidatedAiSuggestions(userId, batch, content, generated.stream().limit(8).toList());
        addResumeQualitySuggestions(userId, batch, content, 5);
        return get(userId, batch.getId());
    }

    private void addResumeQualitySuggestions(UUID userId, SuggestionBatch batch, JsonNode content, int limit) {
        int added = 0;
        JsonNode profile = content.path("profile");
        if (profile.path("headline").asText("").isBlank() && added++ < limit) {
            addMissingSuggestion(userId, batch, "基本信息", "补充明确的求职方向",
                    "当前未填写求职方向", "例如：Java 后端开发工程师。请按真实求职目标填写。",
                    "明确岗位方向有助于招聘方快速判断匹配度。", "profile.headline");
        }
        for (JsonNode section : content.path("sections")) {
            if (added >= limit) return;
            String type = section.path("type").asText();
            for (JsonNode item : section.path("items")) {
                if (!item.isObject()) continue;
                String sectionName = section.path("heading").asText(type);
                String evidence = item.path("title").asText(item.path("description").asText("当前条目"));
                if ("PROJECT".equals(type)) {
                    JsonNode highlights = item.path("highlights");
                    if ((!highlights.isArray() || highlights.isEmpty()) && added++ < limit) {
                        addMissingSuggestion(userId, batch, sectionName, "补充项目成果",
                                "当前项目没有成果条目", "补充你采取的动作、使用的技术和产生的结果。",
                                "只有项目名称和技术栈不足以证明实际贡献。", evidence);
                    } else if (!containsMetric(highlights) && added++ < limit) {
                        addMissingSuggestion(userId, batch, sectionName, "补充可核验的量化结果",
                                "项目成果缺少量化信息", "如有真实数据，请补充性能、规模、效率或质量指标。",
                                "量化结果能提高项目描述的可信度；没有数据时不要编造。", evidence);
                    }
                }
                if (added >= limit) return;
            }
        }
    }

    private void addMissingSuggestion(UUID userId, SuggestionBatch batch, String section, String title,
                                      String before, String after, String reason, String evidence) {
        ResumeSuggestion suggestion = baseSuggestion(userId, batch, "missing");
        suggestion.setSectionName(section);
        suggestion.setTitle(title);
        suggestion.setBeforeText(before);
        suggestion.setAfterText(after);
        suggestion.setReasonText(reason);
        suggestion.setEvidenceText(evidence);
        suggestion.setPatchText("[]");
        store.persist(suggestion);
    }

    private boolean containsMetric(JsonNode highlights) {
        if (!highlights.isArray()) return false;
        for (JsonNode highlight : highlights) {
            if (highlight.asText("").matches(".*(?:\\d+(?:\\.\\d+)?\\s*(?:%|ms|秒|分钟|万|千|人|条|个)|QPS|TPS|P95).*")) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public BatchView get(UUID userId, UUID id) {
        SuggestionBatch batch = ownedBatch(userId, id);
        List<SuggestionView> suggestions = store.query(
                "select s from ResumeSuggestion s where s.userId=:uid and s.batchId=:bid order by s.createdAt",
                ResumeSuggestion.class, Map.of("uid", userId, "bid", id)).stream().map(this::view).toList();
        return new BatchView(batch.getId(), batch.getMatchRunId(), batch.getResumeVersionId(), batch.getResumeId(),
                batch.getDraftRevision(), batch.getStatus(),
                suggestions, batch.getCreatedAt(), batch.getUpdatedAt(), batch.getProviderMode(), jsons.list(batch.getWarningsText()));
    }

    @Transactional(readOnly = true)
    public List<BatchView> list(UUID userId, UUID matchId) {
        List<SuggestionBatch> batches = store.query(
                "select b from SuggestionBatch b where b.userId=:uid order by b.createdAt desc",
                SuggestionBatch.class, Map.of("uid", userId));
        return batches.stream()
                .filter(batch -> matchId == null || matchId.equals(batch.getMatchRunId()))
                .map(batch -> get(userId, batch.getId()))
                .toList();
    }

    @Transactional
    public SuggestionView update(UUID userId, UUID id, String status, String editedAfter) {
        if (!STATUSES.contains(status)) throw ApiException.badRequest("INVALID_SUGGESTION_STATUS", "不支持的建议状态");
        ResumeSuggestion suggestion = ownedSuggestion(userId, id);
        if ("applied".equals(suggestion.getSuggestionStatus())) throw ApiException.conflict("SUGGESTION_ALREADY_APPLIED", "已应用的建议不可修改");
        if ("applied".equals(status)) throw ApiException.badRequest("USE_APPLY_ENDPOINT", "请通过应用批次接口写入简历版本");
        if (editedAfter != null && !editedAfter.isBlank()) suggestion.setAfterText(editedAfter.trim());
        suggestion.setSuggestionStatus(status);
        return view(suggestion);
    }

    @Transactional
    public ApplyResult apply(UUID userId, UUID batchId, UUID expectedResumeVersionId, List<UUID> suggestionIds) {
        SuggestionBatch batch = ownedBatch(userId, batchId);
        if (batch.getMatchRunId() == null) {
            return applyToDraft(userId, batch, suggestionIds);
        }
        if (!batch.getResumeVersionId().equals(expectedResumeVersionId))
            throw ApiException.conflict("RESUME_VERSION_CONFLICT", "建议基于的简历版本已变化，请重新生成建议");
        ResumeVersion base = resumes.version(userId, expectedResumeVersionId);
        Resume resume = resumes.owned(userId, base.getResumeId());
        if (!expectedResumeVersionId.equals(resume.getCurrentVersionId()))
            throw ApiException.conflict("RESUME_VERSION_CONFLICT", "当前简历已有更新，请重新生成或手工合并");

        if (!resumes.getDraft(userId,base.getResumeId()).content().equals(jsons.tree(base.getContentText())))
            throw ApiException.conflict("UNPUBLISHED_DRAFT_CONFLICT","已有未发布修改，请先发布草稿并重新生成建议");
        Set<UUID> selected = suggestionIds == null ? Set.of() : new HashSet<>(suggestionIds);
        List<ResumeSuggestion> candidates = store.query(
                "select s from ResumeSuggestion s where s.userId=:uid and s.batchId=:bid",
                ResumeSuggestion.class, Map.of("uid", userId, "bid", batchId));
        JsonNode content = jsons.tree(base.getContentText()).deepCopy();
        int applied = 0;
        for (ResumeSuggestion suggestion : candidates) {
            if ((!selected.isEmpty() && !selected.contains(suggestion.getId()))
                    || !"accepted".equals(suggestion.getSuggestionStatus())
                    || !"rewrite".equals(suggestion.getSuggestionKind())) continue;
            int replacements = replaceAtTarget(content, suggestion.getTargetElementId(), suggestion.getBeforeText(), suggestion.getAfterText());
            if (replacements > 0) {
                suggestion.setSuggestionStatus("applied");
                applied++;
            }
        }
        if (applied == 0) throw ApiException.badRequest("NO_APPLICABLE_SUGGESTIONS", "没有已接受且可安全应用的改写建议");
        ResumeService.VersionView newVersion = resumes.publish(userId, resume.getId(), "AI_APPLY", content);
        batch.setStatus("APPLIED");
        return new ApplyResult(newVersion.id(), newVersion.version(), applied, null, true);
    }

    private ApplyResult applyToDraft(UUID userId, SuggestionBatch batch, List<UUID> suggestionIds) {
        if (batch.getResumeId() == null || batch.getDraftRevision() == null) {
            throw ApiException.conflict("SUGGESTION_BATCH_INVALID", "建议批次缺少草稿快照，请重新生成");
        }
        ResumeService.DraftView draft = resumes.getDraft(userId, batch.getResumeId());
        if (draft.revision() != batch.getDraftRevision()) {
            throw ApiException.conflict("DRAFT_REVISION_CONFLICT", "草稿已更新，请重新生成建议");
        }
        Set<UUID> selected = suggestionIds == null ? Set.of() : new HashSet<>(suggestionIds);
        List<ResumeSuggestion> candidates = store.query(
                "select s from ResumeSuggestion s where s.userId=:uid and s.batchId=:bid",
                ResumeSuggestion.class, Map.of("uid", userId, "bid", batch.getId()));
        JsonNode content = draft.content().deepCopy();
        int applied = applyRewrites(content, candidates, selected);
        if (applied == 0) {
            throw ApiException.badRequest("NO_APPLICABLE_SUGGESTIONS", "没有已接受且可安全应用的改写建议");
        }
        ResumeService.DraftView saved = resumes.saveDraft(
                userId, batch.getResumeId(), batch.getDraftRevision(), content);
        batch.setStatus("APPLIED");
        Resume resume = resumes.owned(userId, batch.getResumeId());
        return new ApplyResult(resume.getCurrentVersionId(), resume.getCurrentVersionNumber(), applied,
                saved.revision(), false);
    }

    private int applyRewrites(JsonNode content, List<ResumeSuggestion> candidates, Set<UUID> selected) {
        int applied = 0;
        for (ResumeSuggestion suggestion : candidates) {
            if ((!selected.isEmpty() && !selected.contains(suggestion.getId()))
                    || !"accepted".equals(suggestion.getSuggestionStatus())
                    || !"rewrite".equals(suggestion.getSuggestionKind())) continue;
            if (replaceAtTarget(content, suggestion.getTargetElementId(), suggestion.getBeforeText(),
                    suggestion.getAfterText()) > 0) {
                suggestion.setSuggestionStatus("applied");
                applied++;
            }
        }
        return applied;
    }

    public SuggestionBatch ownedBatch(UUID userId, UUID id) {
        return store.one("select b from SuggestionBatch b where b.id=:id and b.userId=:uid", SuggestionBatch.class,
                        Map.of("id", id, "uid", userId)).orElseThrow(() -> ApiException.notFound("建议批次"));
    }

    private ResumeSuggestion ownedSuggestion(UUID userId, UUID id) {
        return store.one("select s from ResumeSuggestion s where s.id=:id and s.userId=:uid", ResumeSuggestion.class,
                        Map.of("id", id, "uid", userId)).orElseThrow(() -> ApiException.notFound("简历建议"));
    }

    private int addValidatedAiSuggestions(UUID userId, SuggestionBatch batch, JsonNode content,
                                          List<Map<String, Object>> generated) {
        String fullResume = jsons.write(content);
        int count = 0;
        for (Map<String, Object> item : generated) {
            String before = string(item, "before", "").trim();
            String after = string(item, "after", "").trim();
            String kind = string(item, "kind", before.isBlank() ? "missing" : "rewrite").toLowerCase(Locale.ROOT);
            if ("missing_information".equals(kind)) kind = "missing";
            if (!Set.of("rewrite","missing").contains(kind)) continue;
            if ("rewrite".equals(kind) && (before.isBlank() || after.isBlank() || !fullResume.contains(before))) continue;
            ResumeSuggestion suggestion = baseSuggestion(userId, batch, kind);
            String target = string(item, "targetElementId", null);
            suggestion.setTargetElementId(target);
            suggestion.setSectionName(string(item, "section", sectionForPath(content, target)));
            suggestion.setTitle(string(item, "title",
                    batch.getMatchRunId() == null ? "精简表达并统一技术名称" : "优化岗位相关表达"));
            suggestion.setBeforeText(before);
            suggestion.setAfterText(after);
            suggestion.setReasonText(string(item, "reason", "使现有事实与岗位要求更清晰地对应"));
            suggestion.setEvidenceText(string(item, "evidence", String.join("；", item.get("supportingFacts") instanceof List<?> facts ? facts.stream().map(String::valueOf).toList() : List.of("当前简历内容与岗位要求"))));
            suggestion.setPatchText(jsons.write(item.getOrDefault("patch", List.of())));
            store.persist(suggestion);
            count++;
        }
        return count;
    }

    private String sectionForPath(JsonNode content, String target) {
        if (target == null || target.startsWith("$/profile/")) return "基本信息";
        if (target.startsWith("$/sections/")) {
            String[] parts = target.substring(2).split("/");
            if (parts.length >= 2) {
                try {
                    JsonNode section = content.path("sections").path(Integer.parseInt(parts[1]));
                    return section.path("heading").asText(section.path("type").asText("简历内容"));
                } catch (NumberFormatException ignored) {
                    return "简历内容";
                }
            }
        }
        return "简历内容";
    }

    private void addDeterministicSuggestions(UUID userId, SuggestionBatch batch, List<MatchingService.ItemView> items) {
        items.stream().filter(item -> "MISSING".equals(item.verdict())).limit(3).forEach(item -> {
            ResumeSuggestion suggestion = baseSuggestion(userId, batch, "missing");
            suggestion.setSectionName("技能与经历");
            suggestion.setTitle("补充“" + shortText(item.requirement()) + "”的真实实践证据");
            suggestion.setBeforeText("当前简历未找到可信证据");
            suggestion.setAfterText("请确认是否实际使用过该技术，并补充场景、个人动作与可验证结果。");
            suggestion.setReasonText("目标岗位将该项列为" + item.importance() + "要求，现有材料不足以生成可信描述。");
            suggestion.setEvidenceText(item.jdEvidence());
            suggestion.setPatchText("[]");
            store.persist(suggestion);
        });
        items.stream().filter(item -> Set.of("PARTIAL", "SEMANTIC").contains(item.verdict()))
                 .filter(item -> item.resumeEvidence() != null && !item.resumeEvidence().isBlank()).limit(3).forEach(item -> {
            ResumeSuggestion suggestion = baseSuggestion(userId, batch, "rewrite");
            suggestion.setSectionName(item.resumeSource());
            suggestion.setTitle("突出已有“" + shortText(item.requirement()) + "”证据");
            suggestion.setBeforeText(item.resumeEvidence());
            suggestion.setAfterText(item.resumeEvidence());
            suggestion.setReasonText("当前证据相关但表达较弱；可在不改变事实的前提下补充你的动作与结果数据。");
            suggestion.setEvidenceText(item.resumeEvidence());
            suggestion.setPatchText(jsons.write(List.of(Map.of("op", "test", "value", item.resumeEvidence()))));
            store.persist(suggestion);
        });
    }

    private ResumeSuggestion baseSuggestion(UUID userId, SuggestionBatch batch, String kind) {
        ResumeSuggestion suggestion = new ResumeSuggestion();
        suggestion.setUserId(userId);
        suggestion.setBatchId(batch.getId());
        suggestion.setSuggestionKind(kind);
        suggestion.setSuggestionStatus("pending");
        suggestion.setPatchText("[]");
        return suggestion;
    }

    static Map<String, Object> aiRequirement(MatchingService.ItemView item) {
        return Map.of(
                "requirementId", item.id().toString(),
                "text", item.requirement(),
                "category", aiCategory(item.category()),
                "importance", aiImportance(item.importance()),
                "confidence", item.confidence());
    }

    private static String aiCategory(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (Set.of("SKILL", "EDUCATION", "EXPERIENCE", "LANGUAGE", "LOCATION", "RESPONSIBILITY", "OTHER")
                .contains(normalized)) return normalized;
        if (normalized.contains("学历") || normalized.contains("教育")) return "EDUCATION";
        if (normalized.contains("经验")) return "EXPERIENCE";
        if (normalized.contains("语言")) return "LANGUAGE";
        if (normalized.contains("地点") || normalized.contains("城市")) return "LOCATION";
        if (normalized.contains("职责")) return "RESPONSIBILITY";
        if (Set.of("编程语言", "后端框架", "数据库", "中间件", "工程基础").contains(value)) return "SKILL";
        return "OTHER";
    }

    private static String aiImportance(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("MUST") || normalized.contains("必须")) return "MUST";
        if (normalized.equals("BONUS") || normalized.equals("PREFERRED") || normalized.contains("加分")) return "PREFERRED";
        return "NORMAL";
    }

    private List<Map<String, Object>> resumeElements(JsonNode root) {
        return com.careerlens.core.resume.ResumeFacts.collect(root).stream()
                .map(f -> Map.<String,Object>of("elementId", f.path(), "text", f.text())).toList();
    }

    private int replaceAtTarget(JsonNode content, String path, String before, String after) {
        if (path == null || !path.startsWith("$/")) return replaceText(content, before, after);
        String pointer = path.substring(1);
        JsonNode current = content.at(pointer);
        if (!current.isTextual() || !current.asText().equals(before)) return 0;
        int slash = pointer.lastIndexOf('/');
        JsonNode parent = content.at(pointer.substring(0, slash));
        String field = pointer.substring(slash + 1).replace("~1","/").replace("~0","~");
        if (parent instanceof ObjectNode object) { object.put(field, after); return 1; }
        if (parent instanceof ArrayNode array) {
            try { array.set(Integer.parseInt(field), array.textNode(after)); return 1; }
            catch (NumberFormatException ex) { return 0; }
        }
        return 0;
    }

    private int replaceText(JsonNode node, String before, String after) {
        int replacements = 0;
        if (node instanceof ObjectNode object) {
            List<String> fields = new ArrayList<>(); object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                JsonNode child = object.get(field);
                if (child.isTextual() && child.asText().equals(before)) { object.put(field, after); replacements++; }
                else replacements += replaceText(child, before, after);
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child.isTextual() && child.asText().equals(before)) { array.set(i, array.textNode(after)); replacements++; }
                else replacements += replaceText(child, before, after);
            }
        }
        return replacements;
    }

    private SuggestionView view(ResumeSuggestion suggestion) {
        return new SuggestionView(suggestion.getId(), suggestion.getBatchId(), suggestion.getTargetElementId(),
                suggestion.getSectionName(), suggestion.getTitle(), suggestion.getBeforeText(), suggestion.getAfterText(),
                suggestion.getReasonText(), suggestion.getEvidenceText(), suggestion.getSuggestionKind(),
                suggestion.getSuggestionStatus(), jsons.list(suggestion.getPatchText()), suggestion.getCreatedAt());
    }
    private static String string(Map<String, Object> item, String key, String fallback) {
        Object value = item.get(key); return value == null ? fallback : String.valueOf(value);
    }
    private static String shortText(String text) { return text.length() > 30 ? text.substring(0, 30) + "…" : text; }

    public record SuggestionView(UUID id, UUID batchId, String targetElementId, String section, String title,
                                 String before, String after, String reason, String evidence, String kind,
                                 String status, List<Object> patch, java.time.Instant createdAt) {}
    public record BatchView(UUID id, UUID matchId, UUID resumeVersionId, UUID resumeId, Long draftRevision,
                            String status,
                            List<SuggestionView> suggestions, java.time.Instant createdAt, java.time.Instant updatedAt, String providerMode,List<Object> warnings) {}
    public record ApplyResult(UUID resumeVersionId, int resumeVersion, int appliedCount,
                              Long draftRevision, boolean published) {}
}
