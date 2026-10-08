package com.careerlens.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CoreApiIntegrationTest {
    private final java.util.concurrent.atomic.AtomicReference<String> activeRunnerProfile=new java.util.concurrent.atomic.AtomicReference<>("default");
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.careerlens.core.integration.RunnerClient runner;

    @org.junit.jupiter.api.BeforeEach
    void fixtureRunner() {
        activeRunnerProfile.set("default");
        org.mockito.Mockito.when(runner.status()).thenReturn(Map.of("online",true,"details",Map.of("mode","real")));
        org.mockito.Mockito.when(runner.connect(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv->Map.of("connected",true,"profileName",activeRunnerProfile.get(),"accountFingerprint",(activeRunnerProfile.get().equals("default")?"a":"b").repeat(64),"maskedIdentity",activeRunnerProfile.get()));
        org.mockito.Mockito.when(runner.activateBossProfile(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv->{String profile=inv.getArgument(0);activeRunnerProfile.set(profile);return Map.of("connected",false,"profileName",profile);});
        org.mockito.Mockito.when(runner.data(org.mockito.ArgumentMatchers.anyMap())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.when(runner.discoverJobs(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(inv -> List.of(Map.of("platform", inv.getArgument(0), "externalJobId", "TEST-JOB", "company","测试公司","role","Java 开发","description","Java Spring Boot")));
    }
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.careerlens.core.integration.AiWorkerClient ai;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.careerlens.core.common.Jsons jsons;
    @Autowired com.careerlens.core.automation.AutomationService automation;
    @Autowired com.careerlens.core.auth.AuthService auth;

    @Test
    void healthIsPublicAndProtectedResourcesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(get("/api/v1/resumes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void resumeDraftUsesOptimisticRevisionAndPublishesImmutableVersions() throws Exception {
        String token = register("resume");
        JsonNode created = responseJson(postJson("/api/v1/resumes", token, Map.of(
                "title", "Java 后端主简历",
                "headline", "Java 后端开发工程师",
                "source", "MANUAL_PUBLISH",
                "content", resumeContent())));
        String resumeId = created.at("/resume/id").asText();
        long revision = created.at("/draft/revision").asLong();

        mvc.perform(put("/api/v1/resumes/{id}/draft", resumeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of(
                                "baseRevision", revision,
                                "content", resumeContent()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(revision + 1));

        mvc.perform(put("/api/v1/resumes/{id}/draft", resumeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of(
                                "baseRevision", revision,
                                "content", resumeContent()))))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.title").value("DRAFT_REVISION_CONFLICT"));

        mvc.perform(post("/api/v1/resumes/{id}/versions", resumeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"MANUAL_PUBLISH\",\"baseRevision\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/v1/resumes/{id}/versions", resumeId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void importedResumeCanBeReparsedFromStoredText() throws Exception {
        String token = register("reparse");
        JsonNode created = responseJson(postJson("/api/v1/resumes", token, Map.of(
                "title", "待重新识别简历",
                "content", Map.of(
                        "profile", Map.of(),
                        "sections", List.of(),
                        "sourceDocument", Map.of(
                                "fileName", "resume.pdf", "rawText", "项目经历 SAMPLE-CHAT 示例即时通信系统",
                                "blocks", List.of(Map.of("page", 2, "text", "项目经历 SAMPLE-CHAT 示例即时通信系统")),
                                "metadata", Map.of("parser", "pdf-text")))
        )));
        String resumeId = created.at("/resume/id").asText();
        long revision = created.at("/draft/revision").asLong();
        Map<String, Object> structured = Map.of(
                "profile", Map.of("name", "赵六"),
                "sections", List.of(Map.of(
                        "elementId", "project-section", "type", "PROJECT", "heading", "项目经历",
                        "hidden", false, "items", List.of(Map.of(
                                "elementId", "project-entry", "title", "SAMPLE-CHAT 示例即时通信系统", "hidden", false)))),
                "importReviewPending", true,
                "importAnalysis", Map.of("fields", List.of(), "recognizedFieldCount", 1));
        org.mockito.Mockito.when(ai.parseResume(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of(
                "fileName", "resume.pdf", "rawText", "项目经历 SAMPLE-CHAT 示例即时通信系统",
                "structuredContent", structured, "blocks", List.of(), "sections", List.of()));

        mvc.perform(post("/api/v1/resumes/{id}:reparse", resumeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("baseRevision", revision))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(revision + 1))
                .andExpect(jsonPath("$.content.importReviewPending").value(true))
                .andExpect(jsonPath("$.content.sections[0].items[0].title").value("SAMPLE-CHAT 示例即时通信系统"))
                .andExpect(jsonPath("$.content.sourceDocument.blocks[0].page").value(2))
                .andExpect(jsonPath("$.content.sourceDocument.metadata.parser").value("pdf-text"));

        mvc.perform(post("/api/v1/resumes/{id}:reparse", resumeId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("baseRevision", revision))))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.title").value("DRAFT_REVISION_CONFLICT"));
    }

    @Test
    void completeResumeJobMatchSuggestionAndAutomationFlow() throws Exception {
        String token = register("flow");
        JsonNode resume = responseJson(postJson("/api/v1/resumes", token, Map.of(
                "title", "Java 后端主简历", "headline", "Java 后端开发工程师",
                "source", "MANUAL_PUBLISH", "content", resumeContent())));
        String resumeVersionId = resume.at("/resume/currentVersionId").asText();

        JsonNode job = responseJson(postJson("/api/v1/job-descriptions", token, Map.of(
                "company", "星河科技", "role", "Java 后端开发工程师", "location", "杭州",
                "salary", "15–30K",
                "rawText", "必须熟悉 Java、Spring Boot、MySQL 与 Redis，本科及以上学历；具备自动化测试经验者优先。")));
        String jobId = job.get("id").asText();
        String jobVersionId = job.get("currentVersionId").asText();

        mvc.perform(post("/api/v1/job-descriptions/{id}/versions/{versionId}:extract", jobId, jobVersionId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirementCount").isNumber());

        JsonNode match = responseJson(postJson("/api/v1/matches", token, Map.of(
                "resumeVersionId", resumeVersionId,
                "jobDescriptionVersionId", jobVersionId,
                "scoringRuleSetId", "rules-zh-en-v1")));
        assertThat(match.get("score").asDouble()).isBetween(0.0, 100.0);
        String matchId = match.get("id").asText();

        mvc.perform(post("/api/v1/matches/{id}/suggestion-batches", matchId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.suggestions").isArray());

        JsonNode boss = responseJson(postJson("/api/v1/platforms/boss/connect", token, Map.of()));
        mvc.perform(patch("/api/v1/execution-policy").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("paused",false,"dailyLimit",151,"dryRun",false))))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/execution-policy").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("paused",false,"dailyLimit",150,"dryRun",false))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dailyLimit").value(150))
                .andExpect(jsonPath("$.platformLimit").value(150)).andExpect(jsonPath("$.remaining").value(150));
        JsonNode liepin = responseJson(postJson("/api/v1/platforms/liepin/connect", token, Map.of()));

        JsonNode discovery = responseJson(postJson("/api/v1/discovery-runs", token, Map.of(
                "accountIds", List.of(boss.get("id").asText(), liepin.get("id").asText()),
                "resumeVersionId", resumeVersionId,
                "spec", Map.of("keyword", "Java 后端", "cities", List.of("杭州"), "maxPages", 1))));
        String discoveryId = discovery.get("id").asText();

        JsonNode discoveredJobs = responseJson(getAuthorized("/api/v1/discovery-runs/" + discoveryId + "/jobs", token));
        assertThat(discoveredJobs.isArray()).isTrue();
        assertThat(discoveredJobs.size()).isGreaterThan(0);
        String discoveredJobId = discoveredJobs.get(0).get("id").asText();

        JsonNode plans = responseJson(postJson("/api/v1/application-plans", token, Map.of(
                "discoveryRunId", discoveryId,
                "jobIds", List.of(discoveredJobId),
                "resumeVersionId", resumeVersionId)));
        String planId = plans.get(0).get("id").asText();

        JsonNode approval = responseJson(postJson("/api/v1/application-plans/approve", token, Map.of(
                "planIds", List.of(planId), "acknowledged", true)));
        assertThat(approval.get("approvedCount").asInt()).isEqualTo(1);
        String taskId = approval.get("tasks").get(0).get("id").asText();
        mvc.perform(patch("/api/v1/application-plans/"+planId).header("Authorization",bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"greeting\":\"changed after approval\"}"))
                .andExpect(status().isConflict());

        automation.transition(auth.authenticate(token).orElseThrow().id(), UUID.fromString(taskId),
                "awaiting_captcha", 60, "等待平台验证", "请在本地浏览器完成验证", null);

        mvc.perform(post("/api/v1/automation-tasks/{id}/human-action/resolved", taskId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("queued"));
    }

    @Test
    void failedTasksDoNotConsumeTheLocalDailyApprovalLimit() throws Exception {
        String token = register("daily-limit-failed");
        JsonNode resume = responseJson(postJson("/api/v1/resumes", token, Map.of(
                "title", "每日额度测试简历", "source", "MANUAL_PUBLISH", "content", resumeContent())));
        String version = resume.at("/resume/currentVersionId").asText();
        JsonNode boss = responseJson(postJson("/api/v1/platforms/boss/connect", token, Map.of()));
        org.mockito.Mockito.when(runner.discoverJobs(org.mockito.ArgumentMatchers.eq("boss"),org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(List.of(
                        Map.of("platform","boss","externalJobId","DAILY-LIMIT-A","company","额度公司A","role","Java开发","description","Java Spring"),
                        Map.of("platform","boss","externalJobId","DAILY-LIMIT-B","company","额度公司B","role","Java开发","description","Java Spring")));
        mvc.perform(patch("/api/v1/execution-policy").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("paused", false, "dailyLimit", 1, "dryRun", false))))
                .andExpect(status().isOk());

        JsonNode discovery = responseJson(postJson("/api/v1/discovery-runs", token, Map.of(
                "accountIds", List.of(boss.get("id").asText()), "resumeVersionId", version,
                "spec", Map.of("keyword", "Java", "cities", List.of("南京"), "minimumScore", 0))));
        JsonNode jobs = responseJson(getAuthorized("/api/v1/discovery-runs/" + discovery.get("id").asText() + "/jobs", token));
        assertThat(jobs.size()).isGreaterThanOrEqualTo(2);

        JsonNode firstPlans = responseJson(postJson("/api/v1/application-plans", token, Map.of(
                "discoveryRunId", discovery.get("id").asText(), "resumeVersionId", version,
                "jobIds", List.of(jobs.get(0).get("id").asText()))));
        JsonNode firstApproval = responseJson(postJson("/api/v1/application-plans/approve", token, Map.of(
                "planIds", List.of(firstPlans.get(0).get("id").asText()), "acknowledged", true)));
        UUID firstTask = UUID.fromString(firstApproval.get("tasks").get(0).get("id").asText());
        jdbc.update("UPDATE automation_task SET task_status='failed' WHERE id=?", firstTask);

        JsonNode secondPlans = responseJson(postJson("/api/v1/application-plans", token, Map.of(
                "discoveryRunId", discovery.get("id").asText(), "resumeVersionId", version,
                "jobIds", List.of(jobs.get(1).get("id").asText()))));
        JsonNode secondApproval = responseJson(postJson("/api/v1/application-plans/approve", token, Map.of(
                "planIds", List.of(secondPlans.get(0).get("id").asText()), "acknowledged", true)));
        assertThat(secondApproval.get("approvedCount").asInt()).isEqualTo(1);
    }

    @Test
    void recruiterCooldownDoesNotMergeSameDisplayNameAcrossCompanies() throws Exception {
        String token=register("recruiter-scope");
        JsonNode resume=responseJson(postJson("/api/v1/resumes",token,Map.of(
                "title","招聘者冷却测试简历","source","MANUAL_PUBLISH","content",resumeContent())));
        String version=resume.at("/resume/currentVersionId").asText();
        JsonNode boss=responseJson(postJson("/api/v1/platforms/boss/connect",token,Map.of()));
        org.mockito.Mockito.when(runner.discoverJobs(org.mockito.ArgumentMatchers.eq("boss"),org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(List.of(
                        Map.of("platform","boss","externalJobId","RECRUITER-A","company","公司A","role","Java开发","description","Java Spring","recruiter","刘女士"),
                        Map.of("platform","boss","externalJobId","RECRUITER-B","company","公司B","role","Java开发","description","Java Spring","recruiter","刘女士")));
        JsonNode discovery=responseJson(postJson("/api/v1/discovery-runs",token,Map.of(
                "accountIds",List.of(boss.get("id").asText()),"resumeVersionId",version,
                "spec",Map.of("keyword","Java","cities",List.of("南京"),"sameRecruiterDays",14))));
        postJson("/api/v1/applications",token,Map.of("company","公司A","role","Java开发","platform","boss","externalJobId","RECRUITER-A"));

        JsonNode remaining=responseJson(getAuthorized("/api/v1/discovery-runs/"+discovery.get("id").asText()+"/jobs",token));
        assertThat(remaining.size()).isEqualTo(1);
        assertThat(remaining.get(0).get("company").asText()).isEqualTo("公司B");
    }

    @Test
    void emptyWorkspaceRejectsFixtureConnectionAndForgedTaskStatus() throws Exception {
        String token = register("empty");
        mvc.perform(get("/api/v1/resumes").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        org.mockito.Mockito.when(runner.status()).thenReturn(Map.of("online",true,"details",Map.of("mode","fake")));
        mvc.perform(post("/api/v1/platforms/boss/connect").header("Authorization", bearer(token)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("RUNNER_TEST_MODE"));
        mvc.perform(get("/api/v1/platforms").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(patch("/api/v1/automation-tasks/" + UUID.randomUUID())
                .header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"succeeded\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void acceptedRewriteCreatesVersionAndRejectsStaleReapplication() throws Exception {
        String token = register("rewrite");
        JsonNode resume = responseJson(postJson("/api/v1/resumes", token, Map.of(
                "title", "测试简历", "source", "MANUAL_PUBLISH", "content", resumeContent())));
        String versionId = resume.at("/resume/currentVersionId").asText();
        JsonNode job = responseJson(postJson("/api/v1/job-descriptions",token,Map.of(
                "company","测试公司","role","开发","rawText","必须熟悉 Java")));
        postJson("/api/v1/job-descriptions/"+job.get("id").asText()+"/versions/"+job.get("currentVersionId").asText()+":extract",token,Map.of());
        JsonNode match = responseJson(postJson("/api/v1/matches",token,Map.of(
                "resumeVersionId",versionId,"jobDescriptionVersionId",job.get("currentVersionId").asText())));
        String before = "使用 Spring Boot、MySQL 与 Redis 完成预约和库存模块。";
        String after = "基于 Spring Boot、MySQL、Redis 实现预约及库存模块。";
        org.mockito.Mockito.when(ai.suggestionResponse(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(Map.of("providerMode","test-fixture","suggestions",List.of(Map.of("kind","REWRITE","targetElementId","$/sections/0/highlights/0",
                        "before",before,"after",after,"reason","改善表达"))));
        JsonNode batch = responseJson(postJson("/api/v1/matches/"+match.get("id").asText()+"/suggestion-batches",token,Map.of()));
        String suggestionId = batch.get("suggestions").get(0).get("id").asText();
        mvc.perform(patch("/api/v1/suggestions/"+suggestionId).header("Authorization",bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"accepted\"}"))
                .andExpect(status().isOk());
        String path = "/api/v1/suggestion-batches/"+batch.get("id").asText()+":apply";
        Map<String,Object> apply = Map.of("suggestionIds",List.of(suggestionId),"expectedResumeVersionId",versionId);
        JsonNode result = responseJson(postJson(path,token,apply));
        assertThat(result.get("appliedCount").asInt()).isEqualTo(1);
        JsonNode versions = responseJson(getAuthorized("/api/v1/resumes/"+resume.at("/resume/id").asText()+"/versions",token));
        assertThat(versions.get(0).at("/content/sections/0/highlights/0").asText()).isEqualTo(after);
        assertThat(versions.get(1).at("/content/sections/0/highlights/0").asText()).isEqualTo(before);
        mvc.perform(post(path).header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(apply))).andExpect(status().isConflict());
    }

    @Test
    void scoringUsesCurrentPositiveFactsAndDoesNotFullySatisfyCompositeRequirements() throws Exception {
        String token = register("facts");
        JsonNode resume = responseJson(postJson("/api/v1/resumes",token,Map.of(
                "title","事实测试","source","MANUAL_PUBLISH","content",Map.of(
                        "profile",Map.of("summary","JavaScript开发，未使用过Java"),
                        "sourceDocument",Map.of("rawText","Java"),
                        "sections",List.of(Map.of("type","SKILLS","hidden",true,"items",List.of("Java")))))));
        JsonNode job = responseJson(postJson("/api/v1/job-descriptions",token,Map.of(
                "company","测试公司","role","开发","rawText","必须熟悉 Java")));
        postJson("/api/v1/job-descriptions/"+job.get("id").asText()+"/versions/"+job.get("currentVersionId").asText()+":extract",token,Map.of());
        JsonNode result=responseJson(postJson("/api/v1/matches",token,Map.of(
                "resumeVersionId",resume.at("/resume/currentVersionId").asText(),
                "jobDescriptionVersionId",job.get("currentVersionId").asText())));
        assertThat(result.get("score").asInt()).isZero();
        JsonNode positive = responseJson(postJson("/api/v1/resumes",token,Map.of(
                "title","部分满足","source","MANUAL_PUBLISH","content",Map.of("profile",Map.of("summary","Java开发"),"sections",List.of()))));
        JsonNode composite = responseJson(postJson("/api/v1/job-descriptions",token,Map.of(
                "company","测试公司","role","开发","rawText","必须熟悉 Java 和 MySQL")));
        postJson("/api/v1/job-descriptions/"+composite.get("id").asText()+"/versions/"+composite.get("currentVersionId").asText()+":extract",token,Map.of());
        JsonNode partial=responseJson(postJson("/api/v1/matches",token,Map.of(
                "resumeVersionId",positive.at("/resume/currentVersionId").asText(),
                "jobDescriptionVersionId",composite.get("currentVersionId").asText())));
        assertThat(partial.get("score").asInt()).isBetween(1,75);
        assertThat(partial.get("scoringRuleVersion").asText()).isEqualTo("rules-lexical-1.3");
    }

    @Test
    void durableOutboxCompletesWithoutBrowserPolling() throws Exception {
        String token=register("outbox");
        JsonNode resume=responseJson(postJson("/api/v1/resumes",token,Map.of("title","调度测试","source","MANUAL_PUBLISH","content",resumeContent())));
        String version=resume.at("/resume/currentVersionId").asText();
        JsonNode account=responseJson(postJson("/api/v1/platforms/boss/connect",token,Map.of()));
        JsonNode run=responseJson(postJson("/api/v1/discovery-runs",token,Map.of("accountIds",List.of(account.get("id").asText()),"resumeVersionId",version,"spec",Map.of("keyword","Java"))));
        JsonNode jobs=responseJson(getAuthorized("/api/v1/discovery-runs/"+run.get("id").asText()+"/jobs",token));
        JsonNode plans=responseJson(postJson("/api/v1/application-plans",token,Map.of("discoveryRunId",run.get("id").asText(),"resumeVersionId",version,"jobIds",List.of(jobs.get(0).get("id").asText()))));
        JsonNode approved=responseJson(postJson("/api/v1/application-plans/approve",token,Map.of("planIds",List.of(plans.get(0).get("id").asText()),"acknowledged",true)));
        UUID taskId=UUID.fromString(approved.get("tasks").get(0).get("id").asText());
        org.mockito.Mockito.when(runner.prepare(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("id","runner-prepare","status","queued","type","prepare"));
        org.mockito.Mockito.when(runner.task("runner-prepare")).thenReturn(Map.of("id","runner-prepare","status","succeeded","type","prepare","result",Map.of("pageFingerprint","test-fingerprint")));
        org.mockito.Mockito.when(runner.approve(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("signature","test-proof"));
        org.mockito.Mockito.when(runner.submit(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("id","runner-submit","status","queued","type","submit"));
        org.mockito.Mockito.when(runner.task("runner-submit")).thenReturn(Map.of("id","runner-submit","status","succeeded","type","submit","result",Map.of(
                "outcome","MESSAGE_SENT","receiptId","OBSERVATION-test","receiptSource","observation")));
        jdbc.update("UPDATE automation_outbox SET phase='DONE' WHERE task_id<>?",taskId);
        var dispatcher=new com.careerlens.core.automation.AutomationDispatcher(jdbc,automation,runner,jsons);
        UUID owner=auth.authenticate(token).orElseThrow().id();
        UUID identity=jdbc.queryForObject("SELECT id FROM platform_identity WHERE user_id=? AND active=TRUE",UUID.class,owner);
        jdbc.update("UPDATE platform_execution_policy SET paused=TRUE WHERE user_id=? AND platform_identity_id=?",owner,identity);
        dispatcher.tick();
        assertThat(jdbc.queryForObject("SELECT phase FROM automation_outbox WHERE task_id=?",String.class,taskId)).isEqualTo("NEW");
        org.mockito.Mockito.verify(runner,org.mockito.Mockito.never()).prepare(org.mockito.ArgumentMatchers.any());
        jdbc.update("UPDATE platform_execution_policy SET paused=FALSE WHERE user_id=? AND platform_identity_id=?",owner,identity);
        for(int i=0;i<7;i++)dispatcher.tick();
        assertThat(jdbc.queryForObject("SELECT phase FROM automation_outbox WHERE task_id=?",String.class,taskId)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT task_status FROM automation_task WHERE id=?",String.class,taskId)).isEqualTo("succeeded");
        org.mockito.Mockito.verify(runner,org.mockito.Mockito.times(1)).submit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void durableOneStopMaintainsTwoActiveSlotsAndRefillsImmediately() throws Exception {
        String token=register("one-stop");
        JsonNode resume=responseJson(postJson("/api/v1/resumes",token,Map.of("title","一条龙资料","source","MANUAL_PUBLISH","content",resumeContent())));
        String version=resume.at("/resume/currentVersionId").asText();
        postJson("/api/v1/platforms/boss/connect",token,Map.of());
        JsonNode started=responseJson(postJson("/api/v1/one-stop-runs",token,Map.of("resumeVersionId",version,"platforms",List.of("boss"),
                "spec",Map.of("keyword","Java","cities",List.of("南京"),"minimumScore",0))));
        JsonNode paused=responseJson(postJson("/api/v1/one-stop-runs/"+started.get("id").asText()+"/pause",token,Map.of()));
        assertThat(paused.get("status").asText()).isEqualTo("paused");
        JsonNode resumed=responseJson(postJson("/api/v1/one-stop-runs/"+started.get("id").asText()+"/resume",token,Map.of()));
        assertThat(resumed.get("status").asText()).isEqualTo("running");
        UUID owner=auth.authenticate(token).orElseThrow().id();
        var oneStop=new com.careerlens.core.automation.OneStopDispatcher(jdbc,jsons,automation);
        oneStop.tick();
        org.mockito.Mockito.when(runner.discover(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("id","one-stop-discovery","status","queued"));
        org.mockito.Mockito.when(runner.task("one-stop-discovery")).thenReturn(Map.of("id","one-stop-discovery","status","succeeded","result",
                java.util.stream.IntStream.range(0,8).mapToObj(index->Map.<String,Object>of("platform","boss","externalJobId","ONE-"+index,
                        "company","公司"+index,"role","Java 开发","description","Java Spring Boot","contacted",index==7)).toList()));
        var discovery=new com.careerlens.core.automation.DiscoveryDispatcher(jdbc,runner,automation,jsons);
        discovery.tick();discovery.tick();
        jdbc.update("UPDATE one_stop_run SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",UUID.fromString(started.get("id").asText()));
        oneStop.tick();oneStop.tick();
        assertThat(jdbc.queryForObject("SELECT status FROM one_stop_run WHERE id=?",String.class,UUID.fromString(started.get("id").asText()))).isEqualTo("running");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=?",Long.class,owner)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM application_plan WHERE user_id=? AND external_job_id='ONE-7'",Long.class,owner)).isZero();
        UUID firstTask=jdbc.queryForObject("SELECT id FROM automation_task WHERE user_id=? ORDER BY created_at LIMIT 1",UUID.class,owner);
        jdbc.update("UPDATE automation_task SET task_status='failed' WHERE id=?",firstTask);
        jdbc.update("UPDATE automation_outbox SET phase='FAILED' WHERE task_id=?",firstTask);
        jdbc.update("UPDATE one_stop_run SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",UUID.fromString(started.get("id").asText()));
        oneStop.tick();oneStop.tick();
        assertThat(jdbc.queryForObject("SELECT processed_count FROM one_stop_run WHERE id=?",Integer.class,UUID.fromString(started.get("id").asText()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=?",Long.class,owner)).isEqualTo(3L);
        assertThat(jsons.list(jdbc.queryForObject("SELECT task_ids_text FROM one_stop_run WHERE id=?",String.class,UUID.fromString(started.get("id").asText())))).hasSize(2);
        UUID unknownTask=UUID.fromString(jsons.list(jdbc.queryForObject("SELECT task_ids_text FROM one_stop_run WHERE id=?",String.class,UUID.fromString(started.get("id").asText()))).get(0).toString());
        jdbc.update("UPDATE automation_task SET task_status='unknown_outcome' WHERE id=?",unknownTask);
        jdbc.update("UPDATE automation_outbox SET phase='UNKNOWN' WHERE task_id=?",unknownTask);
        jdbc.update("UPDATE one_stop_run SET reconcile_state_text=?,next_run_at=CURRENT_TIMESTAMP WHERE id=?",jsons.write(Map.of(unknownTask.toString(),3)),UUID.fromString(started.get("id").asText()));
        oneStop.tick();oneStop.tick();
        assertThat(jdbc.queryForObject("SELECT attention_count FROM one_stop_run WHERE id=?",Integer.class,UUID.fromString(started.get("id").asText()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=?",Long.class,owner)).isEqualTo(4L);
        jdbc.update("UPDATE automation_task SET task_status='failed' WHERE user_id=?",owner);
        jdbc.update("UPDATE automation_outbox SET phase='FAILED' WHERE user_id=?",owner);
        jdbc.update("UPDATE one_stop_run SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",UUID.fromString(started.get("id").asText()));
        oneStop.tick();oneStop.tick();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=?",Long.class,owner)).isEqualTo(6L);
        jdbc.update("UPDATE automation_task SET task_status='failed' WHERE user_id=?",owner);
        jdbc.update("UPDATE automation_outbox SET phase='FAILED' WHERE user_id=?",owner);
        jdbc.update("UPDATE one_stop_run SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",UUID.fromString(started.get("id").asText()));
        oneStop.tick();oneStop.tick();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=?",Long.class,owner)).isEqualTo(7L);
        jdbc.update("UPDATE automation_task SET task_status='failed' WHERE user_id=?",owner);
        jdbc.update("UPDATE automation_outbox SET phase='FAILED' WHERE user_id=?",owner);
        jdbc.update("UPDATE one_stop_run SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",UUID.fromString(started.get("id").asText()));
        oneStop.tick();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM one_stop_processed_job WHERE run_id=?",Long.class,UUID.fromString(started.get("id").asText()))).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=?",Long.class,owner)).isEqualTo(7L);
        jdbc.update("UPDATE automation_task SET retryable=TRUE,failure_category='NOT_SUBMITTED_CONFIRMED' WHERE user_id=? AND task_status='failed'",owner);
        postJson("/api/v1/one-stop-runs/"+started.get("id").asText()+"/stop",token,Map.of());
        JsonNode firstPage=responseJson(getAuthorized("/api/v1/automation-tasks?limit=3",token));
        assertThat(firstPage.size()).isEqualTo(3);
        JsonNode nextPage=responseJson(getAuthorized("/api/v1/automation-tasks?limit=3&before="+firstPage.get(2).get("createdAt").asText(),token));
        assertThat(nextPage.size()).isGreaterThan(0);
        JsonNode summary=responseJson(getAuthorized("/api/v1/automation-tasks/failure-summary",token));
        assertThat(summary.toString()).contains("确认未发送");
        List<String> failedIds=firstPage.findValuesAsText("id");
        JsonNode retried=responseJson(postJson("/api/v1/automation-tasks/batch-retry",token,Map.of("taskIds",failedIds)));
        assertThat(retried.get("completed").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=? AND retry_of_task_id IS NOT NULL",Long.class,owner)).isEqualTo(3L);
        jdbc.update("UPDATE automation_task SET task_status='failed',updated_at=? WHERE user_id=?",java.sql.Timestamp.from(java.time.Instant.now().minus(100,java.time.temporal.ChronoUnit.DAYS)),owner);
        jdbc.update("UPDATE discovery_run SET updated_at=? WHERE user_id=?",java.sql.Timestamp.from(java.time.Instant.now().minus(40,java.time.temporal.ChronoUnit.DAYS)),owner);
        JsonNode archived=responseJson(postJson("/api/v1/maintenance/archive",token,Map.of()));
        assertThat(archived.get("tasks").asInt()).isEqualTo(10);
        assertThat(responseJson(getAuthorized("/api/v1/automation-tasks",token)).size()).isZero();
    }

    @Test
    void oneStopCompletesWhenConfiguredJobTargetIsReached() throws Exception {
        String token=register("one-stop-target");
        JsonNode resume=responseJson(postJson("/api/v1/resumes",token,Map.of("title","目标资料","source","MANUAL_PUBLISH","content",resumeContent())));
        String version=resume.at("/resume/currentVersionId").asText();
        postJson("/api/v1/platforms/boss/connect",token,Map.of());
        JsonNode started=responseJson(postJson("/api/v1/one-stop-runs",token,Map.of("resumeVersionId",version,"platforms",List.of("boss"),
                "spec",Map.of("keyword","Java","cities",List.of("南京"),"minimumScore",0,"maxJobs",2))));
        UUID runId=UUID.fromString(started.get("id").asText());
        UUID owner=auth.authenticate(token).orElseThrow().id();
        var oneStop=new com.careerlens.core.automation.OneStopDispatcher(jdbc,jsons,automation);
        oneStop.tick();
        org.mockito.Mockito.when(runner.discover(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("id","target-discovery","status","queued"));
        org.mockito.Mockito.when(runner.task("target-discovery")).thenReturn(Map.of("id","target-discovery","status","succeeded","result",
                java.util.stream.IntStream.range(0,3).mapToObj(index->Map.<String,Object>of("platform","boss","externalJobId","TARGET-"+index,
                        "company","目标公司"+index,"role","Java 开发","description","Java Spring Boot")).toList()));
        var discovery=new com.careerlens.core.automation.DiscoveryDispatcher(jdbc,runner,automation,jsons);
        discovery.tick();discovery.tick();
        jdbc.update("UPDATE one_stop_run SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",runId);
        oneStop.tick();oneStop.tick();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=?",Long.class,owner)).isEqualTo(2L);
        jdbc.update("UPDATE automation_task SET task_status='failed' WHERE user_id=?",owner);
        jdbc.update("UPDATE automation_outbox SET phase='FAILED' WHERE user_id=?",owner);
        jdbc.update("UPDATE one_stop_run SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",runId);
        oneStop.tick();
        assertThat(jdbc.queryForObject("SELECT status FROM one_stop_run WHERE id=?",String.class,runId)).isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT phase FROM one_stop_run WHERE id=?",String.class,runId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT processed_count FROM one_stop_run WHERE id=?",Integer.class,runId)).isEqualTo(2);
    }

    @Test
    void oneStopTerminatesWithoutHumanVerificationWhenBossDailyQuotaIsReached() throws Exception {
        String token=register("one-stop-daily-quota");
        JsonNode resume=responseJson(postJson("/api/v1/resumes",token,Map.of("title","额度资料","source","MANUAL_PUBLISH","content",resumeContent())));
        String version=resume.at("/resume/currentVersionId").asText();
        postJson("/api/v1/platforms/boss/connect",token,Map.of());
        JsonNode started=responseJson(postJson("/api/v1/one-stop-runs",token,Map.of("resumeVersionId",version,"platforms",List.of("boss"),
                "spec",Map.of("keyword","Java","cities",List.of("南京"),"minimumScore",0,"maxJobs",10))));
        UUID runId=UUID.fromString(started.get("id").asText());
        UUID owner=auth.authenticate(token).orElseThrow().id();
        var oneStop=new com.careerlens.core.automation.OneStopDispatcher(jdbc,jsons,automation);
        oneStop.tick();
        org.mockito.Mockito.when(runner.discover(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("id","quota-discovery","status","queued"));
        org.mockito.Mockito.when(runner.task("quota-discovery")).thenReturn(Map.of("id","quota-discovery","status","succeeded","result",
                java.util.stream.IntStream.range(0,2).mapToObj(index->Map.<String,Object>of("platform","boss","externalJobId","QUOTA-"+index,
                        "company","额度公司"+index,"role","Java 开发","description","Java Spring Boot")).toList()));
        var discovery=new com.careerlens.core.automation.DiscoveryDispatcher(jdbc,runner,automation,jsons);
        discovery.tick();discovery.tick();
        jdbc.update("UPDATE one_stop_run SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",runId);
        oneStop.tick();oneStop.tick();
        List<Object> active=jsons.list(jdbc.queryForObject("SELECT task_ids_text FROM one_stop_run WHERE id=?",String.class,runId));
        UUID quotaTask=UUID.fromString(active.get(0).toString());
        UUID otherTask=UUID.fromString(active.get(1).toString());
        jdbc.update("UPDATE automation_task SET task_status='awaiting_question',current_step='等待本地人工处理',human_action_text='BOSS_DAILY_CONTACT_LIMIT: 每天只与150位BOSS沟通，休息一下，明天再来吧' WHERE id=?",quotaTask);
        jdbc.update("UPDATE automation_task SET task_status='awaiting_login',current_step='等待登录',human_action_text='BOSS_BROWSER_LOGIN_REQUIRED' WHERE id=?",otherTask);
        jdbc.update("UPDATE one_stop_run SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",runId);
        oneStop.tick();
        assertThat(jdbc.queryForObject("SELECT status FROM one_stop_run WHERE id=?",String.class,runId)).isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT phase FROM one_stop_run WHERE id=?",String.class,runId)).isEqualTo("DAILY_LIMIT_REACHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=? AND task_status LIKE 'awaiting_%'",Long.class,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT task_status FROM automation_task WHERE id=?",String.class,quotaTask)).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT task_status FROM automation_task WHERE id=?",String.class,otherTask)).isEqualTo("cancelled");
    }

    @Test
    void oneStopDefersJobLevelHumanActionButPausesForAccountVerification() throws Exception {
        String token=register("one-stop-human");
        JsonNode resume=responseJson(postJson("/api/v1/resumes",token,Map.of("title","人工处理资料","source","MANUAL_PUBLISH","content",resumeContent())));
        String version=resume.at("/resume/currentVersionId").asText();
        postJson("/api/v1/platforms/boss/connect",token,Map.of());
        JsonNode started=responseJson(postJson("/api/v1/one-stop-runs",token,Map.of("resumeVersionId",version,"platforms",List.of("boss"),
                "spec",Map.of("keyword","Java","cities",List.of("南京"),"minimumScore",0))));
        UUID runId=UUID.fromString(started.get("id").asText());
        UUID owner=auth.authenticate(token).orElseThrow().id();
        var oneStop=new com.careerlens.core.automation.OneStopDispatcher(jdbc,jsons,automation);
        oneStop.tick();
        org.mockito.Mockito.when(runner.discover(org.mockito.ArgumentMatchers.any())).thenReturn(Map.of("id","human-discovery","status","queued"));
        org.mockito.Mockito.when(runner.task("human-discovery")).thenReturn(Map.of("id","human-discovery","status","succeeded","result",
                java.util.stream.IntStream.range(0,4).mapToObj(index->Map.<String,Object>of("platform","boss","externalJobId","HUMAN-"+index,
                        "company","人工公司"+index,"role","Java 开发","description","Java Spring Boot")).toList()));
        var discovery=new com.careerlens.core.automation.DiscoveryDispatcher(jdbc,runner,automation,jsons);
        discovery.tick();discovery.tick();
        jdbc.update("UPDATE one_stop_run SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",runId);
        oneStop.tick();oneStop.tick();
        List<Object> active=jsons.list(jdbc.queryForObject("SELECT task_ids_text FROM one_stop_run WHERE id=?",String.class,runId));
        UUID deferred=UUID.fromString(active.get(0).toString());
        String deferredJob=jdbc.queryForObject("SELECT external_job_id FROM automation_task WHERE id=?",String.class,deferred);
        jdbc.update("UPDATE automation_task SET task_status='awaiting_question',current_step='岗位页面需要确认',human_action_text='JOB_LOCAL_CONFIRMATION: 请稍后核对该岗位' WHERE id=?",deferred);
        jdbc.update("UPDATE one_stop_run SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",runId);
        oneStop.tick();oneStop.tick();
        assertThat(jdbc.queryForObject("SELECT status FROM one_stop_run WHERE id=?",String.class,runId)).isEqualTo("running");
        assertThat(jdbc.queryForObject("SELECT processed_count FROM one_stop_run WHERE id=?",Integer.class,runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attention_count FROM one_stop_run WHERE id=?",Integer.class,runId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM one_stop_processed_job WHERE run_id=? AND external_job_id=?",String.class,runId,deferredJob)).isEqualTo("deferred_human");
        assertThat(jsons.list(jdbc.queryForObject("SELECT task_ids_text FROM one_stop_run WHERE id=?",String.class,runId))).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=?",Long.class,owner)).isEqualTo(3L);

        UUID captcha=UUID.fromString(jsons.list(jdbc.queryForObject("SELECT task_ids_text FROM one_stop_run WHERE id=?",String.class,runId)).get(0).toString());
        jdbc.update("UPDATE automation_task SET task_status='awaiting_captcha',current_step='等待安全验证',human_action_text='CAPTCHA_REQUIRED: 请完成验证码' WHERE id=?",captcha);
        jdbc.update("UPDATE one_stop_run SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",runId);
        oneStop.tick();
        assertThat(jdbc.queryForObject("SELECT status FROM one_stop_run WHERE id=?",String.class,runId)).isEqualTo("paused");
        assertThat(jdbc.queryForObject("SELECT phase FROM one_stop_run WHERE id=?",String.class,runId)).isEqualTo("WAIT_GROUP");
    }

    @Test
    void bossProfilesIsolateJobsApplicationsPoliciesAndActiveTasks() throws Exception {
        String token=register("multi-boss");
        JsonNode resume=responseJson(postJson("/api/v1/resumes",token,Map.of("title","多账号资料","source","MANUAL_PUBLISH","content",resumeContent())));
        String version=resume.at("/resume/currentVersionId").asText();
        JsonNode platform=responseJson(postJson("/api/v1/platforms/boss/connect",token,Map.of()));
        JsonNode identities=responseJson(getAuthorized("/api/v1/platform-identities",token));
        String defaultIdentity=identities.get(0).get("id").asText();
        JsonNode runA=responseJson(postJson("/api/v1/discovery-runs",token,Map.of("accountIds",List.of(platform.get("id").asText()),"resumeVersionId",version,"spec",Map.of("keyword","Java"))));
        String jobA=responseJson(getAuthorized("/api/v1/discovery-runs/"+runA.get("id").asText()+"/jobs",token)).get(0).get("id").asText();
        postJson("/api/v1/applications",token,Map.of("company","账号A公司","role","Java","platform","boss","externalJobId","SHARED-JOB"));
        JsonNode second=responseJson(postJson("/api/v1/platform-identities",token,Map.of("profileName","account2","displayName","BOSS账号B")));
        postJson("/api/v1/platform-identities/"+second.get("id").asText()+"/activate",token,Map.of());
        mvc.perform(post("/api/v1/discovery-runs").header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("accountIds",List.of(platform.get("id").asText()),"resumeVersionId",version,"spec",Map.of("keyword","Java")))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("PLATFORM_LOGIN_REQUIRED"));
        postJson("/api/v1/platforms/boss/connect",token,Map.of());
        mvc.perform(get("/api/v1/discovery-runs/"+runA.get("id").asText()+"/jobs").header("Authorization",bearer(token))).andExpect(status().isNotFound());
        JsonNode runB=responseJson(postJson("/api/v1/discovery-runs",token,Map.of("accountIds",List.of(platform.get("id").asText()),"resumeVersionId",version,"spec",Map.of("keyword","Java"))));
        String jobB=responseJson(getAuthorized("/api/v1/discovery-runs/"+runB.get("id").asText()+"/jobs",token)).get(0).get("id").asText();
        assertThat(jobB).isNotEqualTo(jobA);
        postJson("/api/v1/applications",token,Map.of("company","账号B公司","role","Java","platform","boss","externalJobId","SHARED-JOB"));
        assertThat(responseJson(getAuthorized("/api/v1/applications",token)).size()).isEqualTo(1);
        assertThat(responseJson(getAuthorized("/api/v1/applications?allAccounts=true",token)).size()).isEqualTo(2);
        mvc.perform(patch("/api/v1/execution-policy").header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("paused",false,"dailyLimit",33,"dryRun",false)))).andExpect(status().isOk());
        postJson("/api/v1/platform-identities/"+defaultIdentity+"/activate",token,Map.of());
        assertThat(responseJson(getAuthorized("/api/v1/execution-policy",token)).get("dailyLimit").asInt()).isEqualTo(20);
        JsonNode plans=responseJson(postJson("/api/v1/application-plans",token,Map.of("discoveryRunId",runA.get("id").asText(),"resumeVersionId",version,"jobIds",List.of(jobA))));
        postJson("/api/v1/application-plans/approve",token,Map.of("planIds",List.of(plans.get(0).get("id").asText()),"acknowledged",true));
        mvc.perform(post("/api/v1/platform-identities/"+second.get("id").asText()+"/activate").header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("BOSS_ACCOUNT_SWITCH_BLOCKED"));
    }

    @Test
    void businessBackupRestoresIntoEmptyAccount() throws Exception {
        String first=register("backup");
        postJson("/api/v1/resumes",first,Map.of("title","备份简历","source","MANUAL_PUBLISH","content",resumeContent()));
        JsonNode exported=responseJson(getAuthorized("/api/v1/workspace/backup",first));
        String second=register("restore");
        var input=json.convertValue(exported,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        input.put("acknowledged",true);
        postJson("/api/v1/workspace/restore",second,input);
        JsonNode restored=responseJson(getAuthorized("/api/v1/resumes",second));
        assertThat(restored.size()).isEqualTo(1);
        assertThat(restored.get(0).get("title").asText()).isEqualTo("备份简历");
    }

    @Test
    void resumeReviewGeneratesAndAppliesSuggestionsToUnpublishedDraft() throws Exception {
        String token = register("resume-review");
        Map<String, Object> content = Map.of(
                "profile", Map.of("name", "示例用户", "summary", "本人使用Java完成接口开发。"),
                "sections", List.of(Map.of("elementId", "project-section", "type", "PROJECT",
                        "heading", "项目经历", "items", List.of(Map.of(
                                "elementId", "project-entry", "title", "接口服务", "description", "后端项目",
                                "highlights", List.of("使用Java完成接口开发。"))))));
        JsonNode created = responseJson(postJson("/api/v1/resumes", token,
                Map.of("title", "未发布草稿", "content", content)));
        String resumeId = created.at("/resume/id").asText();
        org.mockito.Mockito.when(ai.suggestionResponse(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of(
                "providerMode", "offline-rules", "warnings", List.of(), "suggestions", List.of(Map.of(
                        "kind", "REWRITE", "targetElementId", "$/profile/summary",
                        "before", "本人使用Java完成接口开发。", "after", "使用 Java 完成接口开发。",
                        "reason", "精简表达", "supportingFacts", List.of("Java")))));

        JsonNode batch = responseJson(postJson("/api/v1/resumes/" + resumeId + "/suggestion-batches",
                token, Map.of()));
        assertThat(batch.path("matchId").isMissingNode()).isTrue();
        assertThat(batch.path("resumeVersionId").isMissingNode()).isTrue();
        assertThat(batch.get("draftRevision").asLong()).isEqualTo(1);
        String suggestionId = batch.at("/suggestions/0/id").asText();
        mvc.perform(patch("/api/v1/suggestions/" + suggestionId)
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"accepted\"}"))
                .andExpect(status().isOk());
        JsonNode applied = responseJson(postJson("/api/v1/suggestion-batches/" + batch.get("id").asText() + ":apply",
                token, Map.of("suggestionIds", List.of(suggestionId))));
        assertThat(applied.get("published").asBoolean()).isFalse();
        assertThat(applied.get("draftRevision").asLong()).isEqualTo(2);
        JsonNode updated = responseJson(getAuthorized("/api/v1/resumes/" + resumeId + "/draft", token));
        assertThat(updated.at("/content/profile/summary").asText()).isEqualTo("使用 Java 完成接口开发。");
    }

    @Test
    void bossGreetingRejectsResumeWithoutStructuredProjectEvidence() throws Exception {
        String token = register("boss-no-project");
        JsonNode resume = responseJson(postJson("/api/v1/resumes", token, Map.of(
                "title", "无项目证据简历", "source", "MANUAL_PUBLISH", "content", Map.of(
                        "profile", Map.of("name", "示例用户", "summary", "合成测试说明。".repeat(80)),
                        "sections", List.of(Map.of("type", "PROJECT", "items", List.of()))))));
        String versionId = resume.at("/resume/currentVersionId").asText();
        JsonNode account = responseJson(postJson("/api/v1/platforms/boss/connect", token, Map.of()));
        JsonNode discovery = responseJson(postJson("/api/v1/discovery-runs", token, Map.of(
                "accountIds", List.of(account.get("id").asText()), "resumeVersionId", versionId,
                "spec", Map.of("keyword", "Java", "cities", List.of("杭州")))));
        JsonNode jobs = responseJson(getAuthorized(
                "/api/v1/discovery-runs/" + discovery.get("id").asText() + "/jobs", token));

        mvc.perform(post("/api/v1/application-plans").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of(
                                "discoveryRunId", discovery.get("id").asText(), "resumeVersionId", versionId,
                                "jobIds", List.of(jobs.get(0).get("id").asText())))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("GREETING_PROJECT_REQUIRED"));
    }

    @Test
    void bossGreetingIsEvidenceBasedAndLimitedToOneHundredCharacters() throws Exception {
        String token = register("boss-greeting");
        JsonNode resume = responseJson(postJson("/api/v1/resumes", token, Map.of(
                "title", "BOSS简历", "source", "MANUAL_PUBLISH", "content", resumeContent())));
        String versionId = resume.at("/resume/currentVersionId").asText();
        JsonNode account = responseJson(postJson("/api/v1/platforms/boss/connect", token, Map.of()));
        JsonNode discovery = responseJson(postJson("/api/v1/discovery-runs", token, Map.of(
                "accountIds", List.of(account.get("id").asText()), "resumeVersionId", versionId,
                "spec", Map.of("keyword", "Java", "cities", List.of("杭州")))));
        JsonNode jobs = responseJson(getAuthorized(
                "/api/v1/discovery-runs/" + discovery.get("id").asText() + "/jobs", token));
        JsonNode plans = responseJson(postJson("/api/v1/application-plans", token, Map.of(
                "discoveryRunId", discovery.get("id").asText(), "resumeVersionId", versionId,
                "jobIds", List.of(jobs.get(0).get("id").asText()))));
        String planId = plans.get(0).get("id").asText();
        assertThat(plans.get(0).get("actionType").asText()).isEqualTo("CHAT");
        String greeting = plans.get(0).get("greeting").asText();
        assertThat(greeting).contains("Spring Boot").hasSizeLessThanOrEqualTo(100);
        assertThat(plans.at("/0/greetingEvidence/skills")).isEqualTo(json.valueToTree(List.of("Spring Boot")));
        assertThat(plans.at("/0/greetingCandidates").size()).isEqualTo(3);
        assertThat(plans.at("/0/greetingEvidence/project").asText()).isNotBlank();
        assertThat(greeting).doesNotContain("@", "http", "13800000000", "精通", "专家");
        JsonNode generated=responseJson(postJson("/api/v1/application-plans/"+planId+":generate-greetings",token,Map.of()));
        assertThat(generated.get("candidates").size()).isEqualTo(3);
        assertThat(generated.get("candidates").findValuesAsText("text")).allSatisfy(text->assertThat(text).hasSizeLessThanOrEqualTo(100));
        mvc.perform(patch("/api/v1/application-plans/" + planId)
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("greeting", "x".repeat(101)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("INVALID_BOSS_GREETING"));
        JsonNode approval=responseJson(postJson("/api/v1/application-plans/approve",token,Map.of("planIds",List.of(planId),"acknowledged",true)));
        UUID taskId=UUID.fromString(approval.at("/tasks/0/id").asText());UUID owner=auth.authenticate(token).orElseThrow().id();
        automation.recordExecution(owner,taskId,Map.of("id","runner-greeting","type","submit","status","succeeded","result",Map.of(
                "outcome","MESSAGE_SENT","receiptId","GREETING-1","receiptSource","platform","platformReceiptId","GREETING-1")));
        JsonNode applications=responseJson(getAuthorized("/api/v1/applications",token));
        assertThat(applications.get(0).get("nextAction").asText()).isEqualTo("手机端跟进");
        assertThat(applications.get(0).get("handoffStatus").asText()).isEqualTo("handed_off_to_user");
    }

    @Test
    void greetingDryRunGeneratesTwentyEvidenceBoundPlansAndRejectsBlankResume() throws Exception {
        String token=register("greeting-dry-run");
        JsonNode usable=responseJson(postJson("/api/v1/resumes",token,Map.of("title","真实项目简历","source","MANUAL_PUBLISH","content",resumeContent())));
        String version=usable.at("/resume/currentVersionId").asText();
        JsonNode blank=responseJson(postJson("/api/v1/resumes",token,Map.of("title","空白简历","source","MANUAL_PUBLISH","content",Map.of("profile",Map.of(),"sections",List.of()))));
        String blankVersion=blank.at("/resume/currentVersionId").asText();
        JsonNode boss=responseJson(postJson("/api/v1/platforms/boss/connect",token,Map.of()));
        mvc.perform(put("/api/v1/greeting-policy").header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("activeResumeVersionId",blankVersion,"defaultStyle","PROJECT_EVIDENCE","includeQuestion",true,"maxLength",95,"bannedWords",List.of("精通"),"preferredWords",List.of("做过"),"aiEnabled",false))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("GREETING_RESUME_EMPTY"));
        mvc.perform(put("/api/v1/greeting-policy").header("Authorization",bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("activeResumeVersionId",version,"defaultStyle","SKILL_MATCH","includeQuestion",true,"maxLength",85,"bannedWords",List.of("精通","专家"),"preferredWords",List.of("做过"),"aiEnabled",false))))
                .andExpect(status().isOk());
        List<Map<String,Object>> jobs=java.util.stream.IntStream.range(0,20).mapToObj(index->Map.<String,Object>of(
                "platform","boss","externalJobId","GREETING-JOB-"+index,"company","公司"+index,
                "role",index==0?"AI Agent/Python/Java软件工程师":index==1?"java | PML【项目管理系统】":index==2?"Java(双休+全额社保+高薪急招)":"Java后端开发工程师",
                "description",index==0?"AI应用 大模型 Java Python": "Spring Boot Redis RabbitMQ 后端开发")).toList();
        org.mockito.Mockito.when(runner.discoverJobs(org.mockito.ArgumentMatchers.eq("boss"),org.mockito.ArgumentMatchers.anyMap())).thenReturn(jobs);
        JsonNode discovery=responseJson(postJson("/api/v1/discovery-runs",token,Map.of("accountIds",List.of(boss.get("id").asText()),"resumeVersionId",blankVersion,"spec",Map.of("keyword","Java","cities",List.of("南京")))));
        JsonNode discovered=responseJson(getAuthorized("/api/v1/discovery-runs/"+discovery.get("id").asText()+"/jobs",token));
        List<String> ids=new ArrayList<>();discovered.forEach(job->ids.add(job.get("id").asText()));
        JsonNode plans=responseJson(postJson("/api/v1/application-plans",token,Map.of("discoveryRunId",discovery.get("id").asText(),"resumeVersionId",blankVersion,"jobIds",ids)));
        assertThat(plans.size()).isEqualTo(20);
        plans.forEach(plan->{assertThat(plan.get("resumeVersionId").asText()).isEqualTo(version);assertThat(plan.get("greetingCandidates").size()).isEqualTo(3);assertThat(plan.get("greetingEvidence").get("project").asText()).isNotBlank();assertThat(plan.get("greeting").asText()).hasSizeBetween(30,85).doesNotContain("。。","实现基于","双休","全额社保","高薪","急招"," | ","关注贵司");assertThat(plan.get("greetingCandidates").findValuesAsText("qualityScore").stream().mapToInt(Integer::parseInt).max().orElse(0)).isGreaterThanOrEqualTo(70);});
        assertThat(responseJson(getAuthorized("/api/v1/automation-tasks",token)).size()).isZero();
    }

    @Test
    void rememberMeLoginUsesLongLivedRevocableToken() throws Exception {
        String email = "remember+" + UUID.randomUUID() + "@example.com";
        responseJson(postJson("/api/v1/auth/register", null, Map.of(
                "email", email, "displayName", "记住登录用户", "password", "StrongPass123!")));
        JsonNode remembered = responseJson(postJson("/api/v1/auth/login", null, Map.of(
                "email", email, "password", "StrongPass123!", "rememberMe", true)));
        java.time.Instant expiresAt = java.time.Instant.parse(remembered.get("expiresAt").asText());
        assertThat(expiresAt).isAfter(java.time.Instant.now().plus(29, java.time.temporal.ChronoUnit.DAYS));
        String token = remembered.get("accessToken").asText();
        postJson("/api/v1/auth/logout", token, Map.of());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submittedApplicationRecordsCannotBeDeleted() throws Exception {
        String token=register("application-delete-lock");
        JsonNode pending=responseJson(postJson("/api/v1/applications",token,Map.of(
                "company","待投递公司","role","Java开发","stage","wishlist")));
        JsonNode submitted=responseJson(postJson("/api/v1/applications",token,Map.of(
                "company","已沟通公司","role","Java开发","stage","contacted","platform","boss","externalJobId","LOCKED-JOB")));
        mvc.perform(delete("/api/v1/applications/"+pending.get("id").asText()).header("Authorization",bearer(token)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/applications/"+submitted.get("id").asText()).header("Authorization",bearer(token)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("APPLICATION_DELETE_LOCKED"));
        JsonNode remaining=responseJson(getAuthorized("/api/v1/applications?allAccounts=true",token));
        assertThat(remaining.size()).isEqualTo(1);
        assertThat(remaining.get(0).get("id").asText()).isEqualTo(submitted.get("id").asText());
    }

    @Test
    void dashboardDailyStatsCountsContactAndSubmissionButNotWishlist() throws Exception {
        String token=register("daily-stats");
        postJson("/api/v1/applications",token,Map.of("company","待投递公司","role","Java","stage","wishlist"));
        postJson("/api/v1/applications",token,Map.of("company","沟通公司","role","Java","stage","contacted"));
        postJson("/api/v1/applications",token,Map.of("company","投递公司","role","Java","stage","applied"));
        JsonNode stats=responseJson(getAuthorized("/api/v1/applications/daily-stats?days=7",token));
        assertThat(stats.get("days").asInt()).isEqualTo(7);
        assertThat(stats.get("today").asInt()).isEqualTo(2);
        assertThat(stats.get("total").asInt()).isEqualTo(2);
        assertThat(stats.at("/items/6/contacted").asInt()).isEqualTo(1);
        assertThat(stats.at("/items/6/submitted").asInt()).isEqualTo(1);
    }

    private String register(String prefix) throws Exception {
        JsonNode session = responseJson(postJson("/api/v1/auth/register", null, Map.of(
                "email", prefix + "+" + UUID.randomUUID() + "@example.com",
                "displayName", "测试用户",
                "password", "StrongPass123!")));
        return session.get("accessToken").asText();
    }

    private Map<String, Object> resumeContent() {
        return Map.of(
                "profile", Map.of("name", "林一", "headline", "Java 后端开发工程师"),
                "sections", List.of(
                        Map.of("elementId", "project-1", "type", "PROJECT", "title", "预约平台",
                                "highlights", List.of(
                                        "使用 Spring Boot、MySQL 与 Redis 完成预约和库存模块。",
                                        "通过索引优化将 P95 查询延迟从 420ms 降至 96ms。",
                                        "使用 JUnit 和 Testcontainers 编写自动化测试。")),
                        Map.of("elementId", "education-1", "type", "EDUCATION", "degree", "本科"),
                        Map.of("elementId", "skills-1", "type", "SKILLS",
                                "items", List.of("Java", "Spring Boot", "MySQL", "Redis", "Docker"))));
    }

    private MvcResult postJson(String path, String token, Object body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        if (token != null) request.header("Authorization", bearer(token));
        return mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn();
    }

    private MvcResult getAuthorized(String path, String token) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private String bearer(String token) { return "Bearer " + token; }
}
