package com.careerlens.core.automation;

import com.careerlens.core.auth.AppPrincipal;
import com.careerlens.core.common.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AutomationController {
    private final AutomationService automation;
    private final GreetingService greetings;
    @org.springframework.beans.factory.annotation.Value("${careerlens.dispatcher.enabled:true}") private boolean background;
    private final Map<UUID, List<SseEmitter>> emitters = new java.util.concurrent.ConcurrentHashMap<>();

    @GetMapping("/runner/status")
    Map<String, Object> runnerStatus() { return automation.runnerStatus(); }

    @GetMapping("/runner/storage") Map<String,Object> runnerStorage(){return automation.runnerStorage();}
    @PostMapping("/runner/storage/cleanup-legacy") Map<String,Object> cleanupRunnerLegacyStorage(){return automation.cleanupRunnerLegacyStorage();}

    @GetMapping("/runner/boss-diagnostics")
    Map<String, Object> bossDiagnostics() { return automation.bossDiagnostics(); }
    @GetMapping("/runner/boss-profiles") Map<String,Object> bossProfiles(@AuthenticationPrincipal AppPrincipal user){return automation.bossProfiles(user.id());}
    @GetMapping("/runner/boss-cities") Object bossCities(){return automation.bossCities();}
    @PostMapping("/runner/boss-profiles/{name}/activate") Map<String,Object> activateBossProfile(@AuthenticationPrincipal AppPrincipal user,@PathVariable String name){return automation.activateBossProfile(user.id(),name);}

    @GetMapping("/platforms")
    List<AutomationService.PlatformView> platforms(@AuthenticationPrincipal AppPrincipal user) {
        return automation.platforms(user.id());
    }

    @PostMapping("/platforms/{platform}/connect")
    AutomationService.PlatformView connect(@AuthenticationPrincipal AppPrincipal user,
                                           @PathVariable String platform) {
        var result = automation.connect(user.id(), platform);
        publish(user.id(), "platform.updated", Map.of("platform", result));
        return result;
    }

    @DeleteMapping("/platforms/{platform}/connect")
    AutomationService.PlatformView disconnect(@AuthenticationPrincipal AppPrincipal user,
                                              @PathVariable String platform) {
        AutomationService.PlatformView account = automation.platforms(user.id()).stream()
                .filter(item -> item.platform().equalsIgnoreCase(platform))
                .findFirst()
                .orElseGet(() -> automation.connect(user.id(), platform));
        var result = automation.disconnect(user.id(), account.id());
        publish(user.id(), "platform.updated", Map.of("platform", result));
        return result;
    }

    @PostMapping("/discovery-runs")
    @ResponseStatus(HttpStatus.CREATED)
    AutomationService.DiscoveryView discover(@AuthenticationPrincipal AppPrincipal user,
                                             @Valid @RequestBody DiscoveryRequest request) {
        List<UUID> accountIds = request.accountIds();
        if ((accountIds == null || accountIds.isEmpty()) && request.platforms() != null) {
            Set<String> requested = request.platforms().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
            accountIds = automation.platforms(user.id()).stream()
                    .filter(account -> requested.contains(account.platform().toLowerCase(Locale.ROOT)))
                    .filter(account -> "connected".equals(account.status()))
                    .map(AutomationService.PlatformView::id).toList();
            if (accountIds.isEmpty()) {
                throw com.careerlens.core.common.ApiException.badRequest(
                        "NO_CONNECTED_PLATFORM", "选择的平台尚未连接，请先到设置页完成连接");
            }
        }
        Map<String, Object> spec = request.spec() != null ? request.spec()
                : request.searchSpec() != null ? request.searchSpec() : Map.of();
        var result = background ? automation.enqueueDiscovery(user.id(),accountIds,request.resumeVersionId(),spec) : automation.discover(user.id(), accountIds, request.resumeVersionId(), spec);
        publish(user.id(), "discovery.updated", Map.of("discovery", result));
        return result;
    }

    @GetMapping("/discovery-runs/{id}")
    AutomationService.DiscoveryView discovery(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return automation.discovery(user.id(), id);
    }

    @GetMapping("/discovery-runs/current")
    AutomationService.DiscoveryView currentDiscovery(@AuthenticationPrincipal AppPrincipal user){
        return automation.currentManualDiscovery(user.id());
    }

    @GetMapping("/discovery-runs/{id}/jobs")
    List<AutomationService.JobView> discoveryJobs(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                                                   @RequestParam(defaultValue="100") int limit,
                                                   @RequestParam(defaultValue="0") int offset) {
        return automation.jobs(user.id(), id,limit,offset);
    }

    @GetMapping("/discovery-runs/{id}/jobs/page")
    PageResult<AutomationService.JobView> discoveryJobsPage(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id,
                                                             @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){
        int size=Math.max(1,Math.min(100,limit));var page=automation.jobs(user.id(),id,size+1,offset);boolean more=page.size()>size;
        return new PageResult<>(page.stream().limit(size).toList(),automation.jobCount(user.id(),id),more?String.valueOf(offset+size):null,more);
    }

    @GetMapping("/discovery-runs/{id}/filter-stats")
    AutomationService.DiscoveryFilterStats discoveryFilterStats(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id){
        return automation.discoveryFilterStats(user.id(),id);
    }
    @GetMapping("/discovery-runs/{id}/filter-decisions")
    List<Map<String,Object>> discoveryFilterDecisions(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id,@RequestParam(required=false) String reason,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){return automation.discoveryFilterDecisions(user.id(),id,reason,limit,offset);}

    @PostMapping("/application-plans")
    @ResponseStatus(HttpStatus.CREATED)
    List<AutomationService.PlanView> createPlans(@AuthenticationPrincipal AppPrincipal user,
                                                @Valid @RequestBody CreatePlansRequest request) {
        return automation.createPlans(user.id(), request.discoveryRunId(), request.jobIds(), request.resumeVersionId());
    }

    @GetMapping("/application-plans")
    List<AutomationService.PlanView> plans(@AuthenticationPrincipal AppPrincipal user,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(defaultValue="100") int limit,
                                           @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) Instant before) {
        return automation.plans(user.id(), status,limit,before);
    }

    @GetMapping("/application-plans/page")
    PageResult<AutomationService.PlanView> planPage(@AuthenticationPrincipal AppPrincipal user,@RequestParam(required=false) String status,
                                                     @RequestParam(defaultValue="20") int limit,
                                                     @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) Instant before){
        int size=Math.max(1,Math.min(100,limit));var page=automation.plans(user.id(),status,size+1,before);boolean more=page.size()>size;
        var items=page.stream().limit(size).toList();String cursor=more&&!items.isEmpty()&&items.get(items.size()-1).createdAt()!=null?items.get(items.size()-1).createdAt().toString():null;
        return new PageResult<>(items,automation.planCount(user.id(),status),cursor,more);
    }

    @PatchMapping("/application-plans/{id}")
    AutomationService.PlanView updatePlan(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                                          @RequestBody UpdatePlanRequest request) {
        return automation.updatePlan(user.id(), id, request.included(), request.greeting(), request.resumeVersionId(), request.platformResumeId(), request.platformResumeHash());
    }

    @DeleteMapping("/application-plans/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePlan(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        automation.deletePlan(user.id(), id);
    }

    @DeleteMapping("/application-plans")
    Map<String,Integer> clearPlans(@AuthenticationPrincipal AppPrincipal user) {
        return Map.of("deleted", automation.clearPlans(user.id()));
    }

    @PostMapping("/application-plans/{id}:regenerate-greeting")
    AutomationService.PlanView regenerateGreeting(@AuthenticationPrincipal AppPrincipal user,
                                                  @PathVariable UUID id) {
        return automation.regenerateGreeting(user.id(), id);
    }
    @PostMapping("/application-plans/{id}:generate-greetings") GreetingService.Generated generateGreetings(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id){return automation.generateGreetingCandidates(user.id(),id);}
    @GetMapping("/greeting-policy") GreetingService.PolicyView greetingPolicy(@AuthenticationPrincipal AppPrincipal user){return greetings.policy(user.id());}
    @PutMapping("/greeting-policy") GreetingService.PolicyView updateGreetingPolicy(@AuthenticationPrincipal AppPrincipal user,@RequestBody GreetingService.PolicyUpdate input){return greetings.update(user.id(),input);}

    @PostMapping("/application-plans/approve")
    AutomationService.ApprovalResult approve(@AuthenticationPrincipal AppPrincipal user,
                                             @Valid @RequestBody ApprovalRequest request) {
        var result = automation.approve(user.id(), request.planIds(), request.acknowledged(), request.batchMode());
        publish(user.id(), "automation.approved", Map.of("approval", result));
        return result;
    }

    @GetMapping("/automation-tasks")
    List<AutomationService.TaskView> tasks(@AuthenticationPrincipal AppPrincipal user,
                                           @RequestParam(defaultValue="100") int limit,
                                           @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) Instant before) {
        return automation.tasks(user.id(),limit,before);
    }

    @GetMapping("/automation-tasks/page")
    PageResult<AutomationService.TaskView> taskPage(@AuthenticationPrincipal AppPrincipal user,@RequestParam(defaultValue="20") int limit,
                                                     @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) Instant before){
        int size=Math.max(1,Math.min(100,limit));var page=automation.tasks(user.id(),size+1,before);boolean more=page.size()>size;
        var items=page.stream().limit(size).toList();String cursor=more&&!items.isEmpty()&&items.get(items.size()-1).createdAt()!=null?items.get(items.size()-1).createdAt().toString():null;
        return new PageResult<>(items,automation.taskCount(user.id()),cursor,more);
    }

    @GetMapping("/automation-tasks/{id}")
    AutomationService.TaskDetail task(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return automation.task(user.id(), id);
    }

    @PostMapping("/automation-tasks/{id}/start")
    AutomationService.TaskView start(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id,
                                     @RequestBody(required = false) StartTaskRequest request) {
        String scenario = request == null ? "happy_path" : request.scenario();
        var result = automation.enqueue(user.id(), id);
        publish(user.id(), "task.updated", Map.of("task", result));
        return result;
    }

    @PostMapping("/automation-tasks/{id}/sync")
    AutomationService.TaskView sync(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        var result = automation.task(user.id(), id).task();
        publish(user.id(), "task.updated", Map.of("task", result));
        return result;
    }

    @PostMapping("/automation-tasks/{id}/reconcile")
    AutomationService.TaskView reconcile(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        var result = automation.queueReconcile(user.id(), id);
        publish(user.id(), "task.updated", Map.of("task", result));
        return result;
    }

    @PostMapping("/automation-tasks/batch-reconcile")
    AutomationService.BatchActionResult reconcileBatch(@AuthenticationPrincipal AppPrincipal user,@Valid @RequestBody BatchTaskRequest request){
        return automation.reconcileBatch(user.id(),request.taskIds());
    }

    @PostMapping("/automation-tasks/batch-retry")
    AutomationService.BatchActionResult retryBatch(@AuthenticationPrincipal AppPrincipal user,@Valid @RequestBody BatchTaskRequest request){
        return automation.retryBatch(user.id(),request.taskIds());
    }

    @GetMapping("/automation-tasks/failure-summary")
    List<AutomationService.FailureSummary> failureSummary(@AuthenticationPrincipal AppPrincipal user){return automation.failureSummary(user.id());}

    @PostMapping("/automation-tasks/{id}/human-action/resolved")
    AutomationService.TaskView resolve(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        var result = automation.queueHumanAction(user.id(), id);
        publish(user.id(), "human-action.resolved", Map.of("task", result));
        return result;
    }

    @PostMapping("/automation-tasks/{id}/retry")
    AutomationService.TaskView retry(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return automation.retry(user.id(), id);
    }

    @PostMapping("/automation-tasks/{id}/cancel")
    AutomationService.TaskView cancel(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return automation.cancel(user.id(), id);
    }

    @DeleteMapping("/automation-tasks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteTask(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        automation.deleteTask(user.id(), id);
    }

    @DeleteMapping("/automation-tasks")
    Map<String,Integer> clearTasks(@AuthenticationPrincipal AppPrincipal user) {
        return Map.of("deleted", automation.clearTasks(user.id()));
    }

    @GetMapping("/automation-tasks/{id}/audit")
    List<AutomationService.AuditView> audit(@AuthenticationPrincipal AppPrincipal user, @PathVariable UUID id) {
        return automation.audits(user.id(), id);
    }
    @GetMapping("/automation-tasks/{id}/audit/page")
    PageResult<AutomationService.AuditView> auditPage(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){
        var all=automation.audits(user.id(),id);int size=Math.max(1,Math.min(100,limit)),start=Math.max(0,offset);var items=all.stream().skip(start).limit(size).toList();boolean more=start+items.size()<all.size();return new PageResult<>(items,all.size(),more?String.valueOf(start+items.size()):null,more);
    }

    @GetMapping(path = "/automation-events", produces = "text/event-stream")
    SseEmitter events(@AuthenticationPrincipal AppPrincipal user) {
        List<SseEmitter> userEmitters = emitters.computeIfAbsent(user.id(), ignored -> new CopyOnWriteArrayList<>());
        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L);
        userEmitters.add(emitter);
        emitter.onCompletion(() -> userEmitters.remove(emitter));
        emitter.onTimeout(() -> userEmitters.remove(emitter));
        try { emitter.send(SseEmitter.event().name("connected").data(Map.of("at", Instant.now()))); }
        catch (IOException ignored) { userEmitters.remove(emitter); }
        return emitter;
    }

    private void publish(UUID userId, String name, Object data) {
        List<SseEmitter> subscribers = emitters.get(userId);
        if (subscribers == null) return;
        subscribers.removeIf(emitter -> {
            try { emitter.send(SseEmitter.event().name(name).data(data)); return false; }
            catch (IOException error) { emitter.complete(); return true; }
        });
    }

    record DiscoveryRequest(List<UUID> accountIds, List<String> platforms, @NotNull UUID resumeVersionId,
                            Map<String, Object> spec, Map<String, Object> searchSpec) {}
    record CreatePlansRequest(@NotNull UUID discoveryRunId, @NotEmpty List<UUID> jobIds,
                              @NotNull UUID resumeVersionId) {}
    record UpdatePlanRequest(Boolean included, String greeting, UUID resumeVersionId,String platformResumeId,String platformResumeHash) {}
    record ApprovalRequest(@NotEmpty List<UUID> planIds, boolean acknowledged, boolean batchMode) {}
    record BatchTaskRequest(@NotEmpty List<UUID> taskIds) {}
    record StartTaskRequest(String scenario) {}
    record TransitionRequest(@NotNull String status, Integer progress, String currentStep,
                             String humanAction, String receipt) {}
}
