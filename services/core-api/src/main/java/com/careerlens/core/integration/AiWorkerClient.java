package com.careerlens.core.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class AiWorkerClient {
    private final RestClient client;

    public AiWorkerClient(@Value("${careerlens.ai-worker.base-url:http://localhost:8000}") String baseUrl,
                          RestClient.Builder builder) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseResume(Map<String, Object> request) {
        return post("/internal/v1/resumes/parse", request);
    }
    public Map<String,Object> renderResume(Object content) {return post("/internal/v1/resumes/render",content);}
    public Map<String,Object> scoreMatch(Object request) {return post("/internal/v1/matches/score",request);}
    public Map<String,Object> suggestionResponse(Object request) {return post("/internal/v1/suggestions/generate",request);}

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> extractRequirements(String text) {
        Map<String, Object> response = post("/internal/v1/jds/extract", Map.of("text", text, "language", "auto"));
        Object body = response.getOrDefault("requirements", response.get("items"));
        if (body instanceof List<?> list) return (List<Map<String, Object>>) (List<?>) list;
        Object data = response.get("data");
        if (data instanceof Map<?, ?> map) {
            Object nested = map.get("requirements");
            if (nested instanceof List<?> list) return (List<Map<String, Object>>) (List<?>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> generateSuggestions(Map<String, Object> request) {
        Map<String, Object> response = post("/internal/v1/suggestions/generate", request);
        Object body = response.getOrDefault("suggestions", response.get("items"));
        if (body instanceof List<?> list) return (List<Map<String, Object>>) (List<?>) list;
        Object data = response.get("data");
        if (data instanceof Map<?, ?> map && map.get("suggestions") instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        return List.of();
    }

    public Map<String, Object> status() {
        try {
            Map<?, ?> response = client.get().uri("/internal/v1/health").retrieve().body(Map.class);
            if (response == null) return Map.of("online", false);
            return Map.of(
                    "online", true,
                    "status", Objects.toString(response.get("status"), "UP"),
                    "service", Objects.toString(response.get("service"), "careerlens-ai-worker"),
                    "mode", Objects.toString(response.get("providerMode"), "unknown"));
        } catch (RestClientException ex) {
            return Map.of("online", false, "status", "DOWN", "service", "careerlens-ai-worker");
        }
    }

    private Map<String, Object> post(String path, Object request) {
        try {
            Map<?, ?> response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                    .body(request).retrieve().body(Map.class);
            if (response == null) return Collections.emptyMap();
            return response.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        } catch (RestClientException ex) {
            return Collections.emptyMap();
        }
    }
}
