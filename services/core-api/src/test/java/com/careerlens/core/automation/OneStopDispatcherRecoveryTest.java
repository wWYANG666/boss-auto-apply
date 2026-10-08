package com.careerlens.core.automation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OneStopDispatcherRecoveryTest {
    @Test
    void longDiscoveryDiagnosticsAreBoundedForPersistentRunState() {
        String detail="BOSS_PAGE_NAVIGATION_UNSTABLE: "+"x".repeat(900)+"\nstack trace";
        assertThat(OneStopDispatcher.boundedError(detail)).hasSize(500).doesNotContain("\n").endsWith("...");
    }
    @Test
    void onlyTransientNavigationFailuresUseBoundedAutomaticRecovery() {
        assertThat(OneStopDispatcher.isTransientDiscoveryFailure("BOSS_PAGE_NAVIGATION_UNSTABLE: navigation")).isTrue();
        assertThat(OneStopDispatcher.isTransientDiscoveryFailure("Execution context was destroyed")).isTrue();
        assertThat(OneStopDispatcher.isTransientDiscoveryFailure("browserContext.newPage: Protocol error (Target.createTarget): Failed to open a new tab")).isTrue();
        assertThat(OneStopDispatcher.isTransientDiscoveryFailure("page.evaluate: Execution context was destroyed, most likely because of a navigation")).isTrue();
        assertThat(OneStopDispatcher.isTransientDiscoveryFailure("BOSS_JOB_API_37")).isFalse();
        assertThat(OneStopDispatcher.transientDiscoveryDelaySeconds(1)).isEqualTo(15);
        assertThat(OneStopDispatcher.transientDiscoveryDelaySeconds(2)).isEqualTo(30);
        assertThat(OneStopDispatcher.transientDiscoveryDelaySeconds(5)).isEqualTo(60);
        assertThat(OneStopDispatcher.transientDiscoveryDelaySeconds(100)).isEqualTo(60);
    }

    @Test
    void emptyDiscoveryBackoffDistinguishesDuplicatesFromTrueEmptyResults() {
        assertThat(OneStopDispatcher.adaptiveEmptyDelaySeconds(1,12,0)).isEqualTo(30);
        assertThat(OneStopDispatcher.adaptiveEmptyDelaySeconds(2,12,0)).isEqualTo(60);
        assertThat(OneStopDispatcher.adaptiveEmptyDelaySeconds(1,12,4)).isEqualTo(60);
        assertThat(OneStopDispatcher.adaptiveEmptyDelaySeconds(1,0,0)).isEqualTo(300);
    }

    @Test
    void emptyDiscoveryExplainsWhyNoNewJobsWereSelected() {
        var now=java.time.Instant.now();
        var duplicate=new AutomationService.DiscoveryView(java.util.UUID.randomUUID(),"completed",100,15,0,15,null,null,now,now);
        var stats=new AutomationService.DiscoveryFilterStats(15,15,0,15,0,15,15,15,15,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,"",false,false,java.util.List.of());
        assertThat(OneStopDispatcher.emptyDiscoveryReason(duplicate,stats)).contains("15").contains("历史重复");
        var empty=new AutomationService.DiscoveryView(java.util.UUID.randomUUID(),"completed",100,0,0,0,null,null,now,now);
        assertThat(OneStopDispatcher.emptyDiscoveryReason(empty,stats)).contains("平台没有返回岗位");
    }

    @Test
    void emptyDiscoveryDistinguishesPlatformCandidatesRejectedByRunnerFilters() {
        var now=java.time.Instant.now();
        var empty=new AutomationService.DiscoveryView(java.util.UUID.randomUUID(),"completed",100,0,0,0,null,null,now,now);
        var stats=new AutomationService.DiscoveryFilterStats(15,15,15,0,0,0,0,0,0,0,0,0,0,0,5,0,0,0,0,0,0,0,10,5,57,"scopes_exhausted",false,false,java.util.List.of());
        assertThat(OneStopDispatcher.emptyDiscoveryReason(empty,stats)).contains("平台返回15个候选").contains("未通过当前筛选");
    }
}
