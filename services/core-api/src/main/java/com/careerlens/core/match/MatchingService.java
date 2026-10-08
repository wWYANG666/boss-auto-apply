package com.careerlens.core.match;

import com.careerlens.core.common.ApiException;
import com.careerlens.core.common.Jsons;
import com.careerlens.core.domain.DataStore;
import com.careerlens.core.domain.Entities.*;
import com.careerlens.core.job.JobDescriptionService;
import com.careerlens.core.resume.ResumeService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="careerlens.legacy-resume-optimization.enabled",havingValue="true")
@RequiredArgsConstructor
public class MatchingService {
    public static final String RULE_VERSION = "rules-lexical-1.3";
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9+#.一-龥]+");
    private static final Set<String> STOP_WORDS = Set.of("熟悉", "掌握", "了解", "能够", "使用", "相关", "经验", "以及",
            "进行", "开发", "具备", "优先", "常用", "组件", "基础", "工作", "岗位", "要求", "完成");
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("spring boot", List.of("spring boot", "springboot", "spring-boot")),
            Map.entry("mysql", List.of("mysql")),
            Map.entry("redis", List.of("redis")),
            Map.entry("rabbitmq", List.of("rabbitmq", "rocketmq", "消息队列", "mq")),
            Map.entry("linux", List.of("linux", "ubuntu", "centos")),
            Map.entry("docker", List.of("docker", "容器")),
            Map.entry("testing", List.of("junit", "单元测试", "自动化测试", "testcontainers")),
            Map.entry("java", List.of("java")),
            Map.entry("kubernetes", List.of("kubernetes", "k8s")),
            Map.entry("microservice", List.of("微服务", "spring cloud", "dubbo")));

    private final DataStore store;
    private final Jsons jsons;
    private final ResumeService resumes;
    private final JobDescriptionService jobs;
    private final com.careerlens.core.integration.AiWorkerClient ai;

    @Transactional
    public MatchView run(UUID userId, UUID resumeVersionId, UUID jdVersionId, String requestedRuleVersion) {
        ResumeVersion resumeVersion = resumes.version(userId, resumeVersionId);
        JobDescriptionVersion jdVersion = jobs.version(userId, jdVersionId);
        List<JdRequirement> requirements = store.query(
                "select r from JdRequirement r where r.userId=:uid and r.jdVersionId=:vid order by r.sortOrder",
                JdRequirement.class, Map.of("uid", userId, "vid", jdVersionId));
        if (requirements.isEmpty()) throw ApiException.badRequest("REQUIREMENTS_NOT_EXTRACTED", "请先提取并确认岗位要求");

        JsonNode resumeJson = jsons.tree(resumeVersion.getContentText());
        List<Fragment> fragments = flatten(resumeJson);
        String allText = normalize(fragments.stream().map(Fragment::text).reduce("", (a, b) -> a + "\n" + b));

        Map<String,Object> scoringInput=Map.of(
                "ruleVersion","score-v2","resumeElements",fragments.stream().map(f->Map.of("elementId",f.path(),"text",f.text())).toList(),
                "requirements",requirements.stream().map(r->Map.of("requirementId",r.getId().toString(),"text",r.getRequirementText(),
                        "category",Set.of("SKILL","EDUCATION","EXPERIENCE","LANGUAGE","LOCATION","RESPONSIBILITY","OTHER").contains(r.getCategory())?r.getCategory():"SKILL",
                        "importance","MUST".equals(r.getImportance())?"MUST":"BONUS".equals(r.getImportance())?"PREFERRED":"NORMAL",
                        "hardCondition",r.isHardCondition())).toList());
        JsonNode remote=jsons.tree(jsons.write(fragments.isEmpty()?Map.of():ai.scoreMatch(scoringInput)));
        boolean hasRemote=remote.path("items").isArray() && remote.path("items").size()==requirements.size();
        MatchRun run = new MatchRun();
        run.setUserId(userId);
        run.setResumeVersionId(resumeVersionId);
        run.setJdVersionId(jdVersionId);
        run.setRuleVersion(hasRemote?remote.path("algorithmVersion").asText():RULE_VERSION);
        run.setInputSnapshot(jsons.write(scoringInput));run.setInputHash(com.careerlens.core.common.Hashing.sha256(run.getInputSnapshot()));
        run.setTotalScore(BigDecimal.ZERO);
        run.setBreakdownText("[]");
        store.persist(run);
        store.flush();

        BigDecimal earned = BigDecimal.ZERO;
        BigDecimal maximum = BigDecimal.ZERO;
        boolean missingHard = false;
        Map<String, ScoreBucket> buckets = new LinkedHashMap<>();
        for (JdRequirement requirement : requirements) {
            int weight = weight(requirement.getImportance());
            maximum = maximum.add(BigDecimal.valueOf(weight));
            ItemResult result = scoreRequirement(requirement, fragments, allText, weight);
            if(hasRemote){
                for(JsonNode item:remote.path("items"))if(requirement.getId().toString().equals(item.path("requirementId").asText())){
                    String verdict=item.path("verdict").asText("MISSING");
                    if("CONFLICT".equals(verdict))verdict="MISSING";
                    if("SEMANTIC".equals(verdict))verdict="PARTIAL";
                    var evidence=item.path("evidence");
                    result=new ItemResult(verdict,(int)Math.round(weight*item.path("score").asDouble()/100),
                            evidence.path("resumeQuote").asText(""),evidence.path("resumeElementId").asText(""),
                            "统一评分服务：规则与特征相关性",item.path("confidence").asDouble());
                }
            }
            earned = earned.add(BigDecimal.valueOf(result.score()));
            if (requirement.isHardCondition() && "MISSING".equals(result.verdict())) missingHard = true;
            buckets.computeIfAbsent(requirement.getCategory(), ignored -> new ScoreBucket()).add(result.score(), weight);

            MatchItem item = new MatchItem();
            item.setUserId(userId);
            item.setMatchRunId(run.getId());
            item.setRequirementId(requirement.getId());
            item.setRequirementText(requirement.getRequirementText());
            item.setCategory(requirement.getCategory());
            item.setImportance(requirement.getImportance());
            item.setVerdict(result.verdict());
            item.setScore(BigDecimal.valueOf(result.score()));
            item.setMaxScore(BigDecimal.valueOf(weight));
            item.setJdEvidence(Objects.toString(requirement.getQuoteText(),requirement.getRequirementText()));
            item.setResumeEvidence(result.evidence());
            item.setResumeSource(result.source());
            item.setMatchMethod(result.method());
            item.setConfidence(BigDecimal.valueOf(result.confidence()));
            store.persist(item);
        }
        int score = maximum.signum() == 0 ? 0 : earned.multiply(BigDecimal.valueOf(100)).divide(maximum, 0, RoundingMode.HALF_UP).intValue();
        if(hasRemote)score=(int)Math.round(remote.path("overallScore").asDouble());
        if (missingHard) score = Math.min(score, 69);
        List<Map<String, Object>> breakdown = buckets.entrySet().stream().map(entry -> Map.<String, Object>of(
                "key", key(entry.getKey()), "label", entry.getKey(), "score", entry.getValue().percent())).toList();
        run.setTotalScore(BigDecimal.valueOf(score));
        run.setBreakdownText(jsons.write(breakdown));
        return view(userId, run.getId());
    }

    @Transactional(readOnly = true)
    public MatchView view(UUID userId, UUID id) {
        MatchRun run = owned(userId, id);
        ResumeVersion resumeVersion = resumes.version(userId, run.getResumeVersionId());
        JobDescriptionVersion jdVersion = jobs.version(userId, run.getJdVersionId());
        JobDescription jd = jobs.owned(userId, jdVersion.getJobDescriptionId());
        List<ItemView> items = items(userId, id);
        long mustTotal = items.stream().filter(item -> "必须".equals(item.importance())).count();
        long mustMatched = items.stream().filter(item -> "必须".equals(item.importance()) && Set.of("EXACT","ALIAS").contains(item.verdict())).count();
        long evidenceCount = items.stream().filter(item -> item.resumeEvidence() != null && !item.resumeEvidence().isBlank()).count();
        int evidenceCoverage = items.isEmpty() ? 0 : (int) Math.round(evidenceCount * 100.0 / items.size());
        int score = run.getTotalScore().intValue();
        return new MatchView(run.getId(), jd.getCompany(), jd.getRoleName(), run.getResumeVersionId(),
                resumeVersion.getVersionNumber(), jdVersion.getId(), jdVersion.getVersionNumber(), run.getRuleVersion(),
                score, score >= 80 ? "HIGHLY_SUITABLE" : score >= 65 ? "SUITABLE" : "NEEDS_IMPROVEMENT",
                summary(score, items), (int) mustMatched, (int) mustTotal, evidenceCoverage,
                jsons.list(run.getBreakdownText()), items, run.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<MatchView> list(UUID userId) {
        return store.query("select m from MatchRun m where m.userId=:uid order by m.createdAt desc", MatchRun.class,
                Map.of("uid", userId)).stream().map(run -> view(userId, run.getId())).toList();
    }

    @Transactional(readOnly = true)
    public List<ItemView> items(UUID userId, UUID runId) {
        owned(userId, runId);
        return store.query("select i from MatchItem i where i.userId=:uid and i.matchRunId=:rid order by i.createdAt",
                MatchItem.class, Map.of("uid", userId, "rid", runId)).stream().map(this::itemView).toList();
    }

    @Transactional(readOnly = true)
    public ItemView item(UUID userId, UUID runId, UUID itemId) {
        owned(userId, runId);
        return store.one("select i from MatchItem i where i.id=:id and i.userId=:uid and i.matchRunId=:rid",
                MatchItem.class, Map.of("id", itemId, "uid", userId, "rid", runId)).map(this::itemView)
                .orElseThrow(() -> ApiException.notFound("匹配证据"));
    }

    @Transactional
    public MatchView rerun(UUID userId, UUID id) {
        MatchRun existing = owned(userId, id);
        return run(userId, existing.getResumeVersionId(), existing.getJdVersionId(), existing.getRuleVersion());
    }

    public MatchRun owned(UUID userId, UUID id) {
        return store.one("select m from MatchRun m where m.id=:id and m.userId=:uid", MatchRun.class,
                        Map.of("id", id, "uid", userId))
                .orElseThrow(() -> ApiException.notFound("匹配报告"));
    }

    private ItemResult scoreRequirement(JdRequirement requirement, List<Fragment> fragments, String allText, int weight) {
        List<List<String>> groups = ALIASES.values().stream()
                .filter(aliases -> aliases.stream().anyMatch(alias -> SkillEvidence.contains(requirement.getRequirementText(), alias)))
                .toList();
        if (!groups.isEmpty()) {
            List<Fragment> hits = new ArrayList<>();
            for (List<String> aliases : groups) {
                fragments.stream().filter(f -> aliases.stream().anyMatch(alias -> SkillEvidence.positive(f.text(),alias)))
                        .findFirst().ifPresent(hits::add);
            }
            if (hits.isEmpty()) return new ItemResult("MISSING",0,"","当前版本未提供正向证据","规则词边界与否定判断",.9);
            boolean complete=hits.size()==groups.size();
            boolean depthMissing=List.of("一致性","索引优化","事务","并发","重试","幂等").stream()
                    .anyMatch(term -> requirement.getRequirementText().contains(term) &&
                            hits.stream().noneMatch(f -> SkillEvidence.positive(f.text(),term)));
            if (depthMissing) complete=false;
            int score=complete ? weight : Math.max(1,(int)Math.floor(weight*Math.min(.75,hits.size()/(double)groups.size())));
            return new ItemResult(complete ? "EXACT":"PARTIAL",score,
                    hits.stream().map(Fragment::text).distinct().reduce((a,b)->a+"；"+b).orElse(""),
                    hits.stream().map(Fragment::path).distinct().reduce((a,b)->a+";"+b).orElse(""),
                    complete ? "规则技能匹配" : "复合要求或深度证据仅部分覆盖",complete ?.95:.7);
        }
        List<String> rawCandidates = new ArrayList<>();
        rawCandidates.addAll(jsons.list(requirement.getAliasesText()).stream().map(String::valueOf).toList());
        String normalizedReq = normalize(requirement.getRequirementText());
        ALIASES.forEach((key, aliases) -> {
            if (aliases.stream().anyMatch(normalizedReq::contains)) rawCandidates.addAll(aliases);
        });
        tokens(normalizedReq).stream().filter(token -> token.length() >= 2).forEach(rawCandidates::add);
        List<String> candidates = rawCandidates.stream().map(MatchingService::normalize)
                .filter(value -> !value.isBlank()).distinct().toList();

        Optional<String> exact = candidates.stream().filter(candidate -> fragments.stream().anyMatch(f -> SkillEvidence.positive(f.text(),candidate))).findFirst();
        if (exact.isPresent()) {
            Fragment evidence = fragments.stream().filter(fragment -> SkillEvidence.positive(fragment.text(),exact.get()))
                    .findFirst().orElse(new Fragment("resume", allText));
            boolean alias = !normalizedReq.contains(exact.get());
            return new ItemResult(alias ? "ALIAS" : "EXACT", weight, evidence.text(), evidence.path(),
                    alias ? "技能别名词典" : "精确词匹配", alias ? 0.95 : 0.99);
        }

        Set<String> reqTokens = tokens(normalizedReq);
        Fragment best = null;
        double bestOverlap = 0;
        for (Fragment fragment : fragments) {
            if (SkillEvidence.negated(fragment.text())) continue;
            Set<String> fragmentTokens = tokens(normalize(fragment.text()));
            if (reqTokens.isEmpty()) continue;
            long common = reqTokens.stream().filter(fragmentTokens::contains).count();
            double overlap = common / (double) reqTokens.size();
            if (overlap > bestOverlap) { bestOverlap = overlap; best = fragment; }
        }
        if (best != null && bestOverlap >= 0.25) {
            int partial = Math.max(1, (int) Math.round(weight * Math.min(.75, .35 + bestOverlap / 2)));
            return new ItemResult("PARTIAL", partial, best.text(), best.path(),
                    "规则词项重叠", Math.min(.9, .55 + bestOverlap / 2));
        }
        return new ItemResult("MISSING", 0, "", "当前简历未找到可信证据", "词典 + 全文检索", .97);
    }

    private List<Fragment> flatten(JsonNode root) {
        return com.careerlens.core.resume.ResumeFacts.collect(root).stream()
                .map(f -> new Fragment(f.path(), f.text())).toList();
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : NON_WORD.split(text)) {
            if (token.isBlank() || STOP_WORDS.contains(token)) continue;
            result.add(token);
            if (token.matches(".*[一-龥].*") && token.length() > 4) {
                for (int i = 0; i <= token.length() - 2; i++) result.add(token.substring(i, i + 2));
            }
        }
        return result;
    }

    private static String normalize(String value) {
        return NON_WORD.matcher(Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private static int weight(String importance) { return switch (importance) { case "MUST" -> 9; case "IMPORTANT" -> 6; default -> 3; }; }
    private static String importanceLabel(String importance) { return switch (importance) { case "MUST" -> "必须"; case "BONUS" -> "加分"; default -> "重要"; }; }
    private static String key(String label) { return Integer.toUnsignedString(label.hashCode(), 36); }
    private static String summary(int score, List<ItemView> items) {
        long missing = items.stream().filter(item -> "MISSING".equals(item.verdict())).count();
        return score >= 80 ? "核心要求匹配良好，建议补强 " + missing + " 项缺失证据后投递。"
                : score >= 65 ? "主要技术方向一致，仍有 " + missing + " 项岗位要求缺少直接证据。"
                : "当前版本与岗位存在明显缺口，建议先补充真实项目证据。";
    }

    private ItemView itemView(MatchItem item) {
        return new ItemView(item.getId(), item.getRequirementText(), item.getCategory(), importanceLabel(item.getImportance()),
                item.getVerdict(), item.getScore().doubleValue(), item.getMaxScore().doubleValue(), item.getJdEvidence(),
                item.getResumeEvidence(), item.getResumeSource(), item.getMatchMethod(), item.getConfidence().doubleValue());
    }

    private record Fragment(String path, String text) {}
    private record ItemResult(String verdict, int score, String evidence, String source, String method, double confidence) {}
    private static class ScoreBucket {
        double score; double max;
        void add(double value, double maximum) { score += value; max += maximum; }
        int percent() { return max == 0 ? 0 : (int) Math.round(score * 100 / max); }
    }

    public record ItemView(UUID id, String requirement, String category, String importance, String verdict,
                           double score, double maxScore, String jdEvidence, String resumeEvidence,
                           String resumeSource, String method, double confidence) {}
    public record MatchView(UUID id, String company, String role, UUID resumeVersionId, int resumeVersion,
                            UUID jobDescriptionVersionId, int jdVersion, String scoringRuleVersion, int score,
                            String conclusion, String summary, int mustMatched, int mustTotal, int evidenceCoverage,
                            List<Object> breakdown, List<ItemView> items, java.time.Instant createdAt) {}
}
