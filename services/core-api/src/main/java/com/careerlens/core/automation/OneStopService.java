package com.careerlens.core.automation;

import com.careerlens.core.common.ApiException;
import com.careerlens.core.common.Jsons;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OneStopService {
    private final JdbcTemplate jdbc;
    private final Jsons jsons;
    private final AutomationService automation;
    private final PlatformIdentityService identities;
    private final com.careerlens.core.integration.RunnerClient runner;

    @Transactional
    public RunView start(UUID userId, UUID resumeVersionId, List<String> platforms, Map<String,Object> spec) {
        jdbc.queryForObject("SELECT id FROM app_user WHERE id=? FOR UPDATE",UUID.class,userId);
        Long active=jdbc.queryForObject("SELECT COUNT(*) FROM one_stop_run WHERE user_id=? AND status IN ('running','paused')",Long.class,userId);
        if(active!=null&&active>0)throw ApiException.conflict("ONE_STOP_ALREADY_ACTIVE","已有一条龙任务，请先继续或停止原任务");
        Set<String> requested=new LinkedHashSet<>(platforms==null?List.of():platforms.stream().map(String::toLowerCase).toList());
        List<String> connected=automation.platforms(userId).stream().filter(item->"connected".equals(item.status()))
                .map(AutomationService.PlatformView::platform).filter(value->requested.isEmpty()||requested.contains(value)).toList();
        if(connected.isEmpty())throw ApiException.badRequest("NO_CONNECTED_PLATFORM","请先连接至少一个招聘平台");
        if(connected.contains("boss")&&!"connected".equals(identities.active(userId).getConnectionStatus()))throw ApiException.conflict("PLATFORM_LOGIN_REQUIRED","当前BOSS账号尚未完成登录和连接检查");
        UUID id=UUID.randomUUID();Instant now=Instant.now();
        UUID identity=connected.contains("boss")?identities.activeId(userId):null;
        String specText=jsons.write(spec==null?Map.of():spec);
        jdbc.update("INSERT INTO one_stop_run(id,user_id,resume_version_id,platform_identity_id,platforms_text,spec_text,status,phase,discovery_run_id,created_at,updated_at) VALUES (?,?,?,?,?,?,'running',?,?,?,?)",
                id,userId,resumeVersionId,identity,jsons.write(connected),specText,"DISCOVER",null,Timestamp.from(now),Timestamp.from(now));
        return get(userId,id);
    }

    @Transactional(readOnly=true)
    public RunView current(UUID userId) {
        UUID identity=identities.activeIdOrNull(userId);
        return jdbc.query("SELECT * FROM one_stop_run WHERE user_id=? AND (platform_identity_id=? OR platform_identity_id IS NULL AND ? IS NULL) ORDER BY created_at DESC",(rs,n)->view(new LinkedHashMap<>(Map.ofEntries(
                Map.entry("id",rs.getObject("id")),Map.entry("user_id",rs.getObject("user_id")),Map.entry("platform_identity_id",Objects.toString(rs.getObject("platform_identity_id"),"")),Map.entry("status",rs.getString("status")),Map.entry("phase",rs.getString("phase")),
                Map.entry("discovery_round",rs.getInt("discovery_round")),Map.entry("group_offset",rs.getInt("group_offset")),
                Map.entry("processed_count",rs.getInt("processed_count")),Map.entry("job_ids_text",rs.getString("job_ids_text")),
                Map.entry("spec_text",rs.getString("spec_text")),
                Map.entry("task_ids_text",rs.getString("task_ids_text")),Map.entry("last_error",Objects.toString(rs.getString("last_error"),"")),
                Map.entry("succeeded_count",rs.getInt("succeeded_count")),Map.entry("failed_count",rs.getInt("failed_count")),
                Map.entry("cancelled_count",rs.getInt("cancelled_count")),Map.entry("attention_count",rs.getInt("attention_count")),
                Map.entry("created_at",rs.getTimestamp("created_at").toInstant()),Map.entry("updated_at",rs.getTimestamp("updated_at").toInstant()),
                Map.entry("empty_discovery_count",rs.getInt("empty_discovery_count")),
                Map.entry("next_run_at",rs.getTimestamp("next_run_at").toInstant())
        ))),userId,identity,identity).stream().findFirst().orElse(new RunView(null,"idle","IDLE",0,0,0,0,0,0,0,0,0,0,null,null,null,0,null,0,0,0,0,0,null,0,20,20,150));
    }

    @Transactional(readOnly=true)
    public RunView get(UUID userId,UUID id){
        UUID identity=identities.activeIdOrNull(userId);
        return jdbc.queryForObject("SELECT * FROM one_stop_run WHERE id=? AND user_id=? AND (platform_identity_id=? OR platform_identity_id IS NULL AND ? IS NULL)",(rs,n)->{
            DiscoveryMetrics metrics=discoveryMetrics(id,rs.getString("spec_text"));
            AutomationService.DailyQuota quota=automation.dailyQuota(userId,identity);
            return new RunView(
                id,rs.getString("status"),rs.getString("phase"),rs.getInt("discovery_round"),groupNumber(rs.getInt("group_offset")),
                jsons.list(rs.getString("job_ids_text")).size(),jsons.list(rs.getString("task_ids_text")).size(),rs.getInt("processed_count"),
                targetCount(rs.getString("spec_text")),
                rs.getInt("succeeded_count"),rs.getInt("failed_count"),rs.getInt("cancelled_count"),rs.getInt("attention_count"),
                rs.getString("last_error"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),rs.getInt("empty_discovery_count"),rs.getTimestamp("next_run_at").toInstant(),
                metrics.detectedCount(),metrics.candidateLimit(),metrics.pagesCompleted(),metrics.pageLimit(),metrics.progress(),metrics.startedAt(),
                quota.used(),quota.dailyLimit(),quota.remaining(),quota.platformLimit());
        },id,userId,identity,identity);
    }

    @Transactional
    public RunView pause(UUID userId,UUID id){
        UUID identity=identities.activeIdOrNull(userId);
        if(jdbc.update("UPDATE one_stop_run SET status='paused',last_error='用户暂停，当前检查点已保存',updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND (platform_identity_id=? OR platform_identity_id IS NULL AND ? IS NULL) AND status='running'",id,userId,identity,identity)==0)
            throw ApiException.conflict("ONE_STOP_NOT_RUNNING","一条龙当前不在运行");
        return get(userId,id);
    }

    @Transactional
    public RunView stop(UUID userId,UUID id){
        UUID identity=identities.activeIdOrNull(userId);
        List<UUID> discoveries=jdbc.query("SELECT discovery_run_id FROM one_stop_run WHERE id=? AND user_id=? AND discovery_run_id IS NOT NULL",(rs,n)->rs.getObject(1,UUID.class),id,userId);
        if(jdbc.update("UPDATE one_stop_run SET stop_requested=TRUE,status='stopped',pending_discovery_run_id=discovery_run_id,phase='STOPPED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND (platform_identity_id=? OR platform_identity_id IS NULL AND ? IS NULL) AND status IN ('running','paused')",id,userId,identity,identity)==0)
            throw ApiException.conflict("ONE_STOP_NOT_ACTIVE","一条龙任务已经结束");
        for(UUID discoveryId:discoveries){
            jdbc.update("UPDATE discovery_command SET status='CANCELLED',cancel_requested=TRUE,cancelled_at=CURRENT_TIMESTAMP WHERE run_id=? AND status='NEW'",discoveryId);
            List<String> runnerIds=jdbc.query("SELECT runner_id FROM discovery_command WHERE run_id=? AND status='POLL' AND runner_id IS NOT NULL",(rs,n)->rs.getString(1),discoveryId);
            for(String runnerId:runnerIds){
                try{runner.cancelTask(runnerId);jdbc.update("UPDATE discovery_command SET status='CANCELLED',cancel_requested=TRUE,cancelled_at=CURRENT_TIMESTAMP WHERE run_id=? AND runner_id=?",discoveryId,runnerId);}
                catch(RuntimeException ignored){jdbc.update("UPDATE discovery_command SET cancel_requested=TRUE WHERE run_id=? AND runner_id=?",discoveryId,runnerId);}
            }
            Long running=jdbc.queryForObject("SELECT COUNT(*) FROM discovery_command WHERE run_id=? AND status='POLL'",Long.class,discoveryId);
            if(running==null||running==0)jdbc.update("UPDATE discovery_run SET status='cancelled',progress=100,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='queued'",discoveryId);
        }
        return get(userId,id);
    }

    @Transactional
    public RunView resume(UUID userId,UUID id){
        UUID identity=identities.activeIdOrNull(userId);
        Map<String,Object> row=jdbc.queryForMap("SELECT phase,task_ids_text,last_error,discovery_run_id,spec_text FROM one_stop_run WHERE id=? AND user_id=? AND (platform_identity_id=? OR platform_identity_id IS NULL AND ? IS NULL)",id,userId,identity,identity);
        List<String> taskIds=jsons.list(Objects.toString(row.get("task_ids_text"),"[]")).stream().map(Object::toString).toList();
        if("WAIT_GROUP".equals(row.get("phase"))&&!taskIds.isEmpty()){
            String placeholders=String.join(",",Collections.nCopies(taskIds.size(),"?"));
            List<Object> args=new ArrayList<>();args.add(userId);args.addAll(taskIds.stream().map(UUID::fromString).toList());
            List<String> existing=jdbc.query("SELECT CAST(id AS VARCHAR) FROM automation_task WHERE user_id=? AND id IN ("+placeholders+")",(rs,n)->rs.getString(1),args.toArray());
            if(existing.isEmpty())jdbc.update("UPDATE one_stop_run SET phase='DISCOVER',discovery_run_id=NULL,job_ids_text='[]',task_ids_text='[]',group_offset=0,reconcile_state_text='{}' WHERE id=?",id);
            else if(existing.size()!=taskIds.size())jdbc.update("UPDATE one_stop_run SET task_ids_text=?,phase=? WHERE id=?",jsons.write(existing),existing.size()<OneStopDispatcher.ACTIVE_WINDOW_SIZE?"APPROVE_GROUP":"WAIT_GROUP",id);
        }
        if("WAIT_DISCOVERY".equals(row.get("phase"))){
            UUID discoveryId=row.get("discovery_run_id")==null?null:UUID.fromString(row.get("discovery_run_id").toString());
            String status=discoveryId==null?null:jdbc.queryForObject("SELECT status FROM discovery_run WHERE id=?",String.class,discoveryId);
            if("partial".equals(status)){
                Map<String,Object> stats=jsons.map(jdbc.queryForObject("SELECT filter_stats_text FROM discovery_run WHERE id=?",String.class,discoveryId));
                List<Map<String,Object>> pending=maps(stats.get("pendingScopes"));
                if(pending.isEmpty())jdbc.update("UPDATE one_stop_run SET phase='APPROVE_GROUP',last_error=NULL WHERE id=?",id);
                else automation.resumePartialDiscovery(userId,discoveryId,pending,
                        number(stats.get("pagesCompleted"),completedPageCount(stats)),number(stats.get("detectedCount"),number(stats.get("runnerCandidates"),0)),
                        number(stats.get("pageLimit"),number(jsons.map(row.get("spec_text").toString()).get("maxTotalPages"),30)),
                        number(stats.get("candidateLimit"),150));
            }else if(OneStopDispatcher.isTransientDiscoveryFailure(Objects.toString(row.get("last_error"),"")))
                jdbc.update("UPDATE one_stop_run SET phase='DISCOVER',discovery_run_id=NULL,transient_discovery_failures=0 WHERE id=?",id);
        }
        if(jdbc.update("UPDATE one_stop_run SET stop_requested=FALSE,status='running',last_error=NULL,next_run_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND (platform_identity_id=? OR platform_identity_id IS NULL AND ? IS NULL) AND status='paused'",id,userId,identity,identity)==0)
            throw ApiException.conflict("ONE_STOP_NOT_PAUSED","当前一条龙任务不需要继续");
        return get(userId,id);
    }

    private RunView view(Map<String,Object> row){
        UUID id=UUID.fromString(row.get("id").toString());
        DiscoveryMetrics metrics=discoveryMetrics(id,row.get("spec_text").toString());
        UUID user=UUID.fromString(row.get("user_id").toString());
        String identityText=Objects.toString(row.get("platform_identity_id"),"");
        UUID identity=identityText.isBlank()?null:UUID.fromString(identityText);
        AutomationService.DailyQuota quota=automation.dailyQuota(user,identity);
        return new RunView(UUID.fromString(row.get("id").toString()),row.get("status").toString(),row.get("phase").toString(),
                ((Number)row.get("discovery_round")).intValue(),groupNumber(((Number)row.get("group_offset")).intValue()),
                jsons.list(row.get("job_ids_text").toString()).size(),jsons.list(row.get("task_ids_text").toString()).size(),
                ((Number)row.get("processed_count")).intValue(),targetCount(row.get("spec_text").toString()),
                ((Number)row.get("succeeded_count")).intValue(),((Number)row.get("failed_count")).intValue(),
                ((Number)row.get("cancelled_count")).intValue(),((Number)row.get("attention_count")).intValue(),
                Objects.toString(row.get("last_error"),null),
                (Instant)row.get("created_at"),(Instant)row.get("updated_at"),
                ((Number)row.getOrDefault("empty_discovery_count",0)).intValue(),(Instant)row.get("next_run_at"),
                metrics.detectedCount(),metrics.candidateLimit(),metrics.pagesCompleted(),metrics.pageLimit(),metrics.progress(),metrics.startedAt(),
                quota.used(),quota.dailyLimit(),quota.remaining(),quota.platformLimit());
    }

    public record RunView(UUID id,String status,String phase,int discoveryRound,int groupNumber,int batchJobCount,
                          int currentTaskCount,int processedCount,int targetCount,int succeededCount,int failedCount,int cancelledCount,
                          int attentionCount,String lastError,Instant createdAt,Instant updatedAt,
                          int emptyDiscoveryCount,Instant nextRunAt,int discoveryDetectedCount,int discoveryCandidateLimit,
                          int discoveryPagesCompleted,int discoveryPageLimit,int discoveryProgress,Instant discoveryStartedAt,
                          int dailyUsed,int dailyLimit,int dailyRemaining,int platformDailyLimit){}
    private record DiscoveryMetrics(int detectedCount,int candidateLimit,int pagesCompleted,int pageLimit,int progress,Instant startedAt){}
    private DiscoveryMetrics discoveryMetrics(UUID oneStopId,String specText){
        int configuredPages=Math.max(1,Math.min(100,number(jsons.map(specText).get("maxTotalPages"),30)));
        return jdbc.query("SELECT d.progress,d.filter_stats_text,d.created_at FROM one_stop_run r LEFT JOIN discovery_run d ON d.id=r.discovery_run_id WHERE r.id=?",
                (rs,n)->{
                    if(rs.getTimestamp("created_at")==null)return new DiscoveryMetrics(0,150,0,configuredPages,0,null);
                    Map<String,Object> stats=jsons.map(Objects.toString(rs.getString("filter_stats_text"),"{}"));
                    int detected=number(stats.get("detectedCount"),number(stats.get("runnerCandidates"),0));
                    int candidateLimit=number(stats.get("candidateLimit"),150);
                    int pages=number(stats.get("pagesCompleted"),stats.get("pages") instanceof List<?> list?list.size():0);
                    int pageLimit=number(stats.get("pageLimit"),configuredPages);
                    return new DiscoveryMetrics(detected,candidateLimit,pages,pageLimit,rs.getInt("progress"),rs.getTimestamp("created_at").toInstant());
                },oneStopId).stream().findFirst().orElse(new DiscoveryMetrics(0,150,0,configuredPages,0,null));
    }
    private static int number(Object value,int fallback){return value instanceof Number number?number.intValue():fallback;}
    private static int completedPageCount(Map<String,Object> stats){return stats.get("pages") instanceof List<?> pages?(int)pages.stream().filter(value->value instanceof Map<?,?> page&&!"interrupted".equals(Objects.toString(page.get("stopReason"),""))).count():0;}
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> maps(Object value){return value instanceof List<?> list?list.stream().filter(Map.class::isInstance).map(item->(Map<String,Object>)new LinkedHashMap<>((Map<String,Object>)item)).toList():List.of();}
    private static int groupNumber(int scheduled){return scheduled<=0?1:(scheduled-1)/5+1;}
    private int targetCount(String specText){
        Object value=jsons.map(specText).get("maxJobs");
        int count=value instanceof Number number?number.intValue():50;
        return Math.max(1,Math.min(150,count));
    }
}
