package com.careerlens.core.job;

import com.careerlens.core.auth.AppPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="careerlens.legacy-resume-optimization.enabled",havingValue="true")
@RequestMapping("/api/v1/job-descriptions")
@RequiredArgsConstructor
public class JobDescriptionController {
    private final JobDescriptionService jobs;

    @GetMapping
    List<JobDescriptionService.JobView> list(@AuthenticationPrincipal AppPrincipal user) { return jobs.list(user.id()); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    JobDescriptionService.JobView create(@AuthenticationPrincipal AppPrincipal user,
                                         @Valid @RequestBody CreateRequest request) {
        return jobs.create(user.id(), request.company(), request.role(), request.location(), request.salary(), request.rawText());
    }

    @GetMapping("/{id}")
    JobDescriptionService.JobDetail get(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return jobs.get(user.id(), id);
    }

    @PostMapping("/{id}/versions") @ResponseStatus(HttpStatus.CREATED)
    JobDescriptionService.VersionView version(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                                              @Valid @RequestBody VersionRequest request) {
        return jobs.publish(user.id(), id, request.rawText());
    }

    @PostMapping("/{id}/versions/{versionId}:extract")
    JobDescriptionService.ExtractionResult extract(@AuthenticationPrincipal AppPrincipal user,
                                                   @PathVariable UUID id, @PathVariable UUID versionId) {
        return jobs.extract(user.id(), id, versionId);
    }

    @GetMapping("/{id}/versions/{versionId}/requirements")
    List<JobDescriptionService.RequirementView> requirements(@AuthenticationPrincipal AppPrincipal user,
                                                             @PathVariable UUID id, @PathVariable UUID versionId) {
        jobs.owned(user.id(), id);
        return jobs.requirements(user.id(), versionId);
    }

    @PatchMapping("/{id}/versions/{versionId}/requirements/{requirementId}")
    JobDescriptionService.RequirementView updateRequirement(@AuthenticationPrincipal AppPrincipal user,
                                                            @PathVariable UUID id, @PathVariable UUID versionId,
                                                            @PathVariable UUID requirementId,
                                                            @RequestBody UpdateRequirement request) {
        return jobs.updateRequirement(user.id(), id, versionId, requirementId, request.label(), request.category(),
                request.type(), request.hardCondition(), request.confirmed());
    }

    record CreateRequest(@NotBlank String company, @NotBlank String role, String location, String salary,
                         @NotBlank String rawText) {}
    record VersionRequest(@NotBlank String rawText) {}
    record UpdateRequirement(String label, String category, String type, Boolean hardCondition, Boolean confirmed) {}
}
