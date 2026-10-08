package com.careerlens.core.match;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SkillEvidenceTest {
    @Test void englishBoundariesDoNotConfuseDifferentTechnologies() {
        assertThat(SkillEvidence.positive("JavaScript developer","Java")).isFalse();
        assertThat(SkillEvidence.positive("Used Java and MySQL","Java")).isTrue();
        assertThat(SkillEvidence.positive("C++ developer","C")).isFalse();
    }
    @Test void negatedClaimsAreNotPositiveEvidence() {
        for(String text:new String[]{"未使用过Redis","没有 Redis 项目经验","not familiar with Redis","never used Redis"})
            assertThat(SkillEvidence.positive(text,"Redis")).isFalse();
        assertThat(SkillEvidence.positive("未使用Redis，使用MySQL实现存储","MySQL")).isTrue();
    }
}
