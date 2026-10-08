package com.careerlens.core.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResumeFactsTest {
    @Test
    void excludesOriginalsMetadataContactsAndHiddenContent() throws Exception {
        var root = new ObjectMapper().readTree("""
                {"profile":{"name":"Candidate","summary":"Java APIs","email":"redis@example.test"},
                 "sourceDocument":{"rawText":"Redis Kubernetes"},
                 "sections":[
                  {"type":"PROJECT","elementId":"redis-id","title":"API","highlights":["Spring Boot"],"metadata":{"text":"Redis"}},
                  {"type":"SKILLS","hidden":true,"items":["Redis"]},
                  {"type":"PROJECT","visible":false,"title":"Kubernetes"},
                  {"type":"EXPERIENCE","items":[{"title":"Visible"},{"hidden":true,"title":"Hidden"}]}]}
                """);
        var facts = ResumeFacts.collect(root);
        assertThat(facts).extracting(ResumeFacts.Fact::text)
                .containsExactly("Candidate","Java APIs","API","Spring Boot","Visible");
        assertThat(facts).extracting(ResumeFacts.Fact::path).contains("$/sections/0/highlights/0");
    }
}
