package com.careerlens.core.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class Jsons {
    private final ObjectMapper mapper;

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("JSON序列化失败", ex);
        }
    }

    public JsonNode tree(String json) {
        try {
            return mapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("JSON格式错误", ex);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "{}" : json, Map.class);
        } catch (JsonProcessingException ex) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "[]" : json, List.class);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }
}
