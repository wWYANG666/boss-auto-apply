package com.careerlens.core.suggestion;

import com.careerlens.core.auth.AppPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="careerlens.legacy-resume-optimization.enabled",havingValue="true")
@RequiredArgsConstructor
public class SuggestionController {
    private final SuggestionService suggestions;

    @GetMapping("/api/v1/suggestion-batches")
    List<SuggestionService.BatchView> list(@AuthenticationPrincipal AppPrincipal user,
                                           @RequestParam(required = false) UUID matchId) {
        return suggestions.list(user.id(), matchId);
    }

    @PostMapping("/api/v1/matches/{matchId}/suggestion-batches")
    @ResponseStatus(HttpStatus.CREATED)
    SuggestionService.BatchView generate(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID matchId) {
        return suggestions.generate(user.id(), matchId);
    }

    @PostMapping("/api/v1/resumes/{resumeId}/suggestion-batches")
    @ResponseStatus(HttpStatus.CREATED)
    SuggestionService.BatchView generateForResume(@AuthenticationPrincipal AppPrincipal user,
                                                  @PathVariable UUID resumeId) {
        return suggestions.generateForResume(user.id(), resumeId);
    }

    @GetMapping("/api/v1/suggestion-batches/{id}")
    SuggestionService.BatchView get(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return suggestions.get(user.id(), id);
    }

    @PatchMapping("/api/v1/suggestions/{id}")
    SuggestionService.SuggestionView update(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                                            @RequestBody UpdateRequest request) {
        return suggestions.update(user.id(), id, request.status(), request.editedAfter());
    }

    @PostMapping("/api/v1/suggestion-batches/{id}:apply")
    SuggestionService.ApplyResult apply(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                                        @RequestBody ApplyRequest request) {
        return suggestions.apply(user.id(), id, request.expectedResumeVersionId(), request.suggestionIds());
    }

    record UpdateRequest(String status, String editedAfter) {}
    record ApplyRequest(List<UUID> suggestionIds, UUID expectedResumeVersionId) {}
}
