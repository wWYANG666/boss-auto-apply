package com.careerlens.core.suggestion;

import com.careerlens.core.match.MatchingService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionServiceAiContractTest {

    @Test
    void matchItemMapsToStrictWorkerRequirementContract() {
        UUID requirementId = UUID.randomUUID();
        MatchingService.ItemView item = new MatchingService.ItemView(
                requirementId,
                "必须熟悉 Spring Boot",
                "后端框架",
                "必须",
                "EXACT",
                9,
                9,
                "必须熟悉 Spring Boot",
                "使用 Spring Boot 开发预约系统",
                "项目经历",
                "精确词匹配",
                0.99);

        Map<String, Object> request = SuggestionService.aiRequirement(item);

        assertThat(request).containsExactlyInAnyOrderEntriesOf(Map.of(
                "requirementId", requirementId.toString(),
                "text", "必须熟悉 Spring Boot",
                "category", "SKILL",
                "importance", "MUST",
                "confidence", 0.99));
        assertThat(request).doesNotContainKeys("id", "verdict", "resumeEvidence");
    }

    @Test
    void workerEnumsPassThroughAndBonusMapsToPreferred() {
        MatchingService.ItemView item = new MatchingService.ItemView(
                UUID.randomUUID(), "Kubernetes 经验优先", "SKILL", "加分", "MISSING",
                0, 3, "Kubernetes 经验优先", "", "", "词典", 0.8);

        assertThat(SuggestionService.aiRequirement(item))
                .containsEntry("category", "SKILL")
                .containsEntry("importance", "PREFERRED");
    }
}
