package com.careerlens.core.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiWorkerClientContractTest {

    @Test
    void jdExtractionUsesWorkerLanguageEnum() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiWorkerClient client = new AiWorkerClient("http://ai-worker.test", builder);

        server.expect(once(), requestTo("http://ai-worker.test/internal/v1/jds/extract"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"text":"必须熟悉 Java 与 Spring Boot","language":"auto"}
                        """, true))
                .andRespond(withSuccess("{\"requirements\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.extractRequirements("必须熟悉 Java 与 Spring Boot")).isEmpty();
        server.verify();
    }
}
