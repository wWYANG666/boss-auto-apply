package com.careerlens.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration
public class HttpClientConfig {
    @Bean
    RestClientCustomizer boundedRequests() {
        return builder -> {
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(2000);
            factory.setReadTimeout(20000);
            builder.requestFactory(factory);
        };
    }
}
