package com.careerlens.core.resume;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Current visible facts only; never traverse uploaded originals or arbitrary metadata. */
public final class ResumeFacts {
    private ResumeFacts() {}
    public record Fact(String path, String text) {}
    private static final Set<String> FIELDS = Set.of(
            "name", "headline", "summary", "location", "title", "role", "company",
            "school", "major", "degree", "period", "startDate", "endDate",
            "description", "highlights", "items", "text", "issuer", "award", "certificate");

    public static List<Fact> collect(JsonNode root) {
        List<Fact> result = new ArrayList<>();
        if (root == null || !root.isObject()) return result;
        if (root.has("profile")) walk(root.get("profile"), "$/profile", result);
        JsonNode sections = root.path("sections");
        if (sections.isArray()) for (int i = 0; i < sections.size(); i++)
            walk(sections.get(i), "$/sections/" + i, result);
        return List.copyOf(result);
    }

    private static void walk(JsonNode node, String path, List<Fact> out) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            if (node.path("hidden").asBoolean(false) || node.path("visible").isBoolean() && !node.path("visible").asBoolean()) return;
            node.fields().forEachRemaining(field -> {
                if (FIELDS.contains(field.getKey()))
                    walk(field.getValue(), path + "/" + field.getKey(), out);
            });
        } else if (node.isArray()) {
            for (int i=0;i<node.size();i++) walk(node.get(i),path+"/"+i,out);
        } else if (node.isTextual() && !node.asText().isBlank()) out.add(new Fact(path,node.asText()));
    }
}
