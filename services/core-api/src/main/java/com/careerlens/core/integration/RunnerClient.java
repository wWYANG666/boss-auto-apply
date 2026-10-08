package com.careerlens.core.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.*;

@Component
public class RunnerClient {
    private final RestClient client;
    private final RunnerCredentials credentials;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public RunnerClient(@Value("${careerlens.runner.base-url:http://localhost:43120}") String baseUrl,
                        RestClient.Builder builder, RunnerCredentials credentials, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.credentials=credentials; this.mapper=mapper;
        client = builder.baseUrl(baseUrl).build();
    }

    public Map<String, Object> status() {
        long started=System.nanoTime();
        try {
            Map<String, Object> health = stringify(client.get().uri("/health").retrieve().body(Map.class));
            return Map.of("online", true, "name", "CareerLens 本地执行器", "version",
                    Objects.toString(health.getOrDefault("version", "runner 0.1.0")), "lastHeartbeatAt", Instant.now(),
                    "latencyMs", (System.nanoTime()-started)/1_000_000, "globalPaused", false, "details", health);
        } catch (RestClientException ex) {
            return Map.of("online", false, "name", "CareerLens 本地执行器", "version", "unknown",
                    "lastHeartbeatAt", Instant.now(), "globalPaused", true);
        }
    }

