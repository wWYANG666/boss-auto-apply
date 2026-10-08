package com.careerlens.core.resume;

import com.careerlens.core.auth.AppPrincipal;
import com.careerlens.core.common.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/resumes")
@RequiredArgsConstructor
public class ResumeController {
    private final ResumeService resumes;
    private final com.careerlens.core.integration.AiWorkerClient ai;
    private final ImportMapper importMapper;

    @PostMapping("/import") @ResponseStatus(HttpStatus.CREATED)
    ResumeService.ResumeDetail importFile(@AuthenticationPrincipal AppPrincipal user,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        if (file.isEmpty() || file.getSize() > 8 * 1024 * 1024)
            throw com.careerlens.core.common.ApiException.badRequest("INVALID_FILE_SIZE", "请选择不超过8MB的简历文件");
        String name = java.util.Objects.toString(file.getOriginalFilename(), "resume.txt");
        Map<String,Object> parsed = ai.parseResume(Map.of("fileName",name,"contentBase64", java.util.Base64.getEncoder().encodeToString(file.getBytes())));
        if (!(parsed.get("rawText") instanceof String raw) || raw.isBlank())
            throw com.careerlens.core.common.ApiException.badRequest("PARSE_FAILED", "解析未返回文本，请检查AI服务与文件类型；扫描PDF需先OCR");
        return resumes.create(user.id(),name,"",importMapper.candidate(parsed),null);
    }

    @GetMapping List<ResumeService.ResumeView> list(@AuthenticationPrincipal AppPrincipal user) {
        return resumes.list(user.id());
    }
    @GetMapping("/page") PageResult<ResumeService.ResumeView> page(@AuthenticationPrincipal AppPrincipal user,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){
        var all=resumes.list(user.id());int size=Math.max(1,Math.min(100,limit));int start=Math.max(0,offset);var items=all.stream().skip(start).limit(size).toList();boolean more=start+items.size()<all.size();return new PageResult<>(items,all.size(),more?String.valueOf(start+items.size()):null,more);
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    ResumeService.ResumeDetail create(@AuthenticationPrincipal AppPrincipal user,
                                      @Valid @RequestBody CreateRequest request) {
        return resumes.create(user.id(), request.title(), request.headline(), request.content(), request.source());
    }

    @GetMapping("/{id}")
    ResumeService.ResumeDetail get(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return resumes.get(user.id(), id);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) { resumes.delete(user.id(), id); }
    @PatchMapping("/{id}")
    ResumeService.ResumeView rename(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id,@Valid @RequestBody RenameRequest request){
        return resumes.rename(user.id(),id,request.title());
    }

    @PostMapping("/{id}:duplicate") @ResponseStatus(HttpStatus.CREATED)
    ResumeService.ResumeDetail duplicate(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return resumes.duplicate(user.id(), id);
    }

    @GetMapping("/{id}/draft")
    ResumeService.DraftView draft(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return resumes.getDraft(user.id(), id);
    }

    @PostMapping("/{id}:reparse")
    ResumeService.DraftView reparse(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                                    @Valid @RequestBody ReparseRequest request) {
        ResumeService.DraftView draft = resumes.getDraft(user.id(), id);
        JsonNode source = draft.content().path("sourceDocument");
        String rawText = source.path("rawText").asText("");
        if (rawText.isBlank()) {
            throw com.careerlens.core.common.ApiException.badRequest(
                    "SOURCE_TEXT_UNAVAILABLE", "该简历没有可重新识别的原始文本，请重新上传原文件");
        }
        String fileName = source.path("fileName").asText("resume.txt");
        Map<String, Object> parsed = ai.parseResume(Map.of("fileName", fileName, "rawText", rawText));
        ObjectNode candidate = importMapper.candidate(parsed);
        JsonNode refreshedSource = candidate.path("sourceDocument");
        ObjectNode preservedSource = source.deepCopy();
        for (String field : List.of("sections", "skills", "warnings", "importAnalysis")) {
            if (refreshedSource.has(field)) preservedSource.set(field, refreshedSource.get(field));
        }
        JsonNode previousPhoto = draft.content().path("profile").path("photoDataUrl");
        if (!candidate.path("profile").hasNonNull("photoDataUrl") && previousPhoto.isTextual()) {
            ((ObjectNode) candidate.path("profile")).set("photoDataUrl", previousPhoto);
        }
        candidate.set("sourceDocument", preservedSource);
        return resumes.saveDraft(user.id(), id, request.baseRevision(), candidate);
    }

    @PutMapping("/{id}/draft")
    ResumeService.DraftView saveDraft(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                                      @Valid @RequestBody DraftRequest request) {
        return resumes.saveDraft(user.id(), id, request.baseRevision(), request.content());
    }

    @PostMapping("/{id}/versions") @ResponseStatus(HttpStatus.CREATED)
    ResumeService.VersionView publish(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                                      @Valid @RequestBody PublishRequest request) {
        return resumes.publishExpected(user.id(), id, request.source(), request.content(), request.baseRevision());
    }

    @GetMapping("/{id}/versions")
    List<ResumeService.VersionView> versions(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return resumes.versions(user.id(), id);
    }

    @PostMapping("/{id}/versions/{versionId}:restore")
    ResumeService.VersionView restore(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                                      @PathVariable UUID versionId) {
        return resumes.restore(user.id(), id, versionId);
    }

    @GetMapping("/{id}/versions/{leftId}/diff/{rightId}")
    Map<String, Object> diff(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                             @PathVariable UUID leftId, @PathVariable UUID rightId) {
        return resumes.diff(user.id(), id, leftId, rightId);
    }

    record CreateRequest(@NotBlank String title, String headline, JsonNode content, String source) {}
    record RenameRequest(@NotBlank @jakarta.validation.constraints.Size(max=160) String title){}
    record DraftRequest(long baseRevision, JsonNode content) {}
    record ReparseRequest(@jakarta.validation.constraints.Min(1) long baseRevision) {}
    record PublishRequest(String source, JsonNode content, @jakarta.validation.constraints.NotNull Long baseRevision) {}
}
