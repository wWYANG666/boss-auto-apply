package com.careerlens.core.job;

import com.careerlens.core.common.ApiException;
import com.careerlens.core.common.Jsons;
import com.careerlens.core.domain.DataStore;
import com.careerlens.core.domain.Entities.JobDescription;
import com.careerlens.core.domain.Entities.JobDescriptionVersion;
import com.careerlens.core.domain.Entities.JdRequirement;
import com.careerlens.core.integration.AiWorkerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="careerlens.legacy-resume-optimization.enabled",havingValue="true")
@RequiredArgsConstructor
public class JobDescriptionService {
    private static final Pattern SPLIT = Pattern.compile("[\\r\\n；;。]+");
    private static final LinkedHashMap<String, List<String>> SKILLS = new LinkedHashMap<>();
    static {
        SKILLS.put("Java", List.of("java", "jdk", "jvm"));
        SKILLS.put("Spring Boot", List.of("spring boot", "springboot", "spring-boot"));
        SKILLS.put("MySQL", List.of("mysql", "sql", "数据库"));
        SKILLS.put("Redis", List.of("redis", "缓存"));
        SKILLS.put("RabbitMQ / RocketMQ", List.of("rabbitmq", "rocketmq", "消息队列", "mq"));
        SKILLS.put("Linux", List.of("linux", "ubuntu", "centos"));
        SKILLS.put("Docker", List.of("docker", "容器"));
        SKILLS.put("自动化测试", List.of("junit", "单元测试", "自动化测试", "testcontainers"));
        SKILLS.put("微服务", List.of("微服务", "spring cloud", "dubbo"));
        SKILLS.put("Kubernetes", List.of("kubernetes", "k8s"));
        SKILLS.put("Go", List.of("golang", "go语言"));
        SKILLS.put("C++", List.of("c++", "cpp"));
    }

    private final DataStore store;
    private final Jsons jsons;
    private final AiWorkerClient aiWorker;

    @Transactional
    public JobView create(UUID userId, String company, String role, String location, String salary, String rawText) {
        JobDescription jd = new JobDescription();
        jd.setUserId(userId);
        jd.setCompany(company.trim());
        jd.setRoleName(role.trim());
        jd.setLocation(location);
        jd.setSalary(salary);
        store.persist(jd);
        store.flush();
        publishInternal(jd, rawText);
        return view(jd);
    }