    public Map<String,Object> pair(UUID owner,String code) { return post("/v1/pair",Map.of("ownerId",owner.toString(),"code",code)); }
    public Map<String,Object> rotate(String token) {return post("/v1/device/rotate",Map.of("token",token));}
    public Map<String,Object> commitRotation() {return post("/v1/device/rotate/commit",Map.of());}
    public Map<String,Object> revoke() {return post("/v1/device/revoke",Map.of());}
    private java.util.function.Consumer<org.springframework.http.HttpHeaders> signed(String method,String path,String body) {
        return headers -> {
            UUID owner=credentials.currentOwner();
            if(owner==null)return;
            headers.set("X-Runner-Owner-Id",owner.toString());
            credentials.currentToken().ifPresent(token -> {
                headers.setBearerAuth(token);
                String timestamp=Long.toString(System.currentTimeMillis()), nonce=UUID.randomUUID().toString();
                String input=String.join("\n",method,path,timestamp,nonce,com.careerlens.core.common.Hashing.sha256(body));
                try {
                    var mac=javax.crypto.Mac.getInstance("HmacSHA256");
                    mac.init(new javax.crypto.spec.SecretKeySpec(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
                    headers.set("X-Runner-Timestamp",timestamp);
                    headers.set("X-Runner-Nonce",nonce);
                    headers.set("X-Runner-Signature",java.util.HexFormat.of().formatHex(mac.doFinal(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                } catch(Exception ex){throw new IllegalStateException("Runner request signing failed",ex);}
            });
        };
    }

    public Map<String,Object> platformResume(){return data(get("/v1/platforms/liepin/resume"));}
    public Map<String,Object> bossDiagnostics(){return data(get("/v1/platforms/boss/diagnostics"));}
    public Map<String,Object> bossProfiles(){return data(get("/v1/platforms/boss/profiles"));}
    public Object bossCities(){return get("/v1/platforms/boss/cities").getOrDefault("data",List.of());}
    public Map<String,Object> activateBossProfile(String name){return data(post("/v1/platforms/boss/profiles/"+name+"/activate",Map.of()));}
    public Map<String, Object> getPlatforms() { return get("/v1/platforms"); }
    public Map<String, Object> discover(Object body) { return post("/v1/discover", body); }
    public Map<String, Object> prepare(Object body) { return post("/v1/applications/prepare", body); }
    public Map<String, Object> approve(Object body) { return post("/v1/approvals", body); }
    public Map<String, Object> submit(Object body) { return post("/v1/applications/submit", body); }
    public Map<String, Object> reconcile(Object body) { return post("/v1/applications/reconcile", body); }
    public Map<String, Object> connect(String platform) { return post("/v1/platforms/" + platform + "/connect", Map.of()); }
    public void disconnect(String platform) { client.delete().uri("/v1/platforms/" + platform + "/connect").headers(signed("DELETE","/v1/platforms/" + platform + "/connect","")).retrieve().toBodilessEntity(); }
    public Map<String, Object> task(String taskId) { return get("/v1/tasks/" + taskId); }
    public Map<String, Object> taskByCommand(String commandId) { return get("/v1/tasks/by-command/" + commandId); }
    public Map<String, Object> resolveHumanAction(String taskId) {
        return post("/v1/tasks/" + taskId + "/human-action/resolved", Map.of());
    }
    public Map<String,Object> autoResolveHumanAction(String taskId){
        return post("/v1/tasks/"+taskId+"/human-action/auto-resolve",Map.of());
    }

    /**
     * The local runner acknowledges commands before executing them. Most fixture commands finish almost
     * immediately, while browser-backed commands can take longer. This bounded poll keeps the API request
     * responsive and lets callers continue later through the task sync endpoint.
     */
    public Map<String, Object> awaitTask(String taskId) {
        Map<String, Object> last = Map.of();
        for (int attempt = 0; attempt < 40; attempt++) {
            last = data(task(taskId));
            String status = Objects.toString(last.getOrDefault("status", ""));
            if (Set.of("succeeded", "failed", "waiting_human", "unknown_outcome", "cancelled").contains(status)) {
                return last;
            }
            try { Thread.sleep(50); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    public Map<String, Object> data(Map<String, Object> response) {
        return map(response.getOrDefault("data", response));
    }

    public Map<String,Object> storage(){return data(get("/v1/storage"));}
    public Map<String,Object> cleanupLegacyStorage(){return data(post("/v1/storage/cleanup-legacy",Map.of()));}
    public Map<String,Object> cancelTask(String taskId){return data(post("/v1/tasks/"+taskId+"/cancel",Map.of()));}

    public boolean unavailable(Map<String, Object> response) {
        return Boolean.FALSE.equals(response.get("available")) || "RUNNER_OFFLINE".equals(response.get("error"));
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> discoverJobs(String platform, Map<String, Object> searchSpec) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("keyword", Objects.toString(searchSpec.getOrDefault("keyword", searchSpec.getOrDefault("query", "Java 后端"))));
        Object cities = searchSpec.getOrDefault("cities", List.of(searchSpec.getOrDefault("city", "杭州")));
        spec.put("cities", cities instanceof List<?> ? cities : List.of(Objects.toString(cities)));
        spec.put("salary", Objects.toString(searchSpec.getOrDefault("salary", "")));
        spec.put("experience", Objects.toString(searchSpec.getOrDefault("experience", "")));
        spec.put("degree", Objects.toString(searchSpec.getOrDefault("degree", "")));
        spec.put("maxPages", number(searchSpec.getOrDefault("maxPages", 2), 2));

        String commandId = UUID.randomUUID().toString();
        Map<String, Object> accepted = post("/v1/discover", Map.of(
                "commandId", commandId,
                "platform", platform,
                "scenario", Objects.toString(searchSpec.getOrDefault("scenario", "happy_path")),
                "spec", spec));
        Map<String, Object> data = map(accepted.getOrDefault("data", accepted));
        String taskId = Objects.toString(data.getOrDefault("id", ""));
        if (taskId.isBlank()) throw com.careerlens.core.common.ApiException.conflict("RUNNER_UNAVAILABLE", "执行器未接受发现请求");

        for (int attempt = 0; attempt < 40; attempt++) {
            Map<String, Object> taskResponse = get("/v1/tasks/" + taskId);
            Map<String, Object> task = data(taskResponse);
            String status = Objects.toString(task.getOrDefault("status", ""));
            if ("succeeded".equals(status)) {
                Object result = task.get("result");
                if (!(result instanceof List<?> list)) return List.of();
                List<Map<String, Object>> jobs = new ArrayList<>();
                for (Object item : list) {
                    Map<String, Object> job = new LinkedHashMap<>(map(item));
                    if ("fake".equals(Objects.toString(job.get("platform")))) job.put("platform", platform);
                    jobs.add(job);
                }
                return jobs;
            }
            if (Set.of("failed", "waiting_human", "unknown_outcome", "cancelled").contains(status)) throw com.careerlens.core.common.ApiException.conflict("DISCOVERY_REQUIRES_ATTENTION", "职位发现未完成：" + Objects.toString(task.get("errorMessage"), status));
            try { Thread.sleep(50); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return List.of(); }
        }
        throw com.careerlens.core.common.ApiException.conflict("DISCOVERY_PENDING", "职位发现仍在运行，请在本地执行器检查任务");
    }

    private Map<String, Object> get(String path) {
        try { return stringify(client.get().uri(path).headers(signed("GET",path,"")).retrieve().body(Map.class)); }
        catch (org.springframework.web.client.RestClientResponseException ex) {
            if (ex.getStatusCode().value()==401) throw com.careerlens.core.common.ApiException.conflict("RUNNER_AUTH_REQUIRED","请先配对当前账号和本机设备");
            return Map.of("available",false,"error","RUNNER_REJECTED");
        }
        catch (RestClientException ex) { return Map.of("available", false, "error", "RUNNER_OFFLINE"); }
    }

    private Map<String, Object> post(String path, Object body) {
        try {
            String json=mapper.writeValueAsString(body);
            return stringify(client.post().uri(path).headers(signed("POST",path,json)).contentType(MediaType.APPLICATION_JSON).body(json)
                    .retrieve().body(Map.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid Runner command",ex);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            if (ex.getStatusCode().value()==401) throw com.careerlens.core.common.ApiException.conflict("RUNNER_AUTH_REQUIRED","请先配对当前账号和本机设备");
            return Map.of("available",false,"error","/v1/applications/submit".equals(path) ? "RUNNER_SUBMIT_UNKNOWN" : "RUNNER_REJECTED");
        } catch (RestClientException ex) {
            return Map.of("available", false, "error", "/v1/applications/submit".equals(path) ? "RUNNER_SUBMIT_UNKNOWN" : "RUNNER_OFFLINE");
        }
    }

    private Map<String, Object> stringify(Map<?, ?> input) {
        if (input == null) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Map<String, Object> map(Object input) {
        if (!(input instanceof Map<?, ?> value)) return Collections.emptyMap();
        return stringify(value);
    }

    private int number(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(Objects.toString(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
