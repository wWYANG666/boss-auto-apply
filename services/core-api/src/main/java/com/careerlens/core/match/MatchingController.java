package com.careerlens.core.match;

import com.careerlens.core.auth.AppPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="careerlens.legacy-resume-optimization.enabled",havingValue="true")
@RequestMapping("/api/v1/matches")
@RequiredArgsConstructor
public class MatchingController {
    private final MatchingService matching;

    @GetMapping
    List<MatchingService.MatchView> list(@AuthenticationPrincipal AppPrincipal user) { return matching.list(user.id()); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    MatchingService.MatchView run(@AuthenticationPrincipal AppPrincipal user, @Valid @RequestBody MatchRequest request) {
        return matching.run(user.id(), request.resumeVersionId(), request.jobDescriptionVersionId(), request.scoringRuleSetId());
    }

    @GetMapping("/{id}")
    MatchingService.MatchView get(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return matching.view(user.id(), id);
    }

    @PostMapping("/{id}:rerun") @ResponseStatus(HttpStatus.CREATED)
    MatchingService.MatchView rerun(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return matching.rerun(user.id(), id);
    }

    @GetMapping("/{id}/items")
    List<MatchingService.ItemView> items(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return matching.items(user.id(), id);
    }

    @GetMapping("/{id}/items/{itemId}/evidence")
    MatchingService.ItemView item(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                                  @PathVariable UUID itemId) {
        return matching.item(user.id(), id, itemId);
    }

    record MatchRequest(@NotNull UUID resumeVersionId, @NotNull UUID jobDescriptionVersionId,
                        String scoringRuleSetId) {}
}
