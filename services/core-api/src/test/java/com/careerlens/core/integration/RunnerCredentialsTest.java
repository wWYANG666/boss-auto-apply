package com.careerlens.core.integration;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RunnerCredentialsTest {
    @Test void encryptedTokensRoundTripAndRejectWrongKey() {
        var credentials=new RunnerCredentials(null,"a".repeat(32));
        String encrypted=credentials.encrypt("private-device-token");
        assertThat(encrypted).doesNotContain("private-device-token");
        assertThat(credentials.decrypt(encrypted)).isEqualTo("private-device-token");
        assertThatThrownBy(()->new RunnerCredentials(null,"b".repeat(32)).decrypt(encrypted))
                .hasMessageContaining("设备凭据解密失败");
        assertThatThrownBy(()->new RunnerCredentials(null,"").encrypt("token"))
                .hasMessageContaining("RUNNER_ENCRYPTION_KEY");
    }
}