    @Transactional(readOnly = true)
    public List<JobView> list(UUID userId) {
        return store.query("select j from JobDescription j where j.userId=:uid order by j.updatedAt desc",
                JobDescription.class, Map.of("uid", userId)).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public JobDetail get(UUID userId, UUID id) {
        JobDescription jd = owned(userId, id);
        JobDescriptionVersion version = jd.getCurrentVersionId() == null ? null : version(userId, jd.getCurrentVersionId());
        return new JobDetail(view(jd), version == null ? null : versionView(version),
                version == null ? List.of() : requirements(userId, version.getId()));
    }

    @Transactional
    public VersionView publish(UUID userId, UUID id, String rawText) {
        return versionView(publishInternal(owned(userId, id), rawText));
    }

    @Transactional
    public ExtractionResult extract(UUID userId, UUID jdId, UUID versionId) {
        JobDescription jd = owned(userId, jdId);
        JobDescriptionVersion version = version(userId, versionId);
        if (!version.getJobDescriptionId().equals(jd.getId())) throw ApiException.badRequest("VERSION_MISMATCH", "JD版本不属于该岗位");
        if (store.count("select count(m) from MatchRun m where m.userId=:uid and m.jdVersionId=:vid", Map.of("uid", userId, "vid", versionId)) > 0) {
            List<RequirementView> saved = requirements(userId, versionId);
            return new ExtractionResult(UUID.randomUUID(), "completed", 100, saved.size(), (int)saved.stream().filter(r -> "MUST".equals(r.type())).count(), 0, saved);
        }
        store.update("delete from JdRequirement r where r.jdVersionId=:vid and r.userId=:uid",
                Map.of("vid", versionId, "uid", userId));

        List<Extracted> extracted = fromAi(version.getRawText());
        if (extracted.isEmpty()) extracted = deterministicExtract(version.getRawText());
        int order = 0;
        for (Extracted item : extracted) {
            JdRequirement requirement = new JdRequirement();
            requirement.setUserId(userId);
            requirement.setJdVersionId(versionId);
            requirement.setRequirementText(item.text());
            requirement.setCategory(item.category());
            requirement.setImportance(item.importance());
            requirement.setHardCondition(item.hardCondition());
            requirement.setAliasesText(jsons.write(item.aliases()));
            requirement.setSortOrder(order++);
            int start=item.start()>=0?item.start():version.getRawText().indexOf(item.text());
            requirement.setQuoteText(item.text());requirement.setQuoteStart(start);
            int end=item.end()>=start?item.end():start+item.text().length();
            if(start>=0 && end<=version.getRawText().length())requirement.setQuoteText(version.getRawText().substring(start,end));
            requirement.setQuoteEnd(start<0?-1:end);requirement.setExtractionConfidence(item.confidence());
            requirement.setConfirmed(false);
            store.persist(requirement);
        }
        List<RequirementView> results = requirements(userId, versionId);
        long must = results.stream().filter(item -> "MUST".equals(item.type())).count();
        return new ExtractionResult(UUID.randomUUID(), "completed", 100, results.size(), (int) must, (int)results.stream().filter(r->!r.confirmed()).count(), results);
    }

    @Transactional(readOnly = true)
    public List<RequirementView> requirements(UUID userId, UUID versionId) {
        version(userId, versionId);
        return store.query("select r from JdRequirement r where r.userId=:uid and r.jdVersionId=:vid order by r.sortOrder",
                JdRequirement.class, Map.of("uid", userId, "vid", versionId)).stream().map(this::requirementView).toList();
    }

    @Transactional
    public RequirementView updateRequirement(UUID userId, UUID jdId, UUID versionId, UUID requirementId,
                                             String label, String category, String type, Boolean hardCondition, Boolean confirmed) {
        owned(userId, jdId);
        if(!version(userId,versionId).getJobDescriptionId().equals(jdId))throw ApiException.badRequest("VERSION_MISMATCH","JD版本不属于该岗位");
        if(store.count("select count(m) from MatchRun m where m.userId=:uid and m.jdVersionId=:vid",Map.of("uid",userId,"vid",versionId))>0)
            throw ApiException.conflict("REQUIREMENTS_FROZEN","该要求集已用于报告，请创建新JD版本再修改");
        JdRequirement requirement = store.one(
                "select r from JdRequirement r where r.id=:id and r.userId=:uid and r.jdVersionId=:vid",
                JdRequirement.class, Map.of("id", requirementId, "uid", userId, "vid", versionId))
                .orElseThrow(() -> ApiException.notFound("岗位要求"));
        if (confirmed != null) requirement.setConfirmed(confirmed);
        if (label != null && !label.isBlank()) requirement.setRequirementText(label.trim());
        if (category != null && !category.isBlank()) requirement.setCategory(category.trim());
        if (type != null) requirement.setImportance(normalizeImportance(type));
        if (hardCondition != null) requirement.setHardCondition(hardCondition);
        return requirementView(requirement);
    }

    public JobDescription owned(UUID userId, UUID id) {
        return store.one("select j from JobDescription j where j.id=:id and j.userId=:uid", JobDescription.class,
                        Map.of("id", id, "uid", userId))
                .orElseThrow(() -> ApiException.notFound("岗位描述"));
    }

    public JobDescriptionVersion version(UUID userId, UUID versionId) {
        return store.one("select v from JobDescriptionVersion v where v.id=:id and v.userId=:uid",
                        JobDescriptionVersion.class, Map.of("id", versionId, "uid", userId))
                .orElseThrow(() -> ApiException.notFound("岗位描述版本"));
    }

    private JobDescriptionVersion publishInternal(JobDescription jd, String rawText) {
        if (rawText == null || rawText.isBlank()) throw ApiException.badRequest("EMPTY_JOB_DESCRIPTION", "岗位描述不能为空");
        JobDescriptionVersion version = new JobDescriptionVersion();
        version.setUserId(jd.getUserId());
        version.setJobDescriptionId(jd.getId());
        version.setVersionNumber(jd.getCurrentVersionNumber() + 1);
        version.setRawText(rawText.trim());
        version.setStructuredText("{}");
        store.persist(version);
        store.flush();
        jd.setCurrentVersionId(version.getId());
        jd.setCurrentVersionNumber(version.getVersionNumber());
        return version;
    }

    private List<Extracted> fromAi(String rawText) {
        try {
            return aiWorker.extractRequirements(rawText).stream().map(item -> {
                String text = string(item, "text", string(item, "requirement", ""));
                String category = string(item, "category", "岗位要求");
                String importance = normalizeImportance(string(item, "importance", "IMPORTANT"));
                boolean hard = bool(item.get("hardCondition")) || bool(item.get("hard_condition"));
                List<String> aliases = list(item.get("aliases"));
                return new Extracted(text, category, importance, hard, aliases,
                        item.get("charStart") instanceof Number start?start.intValue():-1,
                        item.get("charEnd") instanceof Number end?end.intValue():-1,
                        item.get("confidence") instanceof Number confidence?confidence.doubleValue():0);
            }).filter(item -> !item.text().isBlank()).toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<Extracted> deterministicExtract(String rawText) {
        String lower = rawText.toLowerCase(Locale.ROOT);
        List<Extracted> result = new ArrayList<>();
        for (var entry : SKILLS.entrySet()) {
            Optional<String> hit = entry.getValue().stream().filter(lower::contains).findFirst();
            if (hit.isEmpty()) continue;
            String sentence = Arrays.stream(SPLIT.split(rawText)).filter(s -> s.toLowerCase(Locale.ROOT).contains(hit.get()))
                    .findFirst().map(String::trim).orElse(entry.getKey());
            boolean must = containsAny(sentence, "必须", "要求", "熟悉", "掌握", "精通");
            boolean bonus = containsAny(sentence, "优先", "加分", "更佳");
            result.add(new Extracted(sentence, category(entry.getKey()), bonus ? "BONUS" : must ? "MUST" : "IMPORTANT",
                    must && containsAny(sentence, "必须", "要求"), entry.getValue()));
        }
        if (result.isEmpty()) {
            Arrays.stream(SPLIT.split(rawText)).map(String::trim).filter(line -> line.length() >= 4).limit(8)
                    .forEach(line -> result.add(new Extracted(line, "岗位要求",
                            containsAny(line, "必须", "要求") ? "MUST" : "IMPORTANT",
                            containsAny(line, "必须"), List.of())));
        }
        return result;
    }

    private String category(String skill) {
        if (Set.of("MySQL").contains(skill)) return "数据库";
        if (Set.of("Redis", "RabbitMQ / RocketMQ").contains(skill)) return "中间件";
        if (Set.of("Docker", "Kubernetes", "Linux", "自动化测试").contains(skill)) return "工程基础";
        if (Set.of("Spring Boot", "微服务").contains(skill)) return "后端框架";
        return "编程语言";
    }

    private static String normalizeImportance(String value) {
        String upper = value == null ? "IMPORTANT" : value.toUpperCase(Locale.ROOT);
        if (upper.contains("MUST") || upper.contains("必须")) return "MUST";
        if (upper.contains("BONUS") || upper.contains("加分") || upper.contains("PREFERRED")) return "BONUS";
        return "IMPORTANT";
    }

    private static boolean containsAny(String text, String... needles) {
        return Arrays.stream(needles).anyMatch(text::contains);
    }
    private static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key); return value == null ? fallback : String.valueOf(value);
    }
    private static boolean bool(Object value) { return value instanceof Boolean b && b; }
    private static List<String> list(Object value) {
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return List.of();
    }

    private JobView view(JobDescription jd) {
        return new JobView(jd.getId(), jd.getCompany(), jd.getRoleName(), jd.getLocation(), jd.getSalary(),
                jd.getCurrentVersionId(), jd.getCurrentVersionNumber(), jd.getCreatedAt(), jd.getUpdatedAt());
    }
    private VersionView versionView(JobDescriptionVersion version) {
        return new VersionView(version.getId(), version.getJobDescriptionId(), version.getVersionNumber(),
                version.getRawText(), version.getCreatedAt());
    }
    private RequirementView requirementView(JdRequirement requirement) {
        int weight = switch (requirement.getImportance()) { case "MUST" -> 9; case "IMPORTANT" -> 6; default -> 3; };
        return new RequirementView(requirement.getId(), requirement.getRequirementText(), requirement.getCategory(),
                requirement.getImportance(), weight, requirement.isConfirmed(), requirement.getQuoteText(), requirement.getQuoteStart(), requirement.getQuoteEnd(), requirement.getExtractionConfidence(),
                requirement.isHardCondition(), list(jsons.list(requirement.getAliasesText())));
    }
    private static List<String> list(List<Object> value) { return value.stream().map(String::valueOf).toList(); }

    private record Extracted(String text, String category, String importance, boolean hardCondition, List<String> aliases,int start,int end,double confidence) {
        Extracted(String text,String category,String importance,boolean hardCondition,List<String> aliases){
            this(text,category,importance,hardCondition,aliases,-1,-1,0);
        }
    }
    public record JobView(UUID id, String company, String role, String location, String salary,
                          UUID currentVersionId, int currentVersion, java.time.Instant createdAt, java.time.Instant updatedAt) {}
    public record VersionView(UUID id, UUID jobDescriptionId, int version, String rawText, java.time.Instant createdAt) {}
    public record RequirementView(UUID id, String label, String category, String type, int weight, boolean confirmed,
                                  String quote, int quoteStart, int quoteEnd, double confidence, boolean hardCondition,
                                  List<String> aliases) {}
    public record JobDetail(JobView job, VersionView currentVersion, List<RequirementView> requirements) {}
    public record ExtractionResult(UUID taskId, String status, int progress, int requirementCount,
                                   int mustCount, int pendingCount, List<RequirementView> requirements) {}
}
