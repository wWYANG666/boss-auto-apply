package com.careerlens.core.application;

import com.careerlens.core.auth.AppPrincipal;
import com.careerlens.core.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applications")
@RequiredArgsConstructor
public class ApplicationController {
    private final ApplicationService applications;

    @GetMapping
    List<ApplicationService.ApplicationView> list(@AuthenticationPrincipal AppPrincipal user,
                                                  @RequestParam(required = false) String stage,
                                                  @RequestParam(required = false) String query,
                                                  @RequestParam(defaultValue="false") boolean allAccounts) {
        return applications.list(user.id(), stage, query,allAccounts);
    }

    @GetMapping("/page")
    PageResult<ApplicationService.ApplicationView> page(@AuthenticationPrincipal AppPrincipal user,
                                                         @RequestParam(required=false) String stage,@RequestParam(required=false) String query,
                                                         @RequestParam(defaultValue="false") boolean allAccounts,
                                                         @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){
        var all=applications.list(user.id(),stage,query,allAccounts);int size=Math.max(1,Math.min(100,limit));int start=Math.max(0,offset);
        var items=all.stream().skip(start).limit(size).toList();boolean more=start+items.size()<all.size();
        return new PageResult<>(items,all.size(),more?String.valueOf(start+items.size()):null,more);
    }

    @GetMapping("/stats")
    ApplicationService.Stats stats(@AuthenticationPrincipal AppPrincipal user) { return applications.stats(user.id()); }

    @GetMapping("/daily-stats")
    ApplicationService.DailyStats dailyStats(@AuthenticationPrincipal AppPrincipal user,
                                               @RequestParam(defaultValue="7") int days) {
        return applications.dailyStats(user.id(),days);
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    ApplicationService.ApplicationView create(@AuthenticationPrincipal AppPrincipal user,
                                              @Valid @RequestBody CreateRequest request) {
        return applications.create(user.id(), new ApplicationService.CreateCommand(request.company(), request.role(),
                request.location(), request.stage(), request.matchScore(), request.nextAction(), request.logoText(),
                request.logoTone(), request.tags(), request.platform(), request.externalJobId(), request.resumeVersion(),
                "MANUAL", null, null));
    }

    @GetMapping("/{id}")
    ApplicationService.ApplicationDetail get(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return applications.get(user.id(), id);
    }

    @PatchMapping("/{id}")
    ApplicationService.ApplicationView update(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                                              @RequestBody UpdateRequest request) {
        return applications.update(user.id(), id, new ApplicationService.UpdateCommand(request.stage(),
                request.nextAction(), request.nextActionSet(), request.tags(), null, null));
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        applications.delete(user.id(), id);
    }

    @PostMapping("/{id}/events") @ResponseStatus(HttpStatus.CREATED)
    ApplicationService.EventView event(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                                       @RequestBody EventRequest request) {
        return applications.addEvent(user.id(), id, request.type(), request.detail(), request.toStage());
    }

    record CreateRequest(@NotBlank String company, @NotBlank String role, String location, String stage,
                         Integer matchScore, String nextAction, String logoText, String logoTone, List<String> tags,
                         String platform, String externalJobId, Integer resumeVersion, String actionType,
                         String automationStatus, String receipt) {}
    record UpdateRequest(String stage, String nextAction, boolean nextActionSet, List<String> tags,
                         String automationStatus, String receipt) {}
    record EventRequest(String type, String detail, String toStage) {}
}
