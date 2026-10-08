package com.careerlens.core.config;

import com.careerlens.core.integration.AiWorkerClient;
import com.careerlens.core.integration.RunnerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {
    private final AiWorkerClient aiWorker;
    private final RunnerClient runner;

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("status", "UP", "service", "careerlens-core-api", "time", Instant.now(),
                "dependencies", Map.of("aiWorker", aiWorker.status(), "runner", runner.status()));
    }
}
