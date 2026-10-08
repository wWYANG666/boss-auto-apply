package com.careerlens.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RunnerClientSigningTest {
    @Test void sendsOwnerAndSignatureBoundToExactRequestBody() throws Exception {
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        var credentials=mock(RunnerCredentials.class);
        UUID owner=UUID.randomUUID();
        when(credentials.currentOwner()).thenReturn(owner);
        when(credentials.currentToken()).thenReturn(Optional.of("device-secret"));
        var mapper=new ObjectMapper();
        var runner=new RunnerClient("http://runner.test",builder,credentials,mapper);
        var body=Map.of("commandId","test-command","message","中文");
        String json=mapper.writeValueAsString(body);
        server.expect(requestTo("http://runner.test/v1/applications/prepare"))
                .andExpect(header("Authorization","Bearer device-secret"))
                .andExpect(header("X-Runner-Owner-Id",owner.toString()))
                .andExpect(content().string(json))
                .andExpect(request -> {
                    var headers=request.getHeaders();
                    String data=String.join("\n","POST","/v1/applications/prepare",
                            headers.getFirst("X-Runner-Timestamp"),headers.getFirst("X-Runner-Nonce"),
                            com.careerlens.core.common.Hashing.sha256(json));
                    try {
                        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
                        mac.init(new javax.crypto.spec.SecretKeySpec("device-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
                        assertThat(headers.getFirst("X-Runner-Signature")).isEqualTo(
                                HexFormat.of().formatHex(mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                    }catch(Exception e){throw new AssertionError(e);}
                }).andRespond(withSuccess("{\"data\":{\"id\":\"task\"}}",MediaType.APPLICATION_JSON));
        assertThat(runner.data(runner.prepare(body))).containsEntry("id","task");
        server.verify();
    }
}
