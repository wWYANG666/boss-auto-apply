package com.careerlens.core.automation;

import com.careerlens.core.application.ApplicationService;
import com.careerlens.core.common.ApiException;
import com.careerlens.core.common.Hashing;
import com.careerlens.core.common.Jsons;
import com.careerlens.core.domain.DataStore;
import com.careerlens.core.domain.Entities.*;
import com.careerlens.core.integration.RunnerClient;
import com.careerlens.core.resume.ResumeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AutomationService {
    private static final Set<String> PLATFORMS = Set.of("boss", "liepin");
    private static final Set<String> TASK_STATUSES = Set.of("queued", "preparing", "submitting", "verifying", "succeeded",
            "awaiting_login", "awaiting_captcha", "awaiting_question", "page_changed", "unknown_outcome", "failed", "cancelled", "dry_run");
    private static final Set<String> RUNNER_SCENARIOS = Set.of("happy_path", "login_required", "captcha_required",
            "page_changed", "unknown_after_submit");

    private final DataStore store;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final Jsons jsons;
    private final ResumeService resumes;
    private final RunnerClient runner;
    private final PlatformIdentityService identities;
    private final GreetingService greetings;
    private final ApplicationService applications;

    @Transactional(readOnly = true)
    public List<PlatformView> platforms(UUID userId) {
        return store.query("select p from PlatformAccount p where p.userId=:uid order by p.platform",
                PlatformAccount.class, Map.of("uid", userId)).stream().map(this::platformView).toList();
    }

    @Transactional
    public PlatformView connect(UUID userId, String platform) {
        String normalizedPlatform = normalizePlatform(platform);
        requireLiveRunner();
        Map<String, Object> connection = runner.data(runner.connect(normalizedPlatform));
        if("boss".equals(normalizedPlatform))identities.syncConnection(userId,connection);
        if (!Boolean.TRUE.equals(connection.get("connected"))) {
            String message = Objects.toString(connection.getOrDefault("message", "平台尚未登录，请在本地执行器中完成连接"));
            throw ApiException.conflict("PLATFORM_LOGIN_REQUIRED", message);
        }
        Instant checkedAt = Instant.now();
        String capabilitiesText = jsons.write(capabilities(normalizedPlatform));
        PlatformAccount account = store.one("select p from PlatformAccount p where p.userId=:uid and p.platform=:platform",
                PlatformAccount.class, Map.of("uid", userId, "platform", normalizedPlatform)).orElseGet(() -> {
            PlatformAccount created = new PlatformAccount();
            created.setUserId(userId);
            created.setPlatform(normalizedPlatform);
            created.setDisplayName("boss".equals(normalizedPlatform) ? "BOSS直聘" : "猎聘");
            created.setConnectionType("boss".equals(normalizedPlatform) ? "LOCAL_BROWSER" : "MCP");
            created.setConnectionStatus("connected");
            created.setMaskedIdentity("待本地授权");
            created.setCapabilitiesText(capabilitiesText);
            created.setAdapterVersion("0.1.0");
            created.setLastCheckedAt(checkedAt);
            store.persist(created);
            return created;
        });
        account.setConnectionStatus("connected");
        account.setCapabilitiesText(capabilitiesText);
        account.setLastCheckedAt(checkedAt);
        return platformView(account);
    }

    @Transactional
    public PlatformView disconnect(UUID userId, UUID id) {
        PlatformAccount account = ownedPlatform(userId, id);
        runner.disconnect(account.getPlatform());
        if("boss".equals(account.getPlatform()))identities.disconnectActive(userId);
        account.setConnectionStatus("disconnected");
        account.setLastCheckedAt(Instant.now());
        return platformView(account);
    }

    @Transactional
    public DiscoveryView enqueueDiscovery(UUID userId,List<UUID> accountIds,UUID resumeVersionId,Map<String,Object> spec){
        return enqueueDiscovery(userId,accountIds,resumeVersionId,spec,"manual");
    }

    @Transactional
    public DiscoveryView enqueueDiscovery(UUID userId,List<UUID> accountIds,UUID resumeVersionId,Map<String,Object> spec,String source){
        requireLiveRunner();resumes.version(userId,resumeVersionId);
        spec=normalizeDiscoverySpec(spec);
        List<PlatformAccount> accounts=accountIds==null||accountIds.isEmpty()
            ? store.query("select p from PlatformAccount p where p.userId=:uid and p.connectionStatus='connected'",PlatformAccount.class,Map.of("uid",userId))
            : accountIds.stream().map(id->ownedPlatform(userId,id)).toList();
        if(accounts.isEmpty())throw ApiException.badRequest("NO_CONNECTED_PLATFORM","请先连接平台");
        requireDiscoveryAllowed(userId, accounts);
        if(accounts.stream().anyMatch(account->"boss".equals(account.getPlatform()))){
            spec.put("eligibleTarget",discoveryTarget(dailyQuota(userId,identities.activeIdOrNull(userId)).remaining()));
            spec.put("bypassCache",true);
        }
        DiscoveryRun run=new DiscoveryRun();run.setUserId(userId);run.setPlatformsText(jsons.write(accounts.stream().map(PlatformAccount::getPlatform).toList()));
        if(accounts.stream().anyMatch(account->"boss".equals(account.getPlatform())))run.setPlatformIdentityId(identities.activeId(userId));
        run.setSpecText(jsons.write(spec));run.setStatus("queued");run.setProgress(0);store.persist(run);store.flush();
        jdbc.update("UPDATE discovery_run SET source=? WHERE id=?",source,run.getId());
        for(var account:accounts){
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO discovery_command(id,run_id,user_id,resume_version_id,platform,payload) VALUES (?,?,?,?,?,?)",
              id,run.getId(),userId,resumeVersionId,account.getPlatform(),
              jsons.write(Map.of("commandId",id.toString(),"platform",account.getPlatform(),"scenario","happy_path","spec",spec)));
        }
        return discoveryView(run);
    }

    static int discoveryTarget(int remaining){return Math.max(0,Math.min(150,remaining))+20;}

    @Transactional
    @SuppressWarnings("unchecked")
    public void continueDiscovery(UUID userId,UUID runId,UUID commandId,Map<String,Object> metadata){
        DiscoveryRun run=ownedDiscovery(userId,runId);
        Map<String,Object> previous=jdbc.queryForMap("SELECT * FROM discovery_command WHERE id=? AND run_id=?",commandId,runId);
        Map<String,Object> payload=new LinkedHashMap<>(jsons.map(previous.get("payload").toString()));
        Map<String,Object> spec=new LinkedHashMap<>(objectMap(payload.get("spec")));
        int target=integer(spec.get("eligibleTarget"),0);
        if(target==0||!"boss".equals(previous.get("platform")))return;
        int eligible=discoveryFilterStats(userId,runId).eligible();
        List<?> pending=metadata.get("pendingScopes") instanceof List<?> scopes?scopes:List.of();
        String stopReason=eligible>=target?"target_reached":pending.isEmpty()?"scopes_exhausted":"";
        Map<String,Object> stats=new LinkedHashMap<>(jsons.map(run.getFilterStatsText()));
        stats.put("eligibleCount",eligible);stats.put("eligibleTarget",target);stats.put("searchStopReason",stopReason);
        run.setFilterStatsText(jsons.write(stats));
        if(!stopReason.isEmpty())return;
        spec.put("resumeScopes",pending);spec.put("bypassCache",true);
        spec.put("resumePagesCompleted",integer(metadata.get("pagesCompleted"),0));
        spec.put("resumeDetectedCount",integer(metadata.get("detectedCount"),0));
        spec.put("resumePageLimit",integer(metadata.get("pageLimit"),30));
        // Candidate counts are diagnostic only; eligible jobs decide when to stop.
        spec.put("resumeCandidateLimit",1000);
        UUID nextId=UUID.randomUUID();payload.put("commandId",nextId.toString());payload.put("spec",spec);
        jdbc.update("INSERT INTO discovery_command(id,run_id,user_id,resume_version_id,platform,payload,next_attempt_at) VALUES (?,?,?,?,?,?,?)",
                nextId,runId,userId,previous.get("resume_version_id"),previous.get("platform"),jsons.write(payload),
                java.sql.Timestamp.from(Instant.now().plusSeconds(3)));
        run.setStatus("running");
        run.setProgress(Math.min(95,integer(metadata.get("pagesCompleted"),0)*95/Math.max(1,integer(metadata.get("pageLimit"),30))));
    }
    @Transactional
    public void attachDiscoveryJobs(UUID userId,UUID runId,UUID resumeId,String platform,List<Map<String,Object>> rawJobs){
        DiscoveryRun run=ownedDiscovery(userId,runId);ResumeVersion resume=resumes.version(userId,resumeId);
        for(var raw:rawJobs){
            String external=string(raw,"externalJobId","");
            if(external.isBlank())continue;
            Map<String,Object> jobKey=new HashMap<>();jobKey.put("uid",userId);jobKey.put("platform",platform);jobKey.put("identity",run.getPlatformIdentityId());jobKey.put("external",external);
            var existing=store.one("select j from DiscoveredJob j where j.userId=:uid and j.platform=:platform and (j.platformIdentityId=:identity or j.platformIdentityId is null and :identity is null) and j.externalJobId=:external",DiscoveredJob.class,jobKey);
            DiscoveredJob job=existing.orElseGet(()->toJob(userId,runId,run.getPlatformIdentityId(),resume,platform,external,raw));
            if(existing.isEmpty()){store.persist(job);store.flush();}else{
                run.setDuplicateCount(run.getDuplicateCount()+1);
                String recruiterId=string(raw,"recruiterId","");
                if((job.getRecruiterId()==null||job.getRecruiterId().isBlank())&&!recruiterId.isBlank())job.setRecruiterId(recruiterId);
            }
            saveObservation(userId,runId,resume,platform,external,raw,job);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM discovery_run_job WHERE discovery_run_id=? AND discovered_job_id=?",Long.class,runId,job.getId())==0)
              jdbc.update("INSERT INTO discovery_run_job(discovery_run_id,discovered_job_id) VALUES (?,?)",runId,job.getId());
        }
        run.setDiscoveredCount(run.getDiscoveredCount()+rawJobs.size());
    }
    @Transactional
    public void attachDiscoveryFilterStats(UUID userId,UUID runId,Map<String,Object> stats){
        DiscoveryRun run=ownedDiscovery(userId,runId);
        Map<String,Object> incoming=stats==null?Map.of():stats;
        Map<String,Object> merged=new LinkedHashMap<>(jsons.map(Objects.toString(run.getFilterStatsText(),"{}")));
        merged.remove("liveProgress");
        boolean first=merged.isEmpty();
        for(String key:List.of("platformReturned","runnerCandidates","runnerFiltered","runnerReturned","inRunDuplicates","keywordMismatch","requiredKeywordMismatch","excludedKeyword","excludedCompany","headhunter","contactedByPlatform","recruiterActivity","salaryMismatch","experienceMismatch","degreeMismatch","runnerEligible"))
            merged.put(key,integer(merged.get(key),0)+integer(incoming.get(key),0));
        merged.put("partial",Boolean.TRUE.equals(merged.get("partial"))||Boolean.TRUE.equals(incoming.get("partial")));
        merged.put("cacheHit",first?Boolean.TRUE.equals(incoming.get("cacheHit")):Boolean.TRUE.equals(merged.get("cacheHit"))&&Boolean.TRUE.equals(incoming.get("cacheHit")));
        List<Object> pages=new ArrayList<>(merged.get("pages") instanceof List<?> existing?existing:List.of());
        if(incoming.get("pages") instanceof List<?> added)pages.addAll(added);merged.put("pages",pages);
        if(incoming.containsKey("interruption"))merged.put("interruption",incoming.get("interruption"));
        if(incoming.containsKey("completedScopes"))merged.put("completedScopes",incoming.get("completedScopes"));
        if(incoming.containsKey("pendingScopes"))merged.put("pendingScopes",incoming.get("pendingScopes"));
        for(String key:List.of("pagesCompleted","pageLimit","detectedCount","candidateLimit","eligibleTarget"))
            if(incoming.containsKey(key))merged.put(key,incoming.get(key));
        run.setFilterStatsText(jsons.write(merged));
        if(first)jdbc.update("DELETE FROM job_filter_decision WHERE run_id=?",runId);
        Object decisions=stats==null?null:stats.get("decisions");
        if(decisions instanceof List<?> rows)for(Object value:rows)if(value instanceof Map<?,?> raw){
            jdbc.update("INSERT INTO job_filter_decision(id,run_id,external_job_id,company,role_name,reason) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID(),runId,Objects.toString(raw.get("externalJobId"),""),Objects.toString(raw.get("company"),""),Objects.toString(raw.get("role"),""),Objects.toString(raw.get("reason"),"unknown"));
        }
    }

    @Transactional
    public void attachDiscoveryProgress(UUID userId,UUID runId,int progress,Map<String,Object> stats){
        DiscoveryRun run=ownedDiscovery(userId,runId);
        Map<String,Object> snapshot=new LinkedHashMap<>(jsons.map(Objects.toString(run.getFilterStatsText(),"{}")));
        Map<String,Object> update=stats==null?Map.of():stats;
        for(String key:List.of("pagesCompleted","pageLimit","detectedCount","candidateLimit","interrupted"))
            if(update.containsKey(key))snapshot.put(key,update.get(key));
        snapshot.put("liveProgress",true);
        run.setFilterStatsText(jsons.write(snapshot));
        run.setStatus("running");
        run.setProgress(Math.max(run.getProgress(),Math.min(95,progress)));
    }

    @Transactional
    public DiscoveryView resumePartialDiscovery(UUID userId,UUID runId,List<Map<String,Object>> pendingScopes,int pagesCompleted,int detectedCount,int pageLimit,int candidateLimit){
        DiscoveryRun run=ownedDiscovery(userId,runId);
        if(!"partial".equals(run.getStatus()))throw ApiException.conflict("DISCOVERY_NOT_PARTIAL","职位发现当前不需要断点恢复");
        Map<String,Object> previous=jdbc.queryForMap("SELECT resume_version_id,platform,payload FROM discovery_command WHERE run_id=? ORDER BY next_attempt_at DESC LIMIT 1",runId);
        UUID commandId=UUID.randomUUID();
        Map<String,Object> payload=new LinkedHashMap<>(jsons.map(previous.get("payload").toString()));
        Map<String,Object> spec=payload.get("spec") instanceof Map<?,?> value
                ?new LinkedHashMap<>((Map<String,Object>)(Map<?,?>)value):new LinkedHashMap<>();
        spec.put("bypassCache",true);spec.put("resumeScopes",pendingScopes);spec.put("resumePagesCompleted",pagesCompleted);
        spec.put("resumeDetectedCount",detectedCount);spec.put("resumePageLimit",pageLimit);spec.put("resumeCandidateLimit",candidateLimit);
        payload.put("commandId",commandId.toString());payload.put("spec",spec);
        jdbc.update("UPDATE discovery_command SET status='DONE' WHERE run_id=? AND status='PARTIAL'",runId);
        jdbc.update("INSERT INTO discovery_command(id,run_id,user_id,resume_version_id,platform,payload) VALUES (?,?,?,?,?,?)",
                commandId,runId,userId,previous.get("resume_version_id"),previous.get("platform"),jsons.write(payload));
        run.setStatus("queued");run.setProgress(Math.min(95,pageLimit>0?(int)Math.round(pagesCompleted*95.0/pageLimit):0));
        return discoveryView(run);
    }

    @Transactional
    public boolean resumePartialDiscovery(UUID userId,UUID runId){
        DiscoveryRun run=ownedDiscovery(userId,runId);
        if(!"partial".equals(run.getStatus()))return false;
        Map<String,Object> stats=jsons.map(Objects.toString(run.getFilterStatsText(),"{}"));
        List<Map<String,Object>> pending=stats.get("pendingScopes") instanceof List<?> list
                ?list.stream().filter(Map.class::isInstance).map(item->(Map<String,Object>)new LinkedHashMap<>((Map<String,Object>)item)).toList():List.of();
        if(pending.isEmpty())return false;
        Map<String,Object> spec=jsons.map(run.getSpecText());
        int pagesCompleted=integer(stats.get("pagesCompleted"),stats.get("pages") instanceof List<?> pages?(int)pages.stream().filter(value->value instanceof Map<?,?> page&&!"interrupted".equals(Objects.toString(page.get("stopReason"),""))).count():0);
        resumePartialDiscovery(userId,runId,pending,pagesCompleted,integer(stats.get("detectedCount"),integer(stats.get("runnerCandidates"),0)),
                integer(stats.get("pageLimit"),integer(spec.get("maxTotalPages"),30)),integer(stats.get("candidateLimit"),150));
        return true;
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> discoveryFilterDecisions(UUID userId,UUID runId,String reason,int limit,int offset){
        ownedDiscovery(userId,runId);String clause=reason==null||reason.isBlank()?"":" AND reason=?";List<Object> args=new ArrayList<>();args.add(runId);if(!clause.isBlank())args.add(reason);args.add(Math.max(1,Math.min(100,limit)));args.add(Math.max(0,offset));
        return jdbc.query("SELECT external_job_id,company,role_name,reason FROM job_filter_decision WHERE run_id=?"+clause+" ORDER BY created_at LIMIT ? OFFSET ?",(rs,n)->Map.<String,Object>of("externalJobId",rs.getString(1),"company",rs.getString(2),"role",rs.getString(3),"reason",rs.getString(4)),args.toArray());
    }

    @Transactional
    public DiscoveryView discover(UUID userId, List<UUID> accountIds, UUID resumeVersionId, Map<String, Object> searchSpec) {
        requireLiveRunner();
        searchSpec=normalizeDiscoverySpec(searchSpec);
        ResumeVersion resume = resumes.version(userId, resumeVersionId);
        List<PlatformAccount> accounts = accountIds == null || accountIds.isEmpty()
                ? store.query("select p from PlatformAccount p where p.userId=:uid and p.connectionStatus='connected'",
                    PlatformAccount.class, Map.of("uid", userId))
                : accountIds.stream().map(id -> ownedPlatform(userId, id)).toList();
        if (accounts.isEmpty()) throw ApiException.badRequest("NO_CONNECTED_PLATFORM", "请先连接至少一个招聘平台");
        requireDiscoveryAllowed(userId, accounts);

        DiscoveryRun run = new DiscoveryRun();
        run.setUserId(userId);
        if(accounts.stream().anyMatch(account->"boss".equals(account.getPlatform())))run.setPlatformIdentityId(identities.activeId(userId));
        run.setPlatformsText(jsons.write(accounts.stream().map(PlatformAccount::getPlatform).toList()));
        run.setSpecText(jsons.write(searchSpec == null ? Map.of() : searchSpec));
        run.setStatus("completed");
        run.setProgress(100);
        store.persist(run);
        store.flush();

        List<Map<String, Object>> rawJobs = runnerJobs(accounts, searchSpec);

        int duplicates = 0;
        for (Map<String, Object> raw : rawJobs) {
            String platform = normalizePlatform(string(raw, "platform", accounts.get(0).getPlatform()));
            String externalId = string(raw, "externalJobId", platform.toUpperCase(Locale.ROOT) + "-" + UUID.randomUUID());
            Map<String,Object> jobKey=new HashMap<>();jobKey.put("uid",userId);jobKey.put("platform",platform);jobKey.put("identity",run.getPlatformIdentityId());jobKey.put("external",externalId);
            Optional<DiscoveredJob> existing = store.one(
                    "select j from DiscoveredJob j where j.userId=:uid and j.platform=:platform and (j.platformIdentityId=:identity or j.platformIdentityId is null and :identity is null) and j.externalJobId=:external",
                    DiscoveredJob.class,jobKey);
            if (existing.isPresent()) {
                duplicates++;
                DiscoveredJob existingJob=existing.get();
                String recruiterId=string(raw,"recruiterId","");
                if((existingJob.getRecruiterId()==null||existingJob.getRecruiterId().isBlank())&&!recruiterId.isBlank())existingJob.setRecruiterId(recruiterId);
                saveObservation(userId,run.getId(),resume,platform,externalId,raw,existingJob);
                jdbc.update("INSERT INTO discovery_run_job(discovery_run_id,discovered_job_id) VALUES (?,?)",run.getId(),existingJob.getId());
                continue;
            }
            DiscoveredJob job = toJob(userId, run.getId(), run.getPlatformIdentityId(), resume, platform, externalId, raw);
            store.persist(job);store.flush();
            saveObservation(userId,run.getId(),resume,platform,externalId,raw,job);
            jdbc.update("INSERT INTO discovery_run_job(discovery_run_id,discovered_job_id) VALUES (?,?)",run.getId(),job.getId());
        }
        run.setDiscoveredCount(rawJobs.size());
        run.setDuplicateCount(duplicates);
        return discoveryView(run);
    }

    @Transactional(readOnly = true)
    public DiscoveryView discovery(UUID userId, UUID id) {
        return discoveryView(ownedDiscovery(userId, id));
    }

    @Transactional(readOnly=true)
    public DiscoveryView currentManualDiscovery(UUID userId){
        UUID identity=identities.activeIdOrNull(userId);
        return jdbc.query("SELECT id FROM discovery_run WHERE user_id=? AND source='manual' AND status IN ('queued','running','searching','partial') AND archived_at IS NULL AND (platform_identity_id=? OR platform_identity_id IS NULL AND ? IS NULL) ORDER BY created_at DESC LIMIT 1",
                (rs,n)->discovery(userId,rs.getObject(1,UUID.class)),userId,identity,identity).stream().findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<JobView> jobs(UUID userId, UUID runId) {
        return jobs(userId,runId,1000,0);
    }

    @Transactional(readOnly = true)
    public List<JobView> jobs(UUID userId, UUID runId,int limit,int offset) {
        DiscoveryRun run=ownedDiscovery(userId, runId);
        Map<String,Object> spec=jsons.map(run.getSpecText());
        int companyDays=integer(spec.get("sameCompanyDays"),0),recruiterDays=integer(spec.get("sameRecruiterDays"),0);
        return jdbc.query("SELECT discovered_job_id FROM discovery_run_job WHERE discovery_run_id=?", (rs,n)->rs.getObject(1,UUID.class),runId).stream()
                .map(id->observedJob(userId,id,runId,null))
                .filter(job->!recentCompanyApplication(userId,run.getPlatformIdentityId(),job.getCompany(),companyDays))
                .filter(job->!recentRecruiterApplication(userId,run.getPlatformIdentityId(),job,recruiterDays))
                .sorted(Comparator.comparingInt(DiscoveredJob::getMatchScore).reversed())
                .skip(Math.max(0,offset)).limit(Math.max(1,Math.min(200,limit)))
                .map(this::jobView).toList();
    }

    @Transactional(readOnly=true)
    public long jobCount(UUID userId,UUID runId){ownedDiscovery(userId,runId);Long value=jdbc.queryForObject("SELECT COUNT(*) FROM discovery_run_job WHERE discovery_run_id=?",Long.class,runId);return value==null?0:value;}

    @Transactional(readOnly=true)
    public DiscoveryFilterStats discoveryFilterStats(UUID userId,UUID runId){
        DiscoveryRun run=ownedDiscovery(userId,runId);
        Map<String,Object> spec=jsons.map(run.getSpecText());
        int companyDays=integer(spec.get("sameCompanyDays"),0),recruiterDays=integer(spec.get("sameRecruiterDays"),0);
        int tracked=0,contacted=0,hardConflict=0,score=0,companyCooldown=0,recruiterCooldown=0,eligible=0;
        int minimum=integer(spec.get("minimumScore"),0);
        List<UUID> ids=jdbc.query("SELECT discovered_job_id FROM discovery_run_job WHERE discovery_run_id=?",(rs,n)->rs.getObject(1,UUID.class),runId);
        for(UUID id:ids){
            DiscoveredJob job=observedJob(userId,id,runId,null);
            if(job.isAlreadyTracked()){tracked++;continue;}
            if(job.isContacted()){contacted++;continue;}
            if(!strings(job.getHardConflictsText()).isEmpty()){hardConflict++;continue;}
            if(job.getMatchScore()<minimum){score++;continue;}
            if(recentCompanyApplication(userId,run.getPlatformIdentityId(),job.getCompany(),companyDays)){companyCooldown++;continue;}
            if(recentRecruiterApplication(userId,run.getPlatformIdentityId(),job,recruiterDays)){recruiterCooldown++;continue;}
            eligible++;
        }
        Map<String,Object> runnerStats=jsons.map(Objects.toString(run.getFilterStatsText(),"{}"));
        return new DiscoveryFilterStats(integer(runnerStats.get("platformReturned"),run.getDiscoveredCount()),
                integer(runnerStats.get("runnerCandidates"),run.getDiscoveredCount()),integer(runnerStats.get("runnerFiltered"),0),
                integer(runnerStats.get("runnerReturned"),run.getDiscoveredCount()),integer(runnerStats.get("inRunDuplicates"),0),
                run.getDuplicateCount(),ids.size(),tracked,contacted,
                hardConflict,score,companyCooldown,recruiterCooldown,eligible,
                integer(runnerStats.get("keywordMismatch"),0),integer(runnerStats.get("requiredKeywordMismatch"),0),
                integer(runnerStats.get("excludedKeyword"),0),integer(runnerStats.get("excludedCompany"),0),
                integer(runnerStats.get("headhunter"),0),integer(runnerStats.get("contactedByPlatform"),0),
                integer(runnerStats.get("recruiterActivity"),0),integer(runnerStats.get("salaryMismatch"),0),
                integer(runnerStats.get("experienceMismatch"),0),integer(runnerStats.get("degreeMismatch"),0),
                integer(runnerStats.get("eligibleTarget"),0),Objects.toString(runnerStats.get("searchStopReason"),""),
                Boolean.TRUE.equals(runnerStats.get("cacheHit")),Boolean.TRUE.equals(runnerStats.get("partial")),
                runnerStats.get("pages") instanceof List<?> pages?pages:List.of());
    }

    private boolean recentCompanyApplication(UUID userId,UUID identity,String company,int days){
        if(days<=0||company==null||company.isBlank())return false;
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM job_application WHERE user_id=? AND platform_identity_id=? AND LOWER(company)=LOWER(?) AND created_at>=?",Long.class,
                userId,identity,company,java.sql.Timestamp.from(Instant.now().minus(days,ChronoUnit.DAYS)));
        return count!=null&&count>0;
    }
    private boolean recentRecruiterApplication(UUID userId,UUID identity,DiscoveredJob job,int days){
        String recruiter=job.getRecruiter();
        if(days<=0||recruiter==null||recruiter.isBlank()||"未提供".equals(recruiter))return false;
        String recruiterId=job.getRecruiterId();
        Long count;
        if(recruiterId!=null&&!recruiterId.isBlank()){
            count=jdbc.queryForObject("SELECT COUNT(*) FROM job_application a JOIN discovered_job d ON d.user_id=a.user_id AND d.platform_identity_id=a.platform_identity_id AND d.platform=a.platform AND d.external_job_id=a.external_job_id WHERE a.user_id=? AND a.platform_identity_id=? AND d.recruiter_id=? AND a.created_at>=?",Long.class,
                    userId,identity,recruiterId,java.sql.Timestamp.from(Instant.now().minus(days,ChronoUnit.DAYS)));
        }else{
            count=jdbc.queryForObject("SELECT COUNT(*) FROM job_application a JOIN discovered_job d ON d.user_id=a.user_id AND d.platform_identity_id=a.platform_identity_id AND d.platform=a.platform AND d.external_job_id=a.external_job_id WHERE a.user_id=? AND a.platform_identity_id=? AND LOWER(d.company)=LOWER(?) AND d.recruiter=? AND a.created_at>=?",Long.class,
                    userId,identity,job.getCompany(),recruiter,java.sql.Timestamp.from(Instant.now().minus(days,ChronoUnit.DAYS)));
        }
        return count!=null&&count>0;
    }

    @Transactional
    public List<PlanView> createPlans(UUID userId, UUID discoveryRunId, List<UUID> jobIds, UUID resumeVersionId) {
        ownedDiscovery(userId, discoveryRunId);
        UUID effectiveResumeVersionId=greetings.resolveResumeVersion(userId,resumeVersionId);
        ResumeVersion resume = resumes.version(userId, effectiveResumeVersionId);
        List<PlanView> result = new ArrayList<>();
        for (UUID jobId : jobIds) {
            DiscoveredJob job = observedJob(userId,jobId,discoveryRunId,null);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM discovery_run_job WHERE discovery_run_id=? AND discovered_job_id=?",Long.class,discoveryRunId,jobId)==0) throw ApiException.badRequest("JOB_RUN_MISMATCH", "岗位不属于本次发现批次");
            if (job.isAlreadyTracked()) throw ApiException.conflict("JOB_ALREADY_TRACKED", "岗位已在投递记录中");
            Map<String,Object> planKey=new HashMap<>();planKey.put("uid",userId);planKey.put("platform",job.getPlatform());planKey.put("identity",job.getPlatformIdentityId());planKey.put("external",job.getExternalJobId());planKey.put("action",actionType(job.getPlatform()));
            Optional<ApplicationPlan> existing = store.one(
                    "select p from ApplicationPlan p where p.userId=:uid and p.platform=:platform and (p.platformIdentityId=:identity or p.platformIdentityId is null and :identity is null) and p.externalJobId=:external and p.actionType=:action",
                    ApplicationPlan.class,planKey);
            ApplicationPlan plan = existing.orElseGet(ApplicationPlan::new);
            if ("executed".equals(plan.getApprovalStatus())) throw ApiException.conflict("PLAN_ALREADY_EXECUTED", "岗位已经执行");
            if (existing.isEmpty()) {
                plan.setUserId(userId);
                plan.setPlatformIdentityId(job.getPlatformIdentityId());
                plan.setDiscoveredJobId(jobId);
                plan.setPlatform(job.getPlatform());
                plan.setExternalJobId(job.getExternalJobId());
                plan.setActionType(actionType(job.getPlatform()));
            }
            var observationIds=jdbc.query("SELECT id FROM job_observation WHERE user_id=? AND run_id=? AND job_id=?",(rs,n)->rs.getObject(1,UUID.class),userId,discoveryRunId,jobId);
            plan.setObservationId(observationIds.stream().findFirst().orElse(null));
            plan.setResumeVersionId(effectiveResumeVersionId);
            plan.setIdentityBound(true);
            plan.setResumeVersionNumber(resume.getVersionNumber());
            GreetingService.Generated generated=greetings.generate(userId,job,effectiveResumeVersionId);
            plan.setGreetingText(generated.text());plan.setGreetingStyle(generated.style());plan.setGreetingEvidenceText(jsons.write(generated.evidence()));plan.setGreetingCandidatesText(jsons.write(generated.candidates()));
            plan.setFormAnswersText("boss".equals(job.getPlatform())
                    ? jsons.write(Map.of())
                    : Objects.toString(job.getPlatformMetadataText(), "{}"));
            plan.setIncluded(true);
            plan.setApprovalStatus("draft");
            plan.setPlanHash(planHash(plan));
            plan.setApprovedHash(null);
            plan.setApprovalTokenHash(null);
            plan.setApprovalExpiresAt(null);
            if (existing.isEmpty()) store.persist(plan);
            result.add(planView(plan, job));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<PlanView> plans(UUID userId, String status) {
        return plans(userId,status,200,null);
    }

    @Transactional(readOnly = true)
    public List<PlanView> plans(UUID userId, String status,int limit,Instant before) {
        boolean actionable="actionable".equalsIgnoreCase(status);
        String statusClause=status==null||status.isBlank()?"":actionable?" and (p.approvalStatus='draft' or p.approvalStatus='approved' and p.approvalExpiresAt<:now)":" and p.approvalStatus=:status";
        String jpql="select p from ApplicationPlan p where p.userId=:uid and p.archivedAt is null and (p.platform<>'boss' or p.platformIdentityId=:identity)"+statusClause+(before==null?"":" and p.createdAt<:before")+" order by p.createdAt desc";
        Map<String,Object> parameters=new HashMap<>();parameters.put("uid",userId);parameters.put("identity",identities.activeIdOrNull(userId));if(actionable)parameters.put("now",Instant.now());else if(status!=null&&!status.isBlank())parameters.put("status",status);if(before!=null)parameters.put("before",before);
        return store.query(jpql,ApplicationPlan.class,parameters,Math.max(1,Math.min(200,limit))).stream()
                .map(plan -> planView(plan, observedJob(userId,plan.getDiscoveredJobId(),null,plan.getObservationId()))).toList();
    }

    @Transactional(readOnly=true)
    public long planCount(UUID userId,String status){
        UUID identity=identities.activeIdOrNull(userId);boolean actionable="actionable".equalsIgnoreCase(status);
        String clause=status==null||status.isBlank()?"":actionable?" AND (approval_status='draft' OR approval_status='approved' AND approval_expires_at<CURRENT_TIMESTAMP)":" AND approval_status=?";
        List<Object> args=new ArrayList<>();args.add(userId);args.add(identity);if(!actionable&&status!=null&&!status.isBlank())args.add(status);
        Long value=jdbc.queryForObject("SELECT COUNT(*) FROM application_plan WHERE user_id=? AND archived_at IS NULL AND (platform<>'boss' OR platform_identity_id=?)"+clause,Long.class,args.toArray());return value==null?0:value;
    }

    @Transactional
    public void deletePlan(UUID userId, UUID id) {
        ApplicationPlan plan = ownedPlan(userId, id);
        if (store.count("select count(t) from AutomationTask t where t.userId=:uid and t.applicationPlanId=:pid",
                Map.of("uid", userId, "pid", id)) > 0)
            throw ApiException.conflict("PLAN_HAS_TASKS", "该审核计划已有执行任务，请先删除对应执行任务");
        store.remove(plan);
    }

    @Transactional
    public int clearPlans(UUID userId) {
        UUID identity=identities.activeIdOrNull(userId);Map<String,Object> parameters=new HashMap<>();parameters.put("uid",userId);parameters.put("identity",identity);
        if (store.count("select count(t) from AutomationTask t where t.userId=:uid and (t.platform<>'boss' or t.platformIdentityId=:identity)",parameters) > 0)
            throw ApiException.conflict("PLANS_HAVE_TASKS", "请先清空自动执行任务，再清空投递审核计划");
        return store.update("delete from ApplicationPlan p where p.userId=:uid and (p.platform<>'boss' or p.platformIdentityId=:identity)",parameters);
    }

    @Transactional
    public PlanView updatePlan(UUID userId, UUID id, Boolean included, String greeting, UUID resumeVersionId, String platformResumeId, String platformResumeHash) {
        ApplicationPlan plan = ownedPlan(userId, id);
        if(store.count("select count(t) from AutomationTask t where t.userId=:uid and t.applicationPlanId=:pid and t.taskStatus not in ('failed','cancelled','succeeded')",Map.of("uid",userId,"pid",id))>0)
            throw ApiException.conflict("PLAN_EXECUTION_LOCKED","计划存在待执行或未知结果任务，请先取消未提交任务或核对结果");
        if ("executed".equals(plan.getApprovalStatus())) throw ApiException.conflict("PLAN_ALREADY_EXECUTED", "已执行计划不可编辑");
        if(platformResumeId!=null){
            var answers=new LinkedHashMap<>(jsons.map(plan.getFormAnswersText()));answers.put("platformResumeId",platformResumeId);answers.put("platformResumeHash",Objects.toString(platformResumeHash,""));plan.setFormAnswersText(jsons.write(answers));
        }
        if (included != null) plan.setIncluded(included);
        if (greeting != null) {
            String checkedGreeting = greeting.trim();
            if ("boss".equals(plan.getPlatform()) && (checkedGreeting.isBlank() || checkedGreeting.length() > 100))
                throw ApiException.badRequest("INVALID_BOSS_GREETING", "BOSS招呼语必须为1至100个字符");
            plan.setGreetingText(checkedGreeting);
            greetings.validateSelected(checkedGreeting,jsons.map(plan.getGreetingEvidenceText()),greetings.policy(userId));
            for(Object value:jsons.list(plan.getGreetingCandidatesText()))if(value instanceof Map<?,?> candidate&&checkedGreeting.equals(Objects.toString(candidate.get("text"),"")))plan.setGreetingStyle(Objects.toString(candidate.get("style"),plan.getGreetingStyle()));
        }
        if (resumeVersionId != null) {
            UUID effective=greetings.resolveResumeVersion(userId,resumeVersionId);ResumeVersion version = resumes.version(userId, effective);
            plan.setResumeVersionId(effective);
            plan.setResumeVersionNumber(version.getVersionNumber());
            GreetingService.Generated generated=greetings.generate(userId,observedJob(userId,plan.getDiscoveredJobId(),null,plan.getObservationId()),effective);
            plan.setGreetingText(generated.text());plan.setGreetingStyle(generated.style());plan.setGreetingEvidenceText(jsons.write(generated.evidence()));plan.setGreetingCandidatesText(jsons.write(generated.candidates()));
        }
        plan.setApprovalStatus(Boolean.FALSE.equals(included) ? "skipped" : "draft");
        plan.setPlanHash(planHash(plan));
        plan.setApprovedHash(null);
        plan.setApprovalTokenHash(null);
        plan.setApprovalExpiresAt(null);
        return planView(plan, observedJob(userId,plan.getDiscoveredJobId(),null,plan.getObservationId()));
    }

    @Transactional
    public PlanView regenerateGreeting(UUID userId, UUID id) {
        ApplicationPlan plan = ownedPlan(userId, id);
        if ("executed".equals(plan.getApprovalStatus()))
            throw ApiException.conflict("PLAN_ALREADY_EXECUTED", "已执行计划不可修改");
        DiscoveredJob job = observedJob(userId, plan.getDiscoveredJobId(), null, plan.getObservationId());
        UUID effective=greetings.resolveResumeVersion(userId,plan.getResumeVersionId());ResumeVersion version=resumes.version(userId,effective);plan.setResumeVersionId(effective);plan.setResumeVersionNumber(version.getVersionNumber());
        GreetingService.Generated generated=greetings.generate(userId,job,effective);
        plan.setGreetingText(generated.text());plan.setGreetingStyle(generated.style());plan.setGreetingEvidenceText(jsons.write(generated.evidence()));plan.setGreetingCandidatesText(jsons.write(generated.candidates()));
        plan.setApprovalStatus("draft");
        plan.setPlanHash(planHash(plan));
        plan.setApprovedHash(null);
        plan.setApprovalTokenHash(null);
        plan.setApprovalExpiresAt(null);
        return planView(plan, job);
    }

    @Transactional
    public GreetingService.Generated generateGreetingCandidates(UUID userId,UUID id){
        ApplicationPlan plan=ownedPlan(userId,id);if("executed".equals(plan.getApprovalStatus()))throw ApiException.conflict("PLAN_ALREADY_EXECUTED","已执行计划不可重新生成");
        UUID effective=greetings.resolveResumeVersion(userId,plan.getResumeVersionId());ResumeVersion version=resumes.version(userId,effective);plan.setResumeVersionId(effective);plan.setResumeVersionNumber(version.getVersionNumber());
        DiscoveredJob job=observedJob(userId,plan.getDiscoveredJobId(),null,plan.getObservationId());GreetingService.Generated generated=greetings.generate(userId,job,effective);
        plan.setGreetingText(generated.text());plan.setGreetingStyle(generated.style());plan.setGreetingEvidenceText(jsons.write(generated.evidence()));plan.setGreetingCandidatesText(jsons.write(generated.candidates()));plan.setApprovalStatus("draft");plan.setPlanHash(planHash(plan));plan.setApprovedHash(null);plan.setApprovalTokenHash(null);plan.setApprovalExpiresAt(null);return generated;
    }

    @Transactional
    public ApprovalResult approve(UUID userId, List<UUID> planIds, boolean acknowledged, boolean batchMode) {
        if (!acknowledged) throw ApiException.badRequest("APPROVAL_ACK_REQUIRED", "请确认已检查岗位、简历和招呼语");
        if (planIds == null || planIds.isEmpty()) throw ApiException.badRequest("EMPTY_APPROVAL", "至少选择一个投递计划");
        List<ApplicationPlan> requestedPlans=planIds.stream().map(id->ownedPlan(userId,id)).toList();
        UUID identity=requestedPlans.stream().filter(plan->"boss".equals(plan.getPlatform())).map(ApplicationPlan::getPlatformIdentityId).filter(Objects::nonNull).findFirst().orElse(identities.activeIdOrNull(userId));
        var policies=identity==null?List.<Map<String,Object>>of():jdbc.query("SELECT daily_limit,dry_run FROM platform_execution_policy WHERE user_id=? AND platform_identity_id=? FOR UPDATE",
                (rs,n)->Map.<String,Object>of("dailyLimit",Math.min(150,rs.getInt(1)),"dryRun",rs.getBoolean(2)),userId,identity);
        int dailyLimit=policies.isEmpty()?20:(Integer)policies.get(0).get("dailyLimit");
        boolean dryRun=!policies.isEmpty()&&Boolean.TRUE.equals(policies.get(0).get("dryRun"));
        long today=dailyUsed(userId,identity);
        if(!dryRun&&today+planIds.size()>dailyLimit)throw ApiException.conflict("DAILY_LIMIT_REACHED","当前BOSS账号已达到今日批准数量上限（北京时间日界）");
        if(requestedPlans.stream().anyMatch(plan -> "boss".equals(plan.getPlatform()))) {
            Set<String> companies=new HashSet<>();
            for(ApplicationPlan requested:requestedPlans) {
                DiscoveredJob job=observedJob(userId,requested.getDiscoveredJobId(),null,requested.getObservationId());
                String company=Objects.toString(job.getCompany(),"").trim().toLowerCase(Locale.ROOT).replaceAll("[\\s·•・,，.。()（）【】\\[\\]{}<>《》_-]","");
                if(!companies.add(company)) throw ApiException.badRequest("BOSS_BATCH_DUPLICATE_COMPANY","本批次要求每家公司只保留一个岗位，请取消重复公司的岗位");
            }
            if(companies.size()>(batchMode?20:5)) throw ApiException.badRequest("BOSS_BATCH_COMPANY_LIMIT",batchMode?"一条龙单批最多处理20家公司":"手动批次最多投递5家公司");
        }
        Instant expiresAt = Instant.now().plus(30, ChronoUnit.MINUTES);
        List<TaskView> tasks = new ArrayList<>();
        for (UUID planId : planIds) {
            ApplicationPlan plan = ownedPlan(userId, planId);
            if ("executed".equals(plan.getApprovalStatus())) throw ApiException.conflict("PLAN_ALREADY_EXECUTED", "岗位已经执行，请勿重复批准");
            if (!plan.isIncluded()) continue;
            if ("boss".equals(plan.getPlatform()) && (plan.getGreetingText() == null
                    || plan.getGreetingText().isBlank() || plan.getGreetingText().length() > 100))
                throw ApiException.badRequest("INVALID_BOSS_GREETING", "BOSS招呼语必须为1至100个字符");
            if("boss".equals(plan.getPlatform())){
                Map<String,Object> evidence=jsons.map(plan.getGreetingEvidenceText());
                if(Objects.toString(evidence.get("project"),"").isBlank()){
                    DiscoveredJob evidenceJob=observedJob(userId,plan.getDiscoveredJobId(),null,plan.getObservationId());GreetingService.Generated generated=greetings.generate(userId,evidenceJob,plan.getResumeVersionId());
                    plan.setGreetingText(generated.text());plan.setGreetingStyle(generated.style());plan.setGreetingEvidenceText(jsons.write(generated.evidence()));plan.setGreetingCandidatesText(jsons.write(generated.candidates()));plan.setPlanHash(planHash(plan));evidence=generated.evidence();
                }
                greetings.validateSelected(plan.getGreetingText(),evidence,greetings.policy(userId));
            }
            List<AutomationTask> pending = store.query("select t from AutomationTask t where t.userId=:uid and t.applicationPlanId=:pid", AutomationTask.class, Map.of("uid",userId,"pid",plan.getId()));
            if (pending.stream().anyMatch(t -> Set.of("unknown_outcome","submitting","verifying","preparing","awaiting_login","awaiting_captcha","awaiting_question").contains(t.getTaskStatus()) || t.getRunnerTaskId()!=null && !Set.of("failed","cancelled").contains(t.getTaskStatus())))
                throw ApiException.conflict("PLAN_ALREADY_DISPATCHED", "计划已交付执行器，请先核对原任务");
            pending.stream().filter(t -> "queued".equals(t.getTaskStatus()) && t.getRunnerTaskId()==null).forEach(t -> t.setTaskStatus("cancelled"));
            String rawToken = Hashing.randomToken();
            plan.setApprovedHash(plan.getPlanHash());
            plan.setApprovalTokenHash(Hashing.sha256(rawToken));
            plan.setApprovalExpiresAt(expiresAt);
            plan.setApprovalStatus("approved");
            DiscoveredJob job = observedJob(userId,plan.getDiscoveredJobId(),null,plan.getObservationId());
            AutomationTask task = new AutomationTask();
            task.setUserId(userId);
            task.setPlatformIdentityId(plan.getPlatformIdentityId());
            task.setApplicationPlanId(plan.getId());
            task.setPlatform(plan.getPlatform());
            task.setExternalJobId(plan.getExternalJobId());
            task.setCompany(job.getCompany());
            task.setRoleName(job.getRoleName());
            task.setActionType(plan.getActionType());
            task.setResumeVersionNumber(plan.getResumeVersionNumber());
            task.setTaskStatus("queued");
            task.setProgress(0);
            task.setCurrentStep("已批准，等待本地执行器");
            task.setExecutionSnapshotText(jsons.write(Map.of(
                    "planHash",plan.getPlanHash(),"externalJobId",plan.getExternalJobId(),"company",job.getCompany(),
                    "role",job.getRoleName(),"greetingHash",Hashing.sha256(Objects.toString(plan.getGreetingText(),"")),
                    "observationId",Objects.toString(plan.getObservationId(),""),"approvedAt",Instant.now().toString())));
            store.persist(task);
            store.flush();
            jdbc.update("INSERT INTO automation_outbox(task_id,user_id) VALUES (?,?)",task.getId(),userId);
            audit(userId, task.getId(), "PLAN_APPROVED", "计划哈希与三十分钟确认窗口已锁定");
            tasks.add(taskView(task));
        }
        if (tasks.isEmpty()) throw ApiException.badRequest("NO_INCLUDED_PLANS", "选中的计划均已跳过");
        return new ApprovalResult(UUID.randomUUID(), "ready", tasks.size(), expiresAt, tasks);
    }

    @Transactional(readOnly=true)
    public DailyQuota dailyQuota(UUID userId,UUID identity){
        if(identity==null)return new DailyQuota(20,0,20,150,false);
        var policies=jdbc.query("SELECT daily_limit,dry_run FROM platform_execution_policy WHERE user_id=? AND platform_identity_id=?",
                (rs,n)->Map.<String,Object>of("dailyLimit",Math.min(150,rs.getInt(1)),"dryRun",rs.getBoolean(2)),userId,identity);
        int limit=policies.isEmpty()?20:(Integer)policies.get(0).get("dailyLimit");
        boolean dryRun=!policies.isEmpty()&&Boolean.TRUE.equals(policies.get(0).get("dryRun"));
        int used=(int)Math.min(Integer.MAX_VALUE,dailyUsed(userId,identity));
        return new DailyQuota(limit,used,Math.max(0,Math.min(150,limit)-used),150,dryRun);
    }

    private long dailyUsed(UUID userId,UUID identity){
        if(identity==null)return 0;
        java.time.ZoneId businessZone=java.time.ZoneId.of("Asia/Shanghai");
        Instant businessDayStart=java.time.LocalDate.now(businessZone).atStartOfDay(businessZone).toInstant();
        Long value=jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=? AND platform_identity_id=? AND created_at>=? AND task_status NOT IN ('failed','page_changed','cancelled','dry_run')",Long.class,userId,identity,java.sql.Timestamp.from(businessDayStart));
        return value==null?0:value;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> executionInput(UUID userId,UUID taskId) {
        AutomationTask task=ownedTask(userId,taskId);
        ApplicationPlan plan=ownedPlan(userId,task.getApplicationPlanId());
        validateApproval(plan);
        if(store.count("select count(p) from PlatformAccount p where p.userId=:uid and p.platform=:platform and p.connectionStatus='connected'",Map.of("uid",userId,"platform",plan.getPlatform()))==0)throw ApiException.conflict("PLATFORM_DISCONNECTED","平台连接已关闭");
        return runnerPlan(userId,plan);
    }
    @Transactional
    public TaskView enqueue(UUID userId,UUID id) {
        AutomationTask task=ownedTask(userId,id);
        if(!"queued".equals(task.getTaskStatus()))return taskView(task);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM automation_outbox WHERE task_id=?",Long.class,id)==0)
            jdbc.update("INSERT INTO automation_outbox(task_id,user_id) VALUES (?,?)",id,userId);
        return taskView(task);
    }
    @Transactional
    public void recordExecution(UUID userId,UUID id,Map<String,Object> snapshot) {
        AutomationTask task=ownedTask(userId,id);
        if("cancelled".equals(task.getTaskStatus()))return;
        if(snapshot.get("id")!=null)task.setRunnerTaskId(snapshot.get("id").toString());
        task.setLastSyncedAt(Instant.now());
        String status=Objects.toString(snapshot.get("status"),"running"),type=Objects.toString(snapshot.get("type"),"prepare");
        if("succeeded".equals(status) && "prepare".equals(type)){task.setCurrentStep("预检完成");return;}
        if("succeeded".equals(status)){
            var result=objectMap(snapshot.get("result"));
            if(!result.containsKey("outcome")||!result.containsKey("receiptId")||!Set.of("platform","observation").contains(Objects.toString(result.get("receiptSource"),"")))status="unknown_outcome";
            else {
                task.setReceiptSource(Objects.toString(result.get("receiptSource"),"unverified"));
                task.setPlatformReceiptId(Objects.toString(result.get("platformReceiptId"),null));
                task.setReceipt(result.get("outcome")+":"+result.get("receiptId"));
                ownedPlan(userId,task.getApplicationPlanId()).setApprovalStatus("executed");
                transition(userId,id,"succeeded",100,"首条招呼已送达，已移交手机端",null,task.getReceipt());return;
            }
        }
        if("waiting_human".equals(status)){
            var human=objectMap(snapshot.get("humanAction"));
            String kind=Objects.toString(human.get("kind"),"QUESTION").toLowerCase(Locale.ROOT);
            String description=Objects.toString(human.get("description"),"请检查执行器");
            status="awaiting_"+(Set.of("login","captcha").contains(kind)?kind:"question");
            if(!task.getTaskStatus().equals(status))
                transition(userId,id,status,60,"等待本地人工处理",description,null);
            if(description.contains("BOSS_ACCOUNT_RESTRICTED_32") || description.contains("BOSS_JOB_API_37")
                    || description.contains("BOSS_JOB_REDIRECTED_HOME")){
                if(task.getPlatformIdentityId()!=null)jdbc.update("UPDATE platform_execution_policy SET paused=TRUE WHERE user_id=? AND platform_identity_id=?",userId,task.getPlatformIdentityId());
            }
            return;
        }
        if(Set.of("failed","cancelled","unknown_outcome").contains(status)){
            String detail=Objects.toString(snapshot.get("errorMessage"),status);
            transition(userId,id,status,null,detail,null,null);
            if("failed".equals(status))markPlanFailure(userId,task,Objects.toString(snapshot.get("errorCode"),""),detail);
            return;
        }
        task.setTaskStatus("submit".equals(type)?"submitting":"preparing");
        task.setProgress(integer(snapshot.get("progress"),10));
        task.setCurrentStep("后台执行中");task.setStateVersion(task.getStateVersion()+1);
    }

    @Transactional
    public void recordDryRun(UUID userId,UUID id){
        transition(userId,id,"dry_run",100,"仅预检通过，未向平台发送任何内容",null,"DRY_RUN:PREPARE_VERIFIED");
    }

    @Transactional(readOnly = true)
    public List<TaskView> tasks(UUID userId) {
        return tasks(userId,500);
    }

    @Transactional(readOnly = true)
    public List<TaskView> tasks(UUID userId,int limit) {
        return tasks(userId,limit,null);
    }

    @Transactional(readOnly = true)
    public List<TaskView> tasks(UUID userId,int limit,Instant before) {
        String jpql="select t from AutomationTask t where t.userId=:uid and t.archivedAt is null and (t.platform<>'boss' or t.platformIdentityId=:identity)"+(before==null?"":" and t.createdAt<:before")+" order by t.createdAt desc";
        Map<String,Object> parameters=new HashMap<>();parameters.put("uid",userId);parameters.put("identity",identities.activeIdOrNull(userId));if(before!=null)parameters.put("before",before);
        return store.query(jpql,AutomationTask.class,parameters,Math.max(1,Math.min(1000,limit))).stream().map(this::taskView).toList();
    }

    @Transactional(readOnly=true)
    public long taskCount(UUID userId){UUID identity=identities.activeIdOrNull(userId);Long value=jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=? AND archived_at IS NULL AND (platform<>'boss' OR platform_identity_id=?)",Long.class,userId,identity);return value==null?0:value;}

    @Transactional(readOnly = true)
    public List<TaskView> tasks(UUID userId,Collection<UUID> ids) {
        if(ids.isEmpty())return List.of();
        return store.query("select t from AutomationTask t where t.userId=:uid and t.id in :ids",
                AutomationTask.class,Map.of("uid",userId,"ids",ids)).stream().map(this::taskView).toList();
    }

    @Transactional
    public void deleteTask(UUID userId, UUID id) {
        AutomationTask task = ownedTask(userId, id);
        requireDeletableTask(task);
        deleteTaskRows(userId, id);
    }

    @Transactional
    public int clearTasks(UUID userId) {
        UUID identity=identities.activeIdOrNull(userId);Map<String,Object> parameters=new HashMap<>();parameters.put("uid",userId);parameters.put("identity",identity);
        List<AutomationTask> tasks = store.query("select t from AutomationTask t where t.userId=:uid and (t.platform<>'boss' or t.platformIdentityId=:identity)",AutomationTask.class,parameters);
        tasks.forEach(this::requireDeletableTask);
        for (AutomationTask task : tasks) deleteTaskRows(userId, task.getId());
        return tasks.size();
    }

    private void requireDeletableTask(AutomationTask task) {
        if (!Set.of("failed", "cancelled", "succeeded").contains(task.getTaskStatus()))
            throw ApiException.conflict("TASK_NOT_TERMINAL", "进行中、待处理或结果未知的任务不能直接删除，请先暂停并取消或核对结果");
    }

    private void deleteTaskRows(UUID userId, UUID id) {
        jdbc.update("DELETE FROM automation_outbox WHERE task_id=?", id);
        jdbc.update("DELETE FROM audit_event WHERE user_id=? AND aggregate_type='AUTOMATION_TASK' AND aggregate_id=?", userId, id);
        jdbc.update("DELETE FROM automation_task WHERE id=? AND user_id=?", id, userId);
    }

    @Transactional(readOnly = true)
    public TaskDetail task(UUID userId, UUID id) {
        AutomationTask task = ownedTask(userId, id);
        return new TaskDetail(taskView(task), audits(userId, id));
    }

    @Transactional
    public TaskView start(UUID userId, UUID id, String requestedScenario) {
        requireLiveRunner();
        AutomationTask task = ownedTask(userId, id);
        if ("succeeded".equals(task.getTaskStatus())) return taskView(task);
        if ("cancelled".equals(task.getTaskStatus()))
            throw ApiException.conflict("TASK_CANCELLED", "已取消的任务不能启动");

        ApplicationPlan plan = ownedPlan(userId, task.getApplicationPlanId());
        validateApproval(plan);
        if (task.getRunnerTaskId() != null && !task.getRunnerTaskId().isBlank()) {
            return synchronizeRunnerTask(userId, task, plan);
        }

        String scenario = normalizeRunnerScenario(requestedScenario);
        Map<String, Object> runnerPlan = runnerPlan(userId, plan);
        task.setTaskStatus("preparing");
        task.setProgress(10);
        task.setCurrentStep("本地执行器正在进行页面与表单预检");
        task.setHumanActionText(null);
        task.setStateVersion(task.getStateVersion() + 1);

        String commandId = task.getId() + "-prepare-" + task.getAttempt();
        Map<String, Object> response = runner.prepare(Map.of(
                "commandId", commandId,
                "scenario", scenario,
                "plan", runnerPlan));
        if (runner.unavailable(response)) return runnerUnavailable(userId, task);
        Map<String, Object> accepted = runner.data(response);
        attachRunnerTask(task, accepted, commandId);
        audit(userId, task.getId(), "RUNNER_PREPARE_ACCEPTED", "本地执行器已接收预检命令");
        return handleRunnerTask(userId, task, plan, runnerPlan,
                runner.awaitTask(Objects.toString(accepted.get("id"), "")), scenario);
    }

    @Transactional
    public TaskView sync(UUID userId, UUID id) {
        AutomationTask task = ownedTask(userId, id);
        if (Set.of("succeeded", "cancelled").contains(task.getTaskStatus())) return taskView(task);
        if (task.getRunnerTaskId() == null || task.getRunnerTaskId().isBlank()) return taskView(task);
        ApplicationPlan plan = ownedPlan(userId, task.getApplicationPlanId());
        return synchronizeRunnerTask(userId, task, plan);
    }

    @Transactional
    public TaskView queueReconcile(UUID userId,UUID id){
        AutomationTask task=ownedTask(userId,id);
        if(!"unknown_outcome".equals(task.getTaskStatus()))throw ApiException.conflict("TASK_NOT_RECONCILABLE","仅未知结果可核对");
        var body=Map.of("commandId",id+"-reconcile-"+UUID.randomUUID(),"scenario","happy_path","plan",runnerPlan(userId,ownedPlan(userId,task.getApplicationPlanId())));
        jdbc.update("UPDATE automation_outbox SET phase='RECONCILE_SEND',payload=?,runner_id=NULL,next_run_at=CURRENT_TIMESTAMP WHERE task_id=?",jsons.write(body),id);
        task.setTaskStatus("verifying");return taskView(task);
    }

    @Transactional
    public TaskView reconcile(UUID userId, UUID id) {
        AutomationTask task = ownedTask(userId, id);
        if (!"unknown_outcome".equals(task.getTaskStatus()))
            throw ApiException.conflict("TASK_NOT_RECONCILABLE", "只有结果未知的任务需要核对");
        ApplicationPlan plan = ownedPlan(userId, task.getApplicationPlanId());
        Map<String, Object> runnerPlan = runnerPlan(userId, plan);
        String commandId = task.getId() + "-reconcile-" + task.getAttempt();
        updateRunnerState(task, "verifying", Math.max(80, task.getProgress()), "正在核对平台历史记录", null);
        Map<String, Object> response = runner.reconcile(Map.of(
                "commandId", commandId,
                "scenario", "happy_path",
                "plan", runnerPlan));
        if (runner.unavailable(response)) return runnerUnavailable(userId, task);
        Map<String, Object> accepted = runner.data(response);
        attachRunnerTask(task, accepted, commandId);
        audit(userId, task.getId(), "RUNNER_RECONCILE_ACCEPTED", "本地执行器已开始核对平台结果");
        return handleRunnerTask(userId, task, plan, runnerPlan,
                runner.awaitTask(Objects.toString(accepted.get("id"), "")), "happy_path");
    }

    @Transactional
    public TaskView transition(UUID userId, UUID id, String status, Integer progress, String currentStep,
                               String humanAction, String receipt) {
        AutomationTask task = ownedTask(userId, id);
        if (!TASK_STATUSES.contains(status)) throw ApiException.badRequest("INVALID_TASK_STATUS", "不支持的自动化状态");
        ApplicationPlan plan = task.getApplicationPlanId() == null ? null : ownedPlan(userId, task.getApplicationPlanId());
        if (Set.of("preparing", "submitting").contains(status) && plan != null) validateApproval(plan);
        task.setTaskStatus(status);
        if (progress != null) task.setProgress(Math.max(0, Math.min(100, progress)));
        if (currentStep != null) task.setCurrentStep(summarizeStep(currentStep));
        task.setHumanActionText(humanAction);
        if (receipt != null) task.setReceipt(receipt);
        task.setStateVersion(task.getStateVersion() + 1);
        audit(userId, task.getId(), "TASK_" + status.toUpperCase(Locale.ROOT), task.getCurrentStep());
        if (status.startsWith("awaiting_")) createHumanAction(userId, task, status, humanAction);
        if ("succeeded".equals(status)) onSuccess(userId, task);
        return taskView(task);
    }

    @Transactional
    public TaskView queueHumanAction(UUID userId,UUID id) {
        AutomationTask task=ownedTask(userId,id);
        if(!task.getTaskStatus().startsWith("awaiting_"))throw ApiException.conflict("NO_PENDING_HUMAN_ACTION","当前任务不等待人工处理");
        resolveLocalHumanActions(userId,id);
        jdbc.update("UPDATE automation_outbox SET phase=CASE WHEN phase='WAITING_SUBMIT' THEN 'RESOLVE_SUBMIT' ELSE 'RESOLVE_PREPARE' END,attempts=0,next_run_at=CURRENT_TIMESTAMP WHERE task_id=?",id);
        task.setTaskStatus("queued");task.setCurrentStep("人工处理已确认，等待后台核验");return taskView(task);
    }

    @Transactional
    public TaskView resolveHumanAction(UUID userId, UUID id) {
        AutomationTask task = ownedTask(userId, id);
        if (!task.getTaskStatus().startsWith("awaiting_"))
            throw ApiException.conflict("NO_PENDING_HUMAN_ACTION", "该任务当前不等待人工处理");
        resolveLocalHumanActions(userId, id);
        task.setTaskStatus("queued");
        task.setCurrentStep("人工处理已完成，等待重新预检");
        task.setHumanActionText(null);
        task.setStateVersion(task.getStateVersion() + 1);
        audit(userId, id, "HUMAN_ACTION_RESOLVED", "任务将从安全检查点继续");
        if (task.getRunnerTaskId() == null || task.getRunnerTaskId().isBlank()) return taskView(task);

        Map<String, Object> response = runner.resolveHumanAction(task.getRunnerTaskId());
        if (runner.unavailable(response)) return runnerUnavailable(userId, task);
        ApplicationPlan plan = ownedPlan(userId, task.getApplicationPlanId());
        Map<String, Object> runnerPlan = runnerPlan(userId, plan);
        Map<String, Object> accepted = runner.data(response);
        return handleRunnerTask(userId, task, plan, runnerPlan,
                runner.awaitTask(Objects.toString(accepted.getOrDefault("id", task.getRunnerTaskId()))), "happy_path");
    }

    @Transactional
    public TaskView retry(UUID userId, UUID id) {
        Long activeRun=jdbc.queryForObject("SELECT COUNT(*) FROM one_stop_run WHERE user_id=? AND status='running'",Long.class,userId);
        if(activeRun!=null&&activeRun>0)throw ApiException.conflict("RETRY_DURING_ONE_STOP","请先停止一条龙，再重新执行历史失败任务，避免超出活动槽位");
        AutomationTask task = ownedTask(userId, id);
        if("boss".equals(task.getPlatform())&&!Objects.equals(task.getPlatformIdentityId(),identities.activeIdOrNull(userId)))throw ApiException.conflict("TASK_ACCOUNT_MISMATCH","失败任务属于其他BOSS账号，请先切换到原账号");
        if (!Set.of("failed", "page_changed").contains(task.getTaskStatus()))
            throw ApiException.conflict("TASK_NOT_RETRYABLE", "当前任务状态不允许重试");
        if(!task.isRetryable())throw ApiException.conflict("TASK_REQUIRES_REDISCOVERY","该失败任务必须重新发现岗位，不能直接重试旧快照");
        ApplicationPlan plan=ownedPlan(userId,task.getApplicationPlanId());
        plan.setIncluded(true);plan.setApprovalStatus("draft");plan.setApprovedHash(null);plan.setApprovalTokenHash(null);plan.setApprovalExpiresAt(null);
        plan.setPlanHash(planHash(plan));
        ApprovalResult result=approve(userId,List.of(plan.getId()),true,false);
        AutomationTask created=ownedTask(userId,result.tasks().get(0).id());
        created.setRetryOfTaskId(task.getId());
        created.setRetryRootTaskId(task.getRetryRootTaskId()==null?task.getId():task.getRetryRootTaskId());
        created.setAttempt(task.getAttempt()+1);
        audit(userId,task.getId(),"RETRY_TASK_CREATED","已创建新的执行尝试 "+created.getId());
        audit(userId,created.getId(),"RETRY_OF_TASK",task.getId().toString());
        return taskView(created);
    }

    @Transactional
    public BatchActionResult retryBatch(UUID userId,List<UUID> ids){
        int completed=0,skipped=0;
        for(UUID id:new LinkedHashSet<>(ids==null?List.of():ids).stream().limit(100).toList()){
            try{retry(userId,id);completed++;}catch(RuntimeException error){skipped++;}
        }
        return new BatchActionResult(completed,skipped);
    }

    @Transactional
    public BatchActionResult reconcileBatch(UUID userId,List<UUID> ids){
        int completed=0,skipped=0;
        for(UUID id:new LinkedHashSet<>(ids==null?List.of():ids).stream().limit(100).toList()){
            try{queueReconcile(userId,id);completed++;}catch(RuntimeException error){skipped++;}
        }
        return new BatchActionResult(completed,skipped);
    }

    @Transactional(readOnly=true)
    public List<FailureSummary> failureSummary(UUID userId){
        Map<String,Long> counts=tasks(userId,1000).stream().filter(task->Set.of("failed","page_changed").contains(task.status()))
                .collect(java.util.stream.Collectors.groupingBy(task->failureCategoryLabel(task.failureCategory(),task.currentStep()),LinkedHashMap::new,java.util.stream.Collectors.counting()));
        return counts.entrySet().stream().map(entry->new FailureSummary(entry.getKey(),entry.getValue())).toList();
    }

    private String failureCategory(String detail){
        String value=Objects.toString(detail,"");
        if(value.contains("岗位")&&(value.contains("不一致")||value.contains("已关闭")))return "岗位页面变化";
        if(value.contains("审批")||value.toLowerCase(Locale.ROOT).contains("approval"))return "审批已过期";
        if(value.contains("登录"))return "登录状态异常";
        if(value.contains("频繁")||value.contains("上限")||value.contains("限制"))return "平台访问限制";
        if(value.contains("招呼")||value.contains("沟通"))return "沟通发送失败";
        return "其他执行错误";
    }

    private String failureCategoryLabel(String category,String detail){
        return switch(Objects.toString(category,"")){
            case "NOT_SUBMITTED_CONFIRMED" -> "确认未发送，可重新执行";
            case "CONTACT_ROUTE_STALE" -> "沟通地址失效，可重新执行";
            case "APPROVAL_EXPIRED" -> "审批已过期，可重新批准";
            case "REQUIRES_REDISCOVERY" -> "岗位快照变化，需重新发现";
            case "JOB_UNAVAILABLE" -> "岗位已关闭";
            case "PLATFORM_LIMITED" -> "平台访问限制";
            default -> failureCategory(detail);
        };
    }

    @Transactional
    public TaskView cancel(UUID userId, UUID id) {
        AutomationTask task=ownedTask(userId,id);
        if(Set.of("submitting","verifying","unknown_outcome","succeeded").contains(task.getTaskStatus()))
            throw ApiException.conflict("RUNNER_TASK_ACTIVE","任务可能已提交，先核对结果，不将历史改成未发送");
        var rows=jdbc.queryForList("SELECT phase,lease_until FROM automation_outbox WHERE task_id=?",id);
        if(!rows.isEmpty()){
            String phase=rows.get(0).get("phase").toString();
            if(phase.contains("SUBMIT") || jdbc.queryForObject("SELECT COUNT(*) FROM automation_outbox WHERE task_id=? AND lease_until>CURRENT_TIMESTAMP",Long.class,id)>0)
                throw ApiException.conflict("RUNNER_TASK_ACTIVE","当前阶段正在执行，请稍后重试取消或核对平台");
            jdbc.update("UPDATE automation_outbox SET phase='DONE' WHERE task_id=?",id);
        }
        return transition(userId, id, "cancelled", null, "任务已取消", null, null);
    }

    @Transactional(readOnly = true)
    public List<AuditView> audits(UUID userId, UUID taskId) {
        ownedTask(userId, taskId);
        return store.query("select a from AuditEvent a where a.userId=:uid and a.aggregateType='AUTOMATION_TASK' and a.aggregateId=:tid order by a.createdAt desc",
                AuditEvent.class, Map.of("uid", userId, "tid", taskId)).stream()
                .map(a -> new AuditView(a.getId(), a.getEventType(), a.getDetailText(), a.getCreatedAt())).toList();
    }

    private void requireLiveRunner() {
        Map<String, Object> status = runner.status();
        if (!Boolean.TRUE.equals(status.get("online")))
            throw ApiException.conflict("RUNNER_OFFLINE", "本地执行器未在线");
        Map<String, Object> details = objectMap(status.get("details"));
        if (!"real".equals(details.get("mode")))
            throw ApiException.conflict("RUNNER_TEST_MODE", "执行器处于测试模式，真实工作区不接受模拟岗位或回执");
    }

    public Map<String, Object> runnerStatus() { return runner.status(); }
    public Map<String,Object> runnerStorage(){return runner.storage();}
    public Map<String,Object> cleanupRunnerLegacyStorage(){return runner.cleanupLegacyStorage();}
    public Map<String, Object> runnerPlatforms() { return runner.getPlatforms(); }
    public Map<String, Object> bossDiagnostics() { return runner.bossDiagnostics(); }
    public Map<String,Object> bossProfiles(UUID userId){var values=identities.list(userId);return Map.of("active",values.stream().filter(PlatformIdentityService.View::active).map(PlatformIdentityService.View::profileName).findFirst().orElse("default"),"profiles",values.stream().map(value->Map.of("id",value.id(),"name",value.profileName(),"displayName",value.displayName(),"active",value.active(),"status",value.status())).toList());}
    public Object bossCities(){return runner.bossCities();}
    public Map<String,Object> activateBossProfile(UUID userId,String name){PlatformIdentityService.View identity=identities.list(userId).stream().filter(item->item.profileName().equals(name)).findFirst().orElseGet(()->identities.create(userId,name,name));return Map.of("identity",identities.activate(userId,identity.id()));}

    private TaskView synchronizeRunnerTask(UUID userId, AutomationTask task, ApplicationPlan plan) {
        Map<String, Object> response = runner.task(task.getRunnerTaskId());
        if (runner.unavailable(response)) return runnerUnavailable(userId, task);
        return handleRunnerTask(userId, task, plan, runnerPlan(userId, plan), runner.data(response), "happy_path");
    }

    private TaskView handleRunnerTask(UUID userId, AutomationTask task, ApplicationPlan plan,
                                      Map<String, Object> runnerPlan, Map<String, Object> runnerTask,
                                      String scenario) {
        String runnerTaskId = Objects.toString(runnerTask.getOrDefault("id", task.getRunnerTaskId()), "");
        if (!runnerTaskId.isBlank()) task.setRunnerTaskId(runnerTaskId);
        task.setRunnerPhase(Objects.toString(runnerTask.getOrDefault("phase", task.getRunnerPhase()), null));
        task.setLastSyncedAt(Instant.now());
        String runnerStatus = Objects.toString(runnerTask.getOrDefault("status", ""));
        String runnerType = Objects.toString(runnerTask.getOrDefault("type", ""));
        int progress = integer(runnerTask.get("progress"), task.getProgress());

        if ("succeeded".equals(runnerStatus) && "prepare".equals(runnerType)) {
            return submitPrepared(userId, task, plan, runnerPlan, runnerTask, scenario);
        }
        if ("succeeded".equals(runnerStatus) && Set.of("submit", "reconcile").contains(runnerType)) {
            Map<String, Object> result = objectMap(runnerTask.get("result"));
            if (!result.containsKey("outcome") || !result.containsKey("receiptId")) {
                updateRunnerState(task, "unknown_outcome", progress, "未观察到有效平台回执", "请在本地平台核对结果");
                return taskView(task);
            }
            String outcome = Objects.toString(result.get("outcome"));
            String receiptId = Objects.toString(result.getOrDefault("receiptId", task.getRunnerTaskId()));
            task.setTaskStatus("succeeded");
            task.setProgress(100);
            task.setCurrentStep("平台已返回可验证的投递结果");
            task.setHumanActionText(null);
            task.setReceipt(outcome + ":" + receiptId);
            task.setReceiptSource(Objects.toString(result.get("receiptSource"), "unverified"));
            task.setPlatformReceiptId(result.get("platformReceiptId") instanceof String value ? value : null);
            task.setStateVersion(task.getStateVersion() + 1);
            plan.setApprovalStatus("executed");
            audit(userId, task.getId(), "RUNNER_SUBMIT_SUCCEEDED", task.getReceipt());
            onSuccess(userId, task);
            return taskView(task);
        }

        if ("waiting_human".equals(runnerStatus)) {
            Map<String, Object> human = objectMap(runnerTask.get("humanAction"));
            String kind = Objects.toString(human.getOrDefault("kind", "QUESTION")).toUpperCase(Locale.ROOT);
            String status = switch (kind) {
                case "LOGIN" -> "awaiting_login";
                case "CAPTCHA" -> "awaiting_captcha";
                default -> "awaiting_question";
            };
            String detail = Objects.toString(human.getOrDefault("description", "请在本地执行器中完成平台验证"));
            updateRunnerState(task, status, progress, "本地执行器等待人工处理", detail);
            ensureHumanAction(userId, task, status, detail);
            audit(userId, task.getId(), "RUNNER_HUMAN_ACTION_REQUIRED", detail);
            return taskView(task);
        }

        if ("unknown_outcome".equals(runnerStatus)) {
            String detail = Objects.toString(runnerTask.getOrDefault("errorMessage", "平台结果不明确，需要核对"));
            updateRunnerState(task, "unknown_outcome", progress, "正在等待结果核对", detail);
            audit(userId, task.getId(), "RUNNER_UNKNOWN_OUTCOME", detail);
            return taskView(task);
        }

        if ("failed".equals(runnerStatus) || "cancelled".equals(runnerStatus)) {
            String errorCode = Objects.toString(runnerTask.getOrDefault("errorCode", "RUNNER_TASK_FAILED"));
            String detail = Objects.toString(runnerTask.getOrDefault("errorMessage", "本地执行任务未完成"));
            String status = "PAGE_CHANGED".equals(errorCode) ? "page_changed"
                    : "cancelled".equals(runnerStatus) ? "cancelled" : "failed";
            updateRunnerState(task, status, progress, detail, null);
            if("failed".equals(status)||"page_changed".equals(status))markPlanFailure(userId,task,errorCode,detail);
            audit(userId, task.getId(), "RUNNER_TASK_" + status.toUpperCase(Locale.ROOT), detail);
            return taskView(task);
        }

        String activeStatus = "submit".equals(runnerType) ? "submitting" : "preparing";
        String step = "submit".equals(runnerType) ? "本地执行器正在提交并核验结果" : "本地执行器正在执行提交前检查";
        updateRunnerState(task, activeStatus, progress, step, null);
        return taskView(task);
    }

    private TaskView submitPrepared(UUID userId, AutomationTask task, ApplicationPlan plan,
                                    Map<String, Object> runnerPlan, Map<String, Object> preparedTask,
                                    String scenario) {
        validateApproval(plan);
        Map<String, Object> prepared = objectMap(preparedTask.get("result"));
        Object pageFingerprint = prepared.get("pageFingerprint");
        if (pageFingerprint != null && !Objects.toString(pageFingerprint).isBlank()) {
            runnerPlan.put("pageFingerprint", Objects.toString(pageFingerprint));
        }

        Map<String, Object> approvalResponse = runner.approve(runnerPlan);
        if (runner.unavailable(approvalResponse)) return runnerUnavailable(userId, task);
        Map<String, Object> approval = runner.data(approvalResponse);
        if (!approval.containsKey("signature") || !approval.containsKey("approvalId")) {
            return runnerFailed(userId, task, "本地执行器未生成有效的一次性确认凭据");
        }

        task.setTaskStatus("submitting");
        task.setProgress(60);
        task.setCurrentStep("一次性确认已签发，正在执行投递");
        task.setHumanActionText(null);
        task.setStateVersion(task.getStateVersion() + 1);
        String commandId = task.getId() + "-submit-" + task.getAttempt();
        Map<String, Object> submitResponse = runner.submit(Map.of(
                "commandId", commandId,
                "scenario", scenario,
                "plan", runnerPlan,
                "approval", approval));
        if ("RUNNER_SUBMIT_UNKNOWN".equals(submitResponse.get("error"))) {
            task.setRunnerCommandId(commandId);
            task.setRunnerTaskId(null);
            updateRunnerState(task, "unknown_outcome", 60, "提交响应丢失，需核对平台记录", "请先核对，勿重新投递");
            audit(userId,task.getId(),"RUNNER_SUBMIT_UNKNOWN","提交可能已被接受，禁止按普通失败重试");
            return taskView(task);
        }
        if (runner.unavailable(submitResponse)) return runnerUnavailable(userId, task);
        Map<String, Object> accepted = runner.data(submitResponse);
        attachRunnerTask(task, accepted, commandId);
        audit(userId, task.getId(), "RUNNER_SUBMIT_ACCEPTED", "本地执行器已接收投递命令");
        return handleRunnerTask(userId, task, plan, runnerPlan,
                runner.awaitTask(Objects.toString(accepted.get("id"), "")), scenario);
    }

    private Map<String, Object> runnerPlan(UUID userId, ApplicationPlan plan) {
        DiscoveredJob job = observedJob(userId,plan.getDiscoveredJobId(),null,plan.getObservationId());
        ResumeVersion resume = resumes.version(userId, plan.getResumeVersionId());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("planId", plan.getId().toString());
        value.put("platform", plan.getPlatform());
        if(plan.getPlatformIdentityId()!=null){
            var identity=identities.owned(userId,plan.getPlatformIdentityId());
            value.put("platformIdentityId",identity.getId().toString());
            value.put("profileName",identity.getProfileName());
            if(identity.getAccountFingerprint()!=null)value.put("accountFingerprint",identity.getAccountFingerprint());
        }
        value.put("externalJobId", plan.getExternalJobId());
        value.put("company", job.getCompany());
        value.put("role", job.getRoleName());
        value.put("location", Objects.toString(job.getLocation(), ""));
        value.put("actionType", plan.getActionType());
        value.put("resumeVersionId", plan.getResumeVersionId().toString());
        value.put("resumeContentSha256", Hashing.sha256(resume.getContentText()));
        value.put("materialSource", "CHAT".equals(plan.getActionType()) ? "CHAT_ONLY" : "PLATFORM_RESUME");
        value.put("message", Objects.toString(plan.getGreetingText(), ""));
        value.put("answers", jsons.map(plan.getFormAnswersText()));
        value.put("jobContentHash", job.getContentHash());
        String canonicalUrl=runnerCanonicalUrl(userId,plan,job);
        if (!canonicalUrl.isBlank()) value.put("canonicalUrl",canonicalUrl);
        return value;
    }

    private String runnerCanonicalUrl(UUID userId, ApplicationPlan plan, DiscoveredJob job) {
        String url=Objects.toString(job.getCanonicalUrl(),"");
        if(!"boss".equalsIgnoreCase(plan.getPlatform()) || url.isBlank() || url.contains("securityId="))return url;
        if(plan.getObservationId()==null)return url;
        List<String> rows=jdbc.query("SELECT raw_json FROM job_observation WHERE id=? AND user_id=?",
                (rs,n)->rs.getString(1),plan.getObservationId(),userId);
        if(rows.isEmpty())return url;
        Map<String,Object> raw=jsons.map(rows.get(0));
        String securityId=Objects.toString(raw.get("securityId"),"");
        String lid=Objects.toString(raw.get("lid"),"");
        if(securityId.isBlank())return url;
        var builder=org.springframework.web.util.UriComponentsBuilder.fromUriString(url)
                .queryParam("securityId",securityId);
        if(!lid.isBlank())builder.queryParam("lid",lid);
        return builder.build().encode(java.nio.charset.StandardCharsets.UTF_8).toUriString();
    }

    private void attachRunnerTask(AutomationTask task, Map<String, Object> runnerTask, String commandId) {
        task.setRunnerTaskId(Objects.toString(runnerTask.getOrDefault("id", ""), null));
        task.setRunnerCommandId(commandId);
        task.setRunnerPhase(Objects.toString(runnerTask.getOrDefault("phase", ""), null));
        task.setLastSyncedAt(Instant.now());
    }

    private TaskView runnerUnavailable(UUID userId, AutomationTask task) {
        return runnerFailed(userId, task, "本地执行器未在线，请启动 CareerLens Runner 后重试");
    }

    private TaskView runnerFailed(UUID userId, AutomationTask task, String detail) {
        updateRunnerState(task, "failed", task.getProgress(), detail, detail);
        audit(userId, task.getId(), "RUNNER_UNAVAILABLE_OR_INVALID", detail);
        return taskView(task);
    }

    private void updateRunnerState(AutomationTask task, String status, int progress, String step, String humanAction) {
        task.setTaskStatus(status);
        task.setProgress(Math.max(0, Math.min(100, progress)));
        task.setCurrentStep(summarizeStep(step));
        task.setHumanActionText(humanAction);
        task.setStateVersion(task.getStateVersion() + 1);
        task.setLastSyncedAt(Instant.now());
    }

    private String summarizeStep(String value) {
        String normalized = Objects.toString(value, "");
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 237) + "...";
    }

    private void resolveLocalHumanActions(UUID userId, UUID taskId) {
        store.query("select h from HumanAction h where h.userId=:uid and h.automationTaskId=:tid and h.actionStatus='PENDING'",
                HumanAction.class, Map.of("uid", userId, "tid", taskId)).forEach(action -> {
            action.setActionStatus("RESOLVED");
            action.setResolvedAt(Instant.now());
        });
    }

    private void ensureHumanAction(UUID userId, AutomationTask task, String status, String detail) {
        long pending = store.count("select count(h) from HumanAction h where h.userId=:uid and h.automationTaskId=:tid and h.actionStatus='PENDING'",
                Map.of("uid", userId, "tid", task.getId()));
        if (pending == 0) createHumanAction(userId, task, status, detail);
    }

    private String normalizeRunnerScenario(String scenario) {
        String normalized = scenario == null || scenario.isBlank() ? "happy_path" : scenario.trim().toLowerCase(Locale.ROOT);
        if (!RUNNER_SCENARIOS.contains(normalized))
            throw ApiException.badRequest("INVALID_RUNNER_SCENARIO", "不支持的本地执行场景");
        return normalized;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> input)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Map<String, Object>> runnerJobs(List<PlatformAccount> accounts, Map<String, Object> spec) {
        Map<String, Object> resolvedSpec = spec == null ? Map.of() : spec;
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (PlatformAccount account : accounts) jobs.addAll(runner.discoverJobs(account.getPlatform(), resolvedSpec));
        return jobs;
    }

    private void saveObservation(UUID userId,UUID runId,ResumeVersion resume,String platform,String external,
                                 Map<String,Object> raw,DiscoveredJob canonical){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM job_observation WHERE run_id=? AND job_id=?",Long.class,runId,canonical.getId())>0)return;
        DiscoveredJob snapshot=runId.equals(canonical.getDiscoveryRunId())?canonical:toJob(userId,runId,canonical.getPlatformIdentityId(),resume,platform,external,raw);
        snapshot.setId(canonical.getId());
        jdbc.update("INSERT INTO job_observation(id,user_id,run_id,job_id,view_json,raw_json,content_hash) VALUES (?,?,?,?,?,?,?)",
            UUID.randomUUID(),userId,runId,canonical.getId(),jsons.write(jobView(snapshot)),jsons.write(raw),snapshot.getContentHash());
    }
    private DiscoveredJob observedJob(UUID userId,UUID jobId,UUID runId,UUID observationId){
        DiscoveredJob canonical=ownedJob(userId,jobId);
        List<String> rows=observationId!=null
            ?jdbc.query("SELECT view_json FROM job_observation WHERE id=? AND user_id=? AND job_id=?",(rs,n)->rs.getString(1),observationId,userId,jobId)
            :runId!=null?jdbc.query("SELECT view_json FROM job_observation WHERE run_id=? AND user_id=? AND job_id=?",(rs,n)->rs.getString(1),runId,userId,jobId):List.of();
        if(rows.isEmpty())return canonical;
        var snapshot=jsons.map(rows.get(0));DiscoveredJob job=new DiscoveredJob();
        org.springframework.beans.BeanUtils.copyProperties(canonical,job);
        job.setCompany(string(snapshot,"company",canonical.getCompany()));job.setRoleName(string(snapshot,"role",canonical.getRoleName()));
        job.setLocation(string(snapshot,"location",""));job.setSalary(string(snapshot,"salary",""));
        job.setMatchScore(integer(snapshot.get("matchScore"),0));job.setGrade(string(snapshot,"grade","C"));
        job.setSummaryText(string(snapshot,"summary",""));job.setContentHash(string(snapshot,"jobContentHash",canonical.getContentHash()));
        job.setMatchedSkillsText(jsons.write(snapshot.getOrDefault("matchedSkills",List.of())));
        job.setMissingSkillsText(jsons.write(snapshot.getOrDefault("missingSkills",List.of())));
        job.setHardConflictsText(jsons.write(snapshot.getOrDefault("hardConflicts",List.of())));
        job.setCompanySize(string(snapshot,"companySize",canonical.getCompanySize()));
        job.setRecruiterOnline(Boolean.TRUE.equals(snapshot.get("recruiterOnline")));
        job.setHeadhunter(Boolean.TRUE.equals(snapshot.get("headhunter")));
        job.setContacted(Boolean.TRUE.equals(snapshot.get("contacted")));
        job.setRiskScore(integer(snapshot.get("riskScore"),canonical.getRiskScore()));
        job.setRiskReasonsText(jsons.write(snapshot.getOrDefault("riskReasons",strings(canonical.getRiskReasonsText()))));
        job.setCommuteDistanceKm(decimal(snapshot.get("commuteDistanceKm"),canonical.getCommuteDistanceKm()));
        job.setCommuteDurationMinutes(nullableInteger(snapshot.get("commuteDurationMinutes"),canonical.getCommuteDurationMinutes()));
        return job;
    }

    private DiscoveredJob toJob(UUID userId, UUID runId, UUID platformIdentityId, ResumeVersion resume, String platform,
                                String externalId, Map<String, Object> raw) {
        DiscoveredJob job = new DiscoveredJob();
        job.setUserId(userId); job.setPlatformIdentityId(platformIdentityId);job.setDiscoveryRunId(runId); job.setPlatform(platform); job.setExternalJobId(externalId);
        job.setCompany(string(raw, "company", "未知公司")); job.setRoleName(string(raw, "role", string(raw, "title", "开发工程师")));
        job.setCanonicalUrl(string(raw,"canonicalUrl",""));
        job.setPlatformMetadataText(jsons.write(raw.get("jobKind")==null ? Map.of() : Map.of("jobKind",raw.get("jobKind"))));
        job.setLocation(string(raw, "location", "")); job.setSalary(string(raw, "salary", "面议"));
        job.setExperience(string(raw, "experience", "未提供")); job.setDegree(string(raw, "degree", "未提供"));
        job.setPublishedAtText(string(raw, "publishedAt", "未提供")); job.setRecruiter(string(raw, "recruiter", "未提供"));
        job.setRecruiterId(string(raw,"recruiterId",null));
        job.setRecruiterActive(string(raw, "recruiterActive", "未提供")); job.setCompanyTone(string(raw, "companyTone", "#eef4f0"));
        job.setCompanySize(string(raw,"companySize","未提供"));job.setRecruiterOnline(Boolean.TRUE.equals(raw.get("recruiterOnline")));
        job.setHeadhunter(Boolean.TRUE.equals(raw.get("headhunter")));job.setContacted(Boolean.TRUE.equals(raw.get("contacted")));
        String description = string(raw, "description", string(raw,"summary",""));
        Map<String,Object> simple=simpleJobMatch(resume,job.getRoleName(),description);
        int score=integer(simple.get("score"),0);
        raw = new LinkedHashMap<>(raw);
        String experienceRisk=string(raw,"experienceRisk","");
        if("adjacent".equals(experienceRisk))score=Math.max(0,score-10);
        raw.put("matchedSkills",simple.get("matchedSkills"));
        raw.put("missingSkills",List.of());
        job.setMatchScore(score); job.setGrade(score >= 80 ? "A" : score >= 65 ? "B" : "C");
        job.setMatchedSkillsText(jsons.write(list(raw.get("matchedSkills")))); job.setMissingSkillsText(jsons.write(list(raw.get("missingSkills"))));
        job.setHardConflictsText(jsons.write(list(raw.get("hardConflicts")))); job.setSummaryText(string(raw, "summary", description));
        String content = string(raw, "description", job.getSummaryText()); job.setJobContentText(content);
        List<String> risks=new ArrayList<>(jobRisks(job,content));
        if("adjacent".equals(experienceRisk))risks.add("经验要求略高一档");
        job.setRiskScore(Math.min(100,risks.size()*20));job.setRiskReasonsText(jsons.write(risks));
        job.setCommuteDistanceKm(decimal(raw.get("commuteDistanceKm"),null));job.setCommuteDurationMinutes(nullableInteger(raw.get("commuteDurationMinutes"),null));
        job.setContentHash(Hashing.sha256(platform + externalId + content)); job.setSelected(false); job.setAlreadyTracked(false);
        return job;
    }

    private Map<String,Object> simpleJobMatch(ResumeVersion resume,String role,String description){
        String resumeText=Objects.toString(resume.getContentText(),"").toLowerCase(Locale.ROOT);
        String jobText=(Objects.toString(role,"")+" "+Objects.toString(description,"")).toLowerCase(Locale.ROOT);
        List<String> dictionary=List.of("java","spring boot","springcloud","spring cloud","mysql","redis","rabbitmq","kafka","docker","kubernetes","k8s","linux","git","maven","vue","react","typescript","javascript","python","c++","golang","微服务","分布式","多线程","jvm","mybatis","oracle","sql server","mongodb","elasticsearch");
        List<String> required=dictionary.stream().filter(jobText::contains).distinct().toList();
        List<String> matched=required.stream().filter(resumeText::contains).toList();
        int score=50;
        if(!required.isEmpty())score+=Math.round(40f*matched.size()/required.size());
        String normalizedRole=Objects.toString(role,"").replaceAll("(?i)(工程师|开发|高级|中级|初级|实习|应届|运维|后端|前端)","").trim().toLowerCase(Locale.ROOT);
        if(!normalizedRole.isBlank()&&resumeText.contains(normalizedRole))score+=10;
        return Map.of("score",Math.min(100,score),"matchedSkills",matched);
    }

    private List<String> jobRisks(DiscoveredJob job,String content){
        List<String> risks=new ArrayList<>();String text=(job.getCompany()+" "+job.getRoleName()+" "+content).toLowerCase(Locale.ROOT);
        if(text.matches("(?s).*(外包|驻场|派遣|人力资源服务|项目外派).*"))risks.add("岗位信息包含外包、驻场或派遣相关描述");
        if(job.getCompany().contains("某")||job.getCompany().contains("不知名")||job.getCompany().contains("招聘企业"))risks.add("公司名称未完整公开");
        if(content==null||content.strip().length()<40)risks.add("岗位描述过短，职责和要求信息不足");
        if(job.isHeadhunter())risks.add("招聘者被平台标记为猎头");
        if(!job.isRecruiterOnline()&&(job.getRecruiterActive()==null||job.getRecruiterActive().isBlank()||"未提供".equals(job.getRecruiterActive())))risks.add("招聘者活跃状态未知");
        if(job.getSalary()==null||job.getSalary().isBlank()||"面议".equals(job.getSalary()))risks.add("薪资范围未明确");
        return risks;
    }

    private void validateApproval(ApplicationPlan plan) {
        if (!"approved".equals(plan.getApprovalStatus()) || plan.getApprovalExpiresAt() == null
                || plan.getApprovalExpiresAt().isBefore(Instant.now()) || !Objects.equals(plan.getApprovedHash(), planHash(plan))) {
            throw ApiException.conflict("APPROVAL_EXPIRED_OR_CHANGED", "投递内容已变化或确认已过期，请重新审核");
        }
    }

    private void markPlanFailure(UUID userId,AutomationTask task,String errorCode,String detail){
        if(task.getApplicationPlanId()==null)return;
        String value=(Objects.toString(errorCode,"")+" "+Objects.toString(detail,"")).toUpperCase(Locale.ROOT);
        String category;boolean retryable;
        if(value.contains("VERIFIED_NOT_SUBMITTED")){category="NOT_SUBMITTED_CONFIRMED";retryable=true;}
        else if(value.contains("BOSS_CONTACT_URL_MISSING")){category="CONTACT_ROUTE_STALE";retryable=true;}
        else if(value.contains("APPROVAL_EXPIRED")||value.contains("APPROVAL HAS EXPIRED")||value.contains("审批凭证")){category="APPROVAL_EXPIRED";retryable=true;}
        else if(value.contains("BOSS_PAGE_JOB_ID_MISMATCH")||value.contains("BOSS_PAGE_IDENTITY_MISMATCH")||value.contains("BOSS_JOB_REDIRECTED_HOME")){category="REQUIRES_REDISCOVERY";retryable=false;}
        else if(value.contains("JOB_UNAVAILABLE")||value.contains("职位已关闭")||value.contains("停止招聘")){category="JOB_UNAVAILABLE";retryable=false;}
        else if(value.contains("RATE_LIMIT")||value.contains("DAILY_CONTACT_LIMIT")||value.contains("BOSS_JOB_API_3")){category="PLATFORM_LIMITED";retryable=false;}
        else{category="EXECUTION_FAILED";retryable=false;}
        task.setFailureCategory(category);task.setRetryable(retryable);
        ApplicationPlan plan=ownedPlan(userId,task.getApplicationPlanId());
        plan.setIncluded(false);
        plan.setApprovalStatus(retryable?"failed_retryable":"REQUIRES_REDISCOVERY".equals(category)?"requires_rediscovery":"failed_final");
    }

    private void onSuccess(UUID userId, AutomationTask task) {
        String effectiveAction = "boss".equals(task.getPlatform()) ? "CHAT" : task.getActionType();
        Map<String,Object> existingParameters=new HashMap<>();existingParameters.put("uid",userId);existingParameters.put("identity",task.getPlatformIdentityId());existingParameters.put("platform",task.getPlatform());existingParameters.put("external",task.getExternalJobId());existingParameters.put("action",effectiveAction);
        long existing = store.count("select count(a) from JobApplication a where a.userId=:uid and a.platformIdentityId=:identity and a.platform=:platform and a.externalJobId=:external and a.actionType=:action",existingParameters);
        if (task.getApplicationPlanId()!=null) ownedJob(userId, ownedPlan(userId,task.getApplicationPlanId()).getDiscoveredJobId()).setAlreadyTracked(true);
        if (existing > 0) {jdbc.update("UPDATE job_application SET next_action='手机端跟进',handoff_status='handed_off_to_user',handed_off_at=CURRENT_TIMESTAMP WHERE user_id=? AND platform_identity_id=? AND platform=? AND external_job_id=?",userId,task.getPlatformIdentityId(),task.getPlatform(),task.getExternalJobId());return;}
        applications.create(userId,task.getPlatformIdentityId(),new ApplicationService.CreateCommand(task.getCompany(), task.getRoleName(), "", "CHAT".equals(effectiveAction)?"contacted":"applied", 0,
                "手机端跟进", task.getCompany().substring(0, 1), "#eef4f0", List.of("首条招呼","手机接管"), task.getPlatform(),
                task.getExternalJobId(), task.getResumeVersionNumber(), effectiveAction, "succeeded", task.getReceipt()));
        jdbc.update("UPDATE job_application SET handoff_status='handed_off_to_user',handed_off_at=CURRENT_TIMESTAMP WHERE user_id=? AND platform_identity_id=? AND platform=? AND external_job_id=?",userId,task.getPlatformIdentityId(),task.getPlatform(),task.getExternalJobId());
    }

    private void createHumanAction(UUID userId, AutomationTask task, String status, String detail) {
        HumanAction action = new HumanAction(); action.setUserId(userId); action.setAutomationTaskId(task.getId());
        action.setActionKind(status.substring("awaiting_".length()).toUpperCase(Locale.ROOT));
        action.setTitle("需要人工处理"); action.setDescriptionText(detail == null ? "请打开本地执行器处理" : detail);
        action.setActionStatus("PENDING"); action.setExpiresAt(Instant.now().plus(30, ChronoUnit.MINUTES)); store.persist(action);
    }

    private void audit(UUID userId, UUID taskId, String type, String detail) {
        AuditEvent event = new AuditEvent(); event.setUserId(userId); event.setAggregateType("AUTOMATION_TASK");
        event.setAggregateId(taskId); event.setEventType(type); event.setDetailText(detail == null ? "" : detail); store.persist(event);
    }

    private PlatformAccount ownedPlatform(UUID userId, UUID id) {
        return store.one("select p from PlatformAccount p where p.id=:id and p.userId=:uid", PlatformAccount.class,
                        Map.of("id", id, "uid", userId)).orElseThrow(() -> ApiException.notFound("平台账号"));
    }
    private DiscoveryRun ownedDiscovery(UUID userId, UUID id) {
        DiscoveryRun value=store.one("select d from DiscoveryRun d where d.id=:id and d.userId=:uid", DiscoveryRun.class,
                        Map.of("id", id, "uid", userId)).orElseThrow(() -> ApiException.notFound("职位发现任务"));
        ensureActiveIdentity(userId,value.getPlatformIdentityId(),value.getPlatformsText());return value;
    }
    private DiscoveredJob ownedJob(UUID userId, UUID id) {
        DiscoveredJob value=store.one("select j from DiscoveredJob j where j.id=:id and j.userId=:uid", DiscoveredJob.class,
                        Map.of("id", id, "uid", userId)).orElseThrow(() -> ApiException.notFound("发现的职位"));ensureActiveIdentity(userId,value.getPlatformIdentityId(),value.getPlatform());return value;
    }
    private ApplicationPlan ownedPlan(UUID userId, UUID id) {
        ApplicationPlan value=store.one("select p from ApplicationPlan p where p.id=:id and p.userId=:uid", ApplicationPlan.class,
                        Map.of("id", id, "uid", userId)).orElseThrow(() -> ApiException.notFound("投递计划"));ensureActiveIdentity(userId,value.getPlatformIdentityId(),value.getPlatform());return value;
    }
    private AutomationTask ownedTask(UUID userId, UUID id) {
        AutomationTask value=store.one("select t from AutomationTask t where t.id=:id and t.userId=:uid", AutomationTask.class,
                        Map.of("id", id, "uid", userId)).orElseThrow(() -> ApiException.notFound("自动化任务"));ensureActiveIdentity(userId,value.getPlatformIdentityId(),value.getPlatform());return value;
    }
    private void ensureActiveIdentity(UUID userId,UUID identity,String platform){if(platform!=null&&platform.contains("boss")&&!Objects.equals(identity,identities.activeIdOrNull(userId)))throw ApiException.notFound("当前BOSS账号的数据");}

    private String normalizePlatform(String platform) {
        String normalized = platform == null ? "" : platform.toLowerCase(Locale.ROOT);
        if (!PLATFORMS.contains(normalized)) throw ApiException.badRequest("INVALID_PLATFORM", "只支持 boss 或 liepin");
        return normalized;
    }
    private void requireDiscoveryAllowed(UUID userId, List<PlatformAccount> accounts) {
        if (accounts.stream().noneMatch(account -> "boss".equals(account.getPlatform()))) return;
        var activeIdentity=identities.active(userId);
        if(!"connected".equals(activeIdentity.getConnectionStatus()))throw ApiException.conflict("PLATFORM_LOGIN_REQUIRED","当前BOSS账号尚未完成登录，请先切换到该账号并连接检查");
        UUID identity=activeIdentity.getId();
        List<Boolean> paused = identity==null?List.of():jdbc.query("SELECT paused FROM platform_execution_policy WHERE user_id=? AND platform_identity_id=?",
                (rs, n) -> rs.getBoolean(1), userId,identity);
        if (!paused.isEmpty() && paused.get(0))
            throw ApiException.conflict("BOSS_ACCESS_PAUSED",
                    "BOSS访问已因平台限制暂停。请先在官方页面确认账号恢复，再到自动执行页手动恢复策略。");
    }
    /**
     * BOSS 的自动动作默认只发送审核后的首条招呼语。
     * 猎聘仍使用其独立的投递接口，避免把不支持聊天动作的平台误切换。
     */
    private String actionType(String platform) { return "boss".equals(platform) ? "CHAT" : "RESUME_SUBMIT"; }
    private List<String> capabilities(String platform) { return "boss".equals(platform)
            ? List.of("DISCOVER", "READ_JOB", "CHAT_INITIATED", "MESSAGE_SENT")
            : List.of("DISCOVER", "READ_JOB", "READ_RESUME", "APPLICATION_SUBMITTED", "MESSAGE_SENT"); }
    private String planHash(ApplicationPlan p) { return Hashing.sha256(String.join("|", p.getPlatform(), p.isIdentityBound()?Objects.toString(p.getPlatformIdentityId(),""):"",p.getExternalJobId(),
            p.getActionType(), p.getResumeVersionId().toString(), Objects.toString(p.getObservationId(),""), Objects.toString(p.getGreetingText(), ""),
            Objects.toString(p.getGreetingStyle(),""),Objects.toString(p.getGreetingEvidenceText(),""),Objects.toString(p.getFormAnswersText(), ""))); }

    private PlatformView platformView(PlatformAccount account) { return new PlatformView(account.getId(), account.getPlatform(),
            account.getDisplayName(), account.getConnectionType(), account.getConnectionStatus(), account.getMaskedIdentity(),
            strings(account.getCapabilitiesText()), account.getAdapterVersion(), account.getLastCheckedAt()); }
    private DiscoveryView discoveryView(DiscoveryRun run) {
        List<String> errors=jdbc.query("SELECT last_error FROM discovery_command WHERE run_id=? AND status IN ('FAILED','PARTIAL') AND last_error IS NOT NULL ORDER BY next_attempt_at DESC",
                (rs,n)->rs.getString(1),run.getId());
        String errorMessage=errors.stream().findFirst().orElse(null);
        String errorCode=errorMessage==null?null:errorMessage.contains("BOSS_JOB_API_36")?"BOSS_ACCOUNT_RISK_CONTROL"
                :errorMessage.contains("BOSS_JOB_API_37")?"BOSS_SEARCH_TEMPORARILY_LIMITED"
                :errorMessage.contains("LOGIN_REQUIRED")?"PLATFORM_LOGIN_REQUIRED"
                :errorMessage.contains("CAPTCHA")||errorMessage.contains("验证码")?"PLATFORM_CAPTCHA_REQUIRED":"DISCOVERY_PLATFORM_FAILED";
        return new DiscoveryView(run.getId(), run.getStatus(), run.getProgress(),
                run.getDiscoveredCount(), Math.max(0, run.getDiscoveredCount() - run.getDuplicateCount()), run.getDuplicateCount(),
                errorCode,errorMessage,run.getCreatedAt(), run.getUpdatedAt());
    }
    private JobView jobView(DiscoveredJob job) { return new JobView(job.getId(), job.getPlatform(), job.getExternalJobId(), job.getCompany(),
            job.getRoleName(), job.getLocation(), job.getSalary(), job.getExperience(), job.getDegree(), job.getPublishedAtText(),
            job.getRecruiter(), job.getRecruiterActive(), job.isRecruiterOnline(), job.getCompanySize(), job.isHeadhunter(), job.isContacted(),
            job.getRiskScore(), strings(job.getRiskReasonsText()), companyTaskCount(job), recruiterTaskCount(job),
            job.getCommuteDistanceKm(),job.getCommuteDurationMinutes(),
            job.getCompanyTone(), job.getMatchScore(), job.getGrade(),
            strings(job.getMatchedSkillsText()), strings(job.getMissingSkillsText()), strings(job.getHardConflictsText()),
            job.getSummaryText(), job.isAlreadyTracked(), job.getContentHash()); }
    private PlanView planView(ApplicationPlan p, DiscoveredJob job) { return new PlanView(p.getId(), job.getId(), p.getPlatform(),
            p.getExternalJobId(), job.getCompany(), job.getRoleName(), job.getLocation(), job.getSalary(), job.getMatchScore(),
            job.getGrade(), p.getActionType(), p.getResumeVersionId(), p.getResumeVersionNumber(), p.getGreetingText(),
            strings(job.getMatchedSkillsText()), strings(job.getMissingSkillsText()), strings(job.getHardConflictsText()),
            job.getRecruiter(),job.getRecruiterActive(),job.isRecruiterOnline(),job.getCompanySize(),job.getRiskScore(),strings(job.getRiskReasonsText()),
            companyTaskCount(job),recruiterTaskCount(job),job.getCommuteDistanceKm(),job.getCommuteDurationMinutes(),
            p.isIncluded(), effectiveApprovalStatus(p), p.getPlanHash(), job.getContentHash(), p.getApprovalExpiresAt(),p.getCreatedAt(),
            p.getGreetingStyle(),jsons.map(p.getGreetingEvidenceText()),jsons.list(p.getGreetingCandidatesText())); }
    private String effectiveApprovalStatus(ApplicationPlan plan) {
        return "approved".equals(plan.getApprovalStatus()) && plan.getApprovalExpiresAt() != null
                && plan.getApprovalExpiresAt().isBefore(Instant.now()) ? "expired" : plan.getApprovalStatus();
    }
    private int companyTaskCount(DiscoveredJob job){
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=? AND platform_identity_id=? AND LOWER(company)=LOWER(?)",Integer.class,job.getUserId(),job.getPlatformIdentityId(),job.getCompany());
        return count==null?0:count;
    }
    private int recruiterTaskCount(DiscoveredJob job){
        if(job.getRecruiter()==null||job.getRecruiter().isBlank()||"未提供".equals(job.getRecruiter()))return 0;
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM automation_task t JOIN discovered_job d ON d.user_id=t.user_id AND d.platform=t.platform AND d.platform_identity_id=t.platform_identity_id AND d.external_job_id=t.external_job_id WHERE t.user_id=? AND t.platform_identity_id=? AND d.recruiter=?",Integer.class,job.getUserId(),job.getPlatformIdentityId(),job.getRecruiter());
        return count==null?0:count;
    }
    private TaskView taskView(AutomationTask t) { return new TaskView(t.getId(), t.getApplicationPlanId(), t.getPlatform(),
            t.getExternalJobId(), t.getCompany(), t.getRoleName(), t.getActionType(), t.getResumeVersionNumber(),
            t.getTaskStatus(), t.getProgress(), t.getCurrentStep(), t.getHumanActionText(), t.getReceipt(), t.getAttempt(),
            t.getStateVersion(), t.getReceiptSource(), t.getPlatformReceiptId(), t.getRunnerTaskId(), t.getRunnerCommandId(), t.getRunnerPhase(), t.getLastSyncedAt(),
            t.getCreatedAt(), t.getUpdatedAt(),t.getRetryOfTaskId(),t.getRetryRootTaskId(),t.getFailureCategory(),t.isRetryable()); }
    private List<String> strings(String text) { return jsons.list(text).stream().map(String::valueOf).toList(); }
    private static String string(Map<String, Object> map, String key, String fallback) { Object v = map.get(key); return v == null ? fallback : String.valueOf(v); }
    private static int integer(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
    private static Integer nullableInteger(Object value,Integer fallback){return value instanceof Number n?n.intValue():fallback;}
    private static Double decimal(Object value,Double fallback){return value instanceof Number n?n.doubleValue():fallback;}
    private static List<String> list(Object value) { return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(); }

    private Map<String,Object> normalizeDiscoverySpec(Map<String,Object> input){
        Map<String,Object> value=new LinkedHashMap<>(input==null?Map.of():input);
        value.put("maxJobs",Math.max(1,Math.min(150,integer(value.get("maxJobs"),50))));
        value.put("maxPages",Math.max(1,Math.min(10,integer(value.get("maxPages"),2))));
        value.put("maxTotalPages",Math.max(1,Math.min(100,integer(value.get("maxTotalPages"),30))));
        value.put("minimumScore",Math.max(0,Math.min(100,integer(value.get("minimumScore"),70))));
        return value;
    }

    public record PlatformView(UUID id, String platform, String displayName, String connectionType, String status,
                               String identity, List<String> capabilities, String adapterVersion, Instant lastCheckedAt) {}
    public record DiscoveryView(UUID id, String status, int progress, int rawCount, int deduplicatedCount,
                                int duplicateCount, String errorCode, String errorMessage,
                                Instant createdAt, Instant updatedAt) {}
    public record DiscoveryFilterStats(int platformReturned,int runnerCandidates,int runnerFiltered,int runnerReturned,int inRunDuplicates,
                                       int historicalDuplicates,int linkedJobs,int alreadyTracked,
                                       int contacted,int hardConflict,int scoreBelowMinimum,int companyCooldown,
                                       int recruiterCooldown,int eligible,int keywordMismatch,int requiredKeywordMismatch,
                                       int excludedKeyword,int excludedCompany,int headhunter,int contactedByPlatform,
                                       int recruiterActivity,int salaryMismatch,int experienceMismatch,int degreeMismatch,int eligibleTarget,String searchStopReason,boolean cacheHit,boolean partial,List<?> pages){}
    public record DailyQuota(int dailyLimit,int used,int remaining,int platformLimit,boolean dryRun){}
    public record JobView(UUID id, String platform, String externalJobId, String company, String role, String location,
                          String salary, String experience, String degree, String publishedAt, String recruiter,
                          String recruiterActive, boolean recruiterOnline, String companySize, boolean headhunter, boolean contacted,
                          int riskScore, List<String> riskReasons, int companyTaskCount, int recruiterTaskCount,
                          Double commuteDistanceKm, Integer commuteDurationMinutes,
                          String companyTone, int matchScore, String grade,
                          List<String> matchedSkills, List<String> missingSkills, List<String> hardConflicts,
                          String summary, boolean alreadyTracked, String jobContentHash) {}
    public record PlanView(UUID id, UUID jobId, String platform, String externalJobId, String company, String role,
                           String location, String salary, int matchScore, String grade, String actionType,
                           UUID resumeVersionId, int resumeVersion, String greeting, List<String> matchedSkills,
                           List<String> missingSkills, List<String> hardConflicts, String recruiter, String recruiterActive,
                           boolean recruiterOnline, String companySize, int riskScore, List<String> riskReasons,
                           int companyTaskCount, int recruiterTaskCount, Double commuteDistanceKm, Integer commuteDurationMinutes, boolean included,
                           String approvalStatus, String planHash, String jobContentHash, Instant approvalExpiresAt,Instant createdAt,
                           String greetingStyle,Map<String,Object> greetingEvidence,List<Object> greetingCandidates) {}
    public record ApprovalResult(UUID automationRunId, String status, int approvedCount,
                                 Instant approvalExpiresAt, List<TaskView> tasks) {}
    public record TaskView(UUID id, UUID reviewId, String platform, String externalJobId, String company, String role,
                           String actionType, int resumeVersion, String status, int progress, String currentStep,
                           String humanAction, String receipt, int attempt, long stateVersion, String receiptSource, String platformReceiptId,
                           String runnerTaskId, String runnerCommandId, String runnerPhase, Instant lastSyncedAt,
                           Instant createdAt, Instant updatedAt,UUID retryOfTaskId,UUID retryRootTaskId,String failureCategory,boolean retryable) {}
    public record AuditView(UUID id, String type, String detail, Instant occurredAt) {}
    public record TaskDetail(TaskView task, List<AuditView> events) {}
    public record BatchActionResult(int completed,int skipped){}
    public record FailureSummary(String reason,long count){}
}
