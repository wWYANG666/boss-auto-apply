package com.careerlens.core.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ImportMapperTest {
    @Test void preservesStructuredFieldsAndEvidenceWithoutEmbeddingDuplicateContent() {
        var candidate=new ImportMapper(new ObjectMapper()).candidate(Map.of(
                "rawText","姓名：张三",
                "structuredContent",Map.of(
                        "schemaVersion","2.0","profile",Map.of("name","张三","headline","Java开发"),
                        "sections",List.of(Map.of("type","EDUCATION","items",List.of(Map.of("school","示例大学","major","软件工程")))),
                        "importAnalysis",Map.of("recognizedFieldCount",3,"fields",List.of()))));
        assertThat(candidate.at("/profile/name").asText()).isEqualTo("张三");
        assertThat(candidate.at("/sections/0/items/0/school").asText()).isEqualTo("示例大学");
        assertThat(candidate.path("importReviewPending").asBoolean()).isTrue();
        assertThat(candidate.at("/sourceDocument/importAnalysis/recognizedFieldCount").asInt()).isEqualTo(3);
        assertThat(candidate.at("/sourceDocument/structuredContent").isMissingNode()).isTrue();
    }
}
