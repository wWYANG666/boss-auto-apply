package com.careerlens.core.automation;

import com.careerlens.core.common.Jsons;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="careerlens.dispatcher.enabled",havingValue="true",matchIfMissing=true)
public class OneStopDispatcher {
    static final int ACTIVE_WINDOW_SIZE=2;
    enum HumanBlockerScope { TASK, ACCOUNT }
    private final JdbcTemplate jdbc;
    private final Jsons jsons;
    private final AutomationService automation;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverTransientDiscoveryAfterRestart(){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,discovery_run_id,last_error FROM one_stop_run WHERE status='paused' AND phase='WAIT_DISCOVERY' AND stop_requested=FALSE");
        for(Map<String,Object> row:rows){
            String detail=Objects.toString(row.get("last_error"),"");
            if(!isTransientDiscoveryFailure(detail)||row.get("discovery_run_id")==null)continue;
            UUID id=UUID.fromString(row.get("id").toString()),discoveryId=UUID.fromString(row.get("discovery_run_id").toString());
            Map<String,Object> discovery=jdbc.queryForMap("SELECT status,discovered_count,filter_stats_text FROM discovery_run WHERE id=?",discoveryId);
            String status=Objects.toString(discovery.get("status"),"");
            Map<String,Object> stats=jsons.map(Objects.toString(discovery.get("filter_stats_text"),"{}"));
            boolean hasCheckpoint=number(discovery.get("discovered_count"),0)>0&&stats.get("pendingScopes") instanceof List<?> pending&&!pending.isEmpty();
            if("failed".equals(status)&&hasCheckpoint){
                jdbc.update("UPDATE discovery_run SET status='partial',updated_at=CURRENT_TIMESTAMP WHERE id=?",discoveryId);
                jdbc.update("UPDATE discovery_command SET status='PARTIAL' WHERE id=(SELECT id FROM discovery_command WHERE run_id=? AND status='FAILED' ORDER BY next_attempt_at DESC LIMIT 1)",discoveryId);
            }else if("failed".equals(status)&&!hasCheckpoint){
                jdbc.update("UPDATE one_stop_run SET phase='DISCOVER',discovery_run_id=NULL WHERE id=?",id);
            }else if(!"partial".equals(status))continue;
            jdbc.update("UPDATE one_stop_run SET status='running',transient_discovery_failures=0,last_error='服务重启后已自动恢复岗位发现，将从已保存检查点继续',next_run_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        }
    }

    @Scheduled(fixedDelayString="${careerlens.dispatcher.one-stop-delay-ms:750}")
    public void tick(){
        List<UUID> due=jdbc.query("SELECT id FROM one_stop_run WHERE status='running' AND stop_requested=FALSE AND next_run_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) ORDER BY next_run_at",
                (rs,n)->rs.getObject(1,UUID.class));
        for(UUID id:due.stream().limit(2).toList()){
            if(jdbc.update("UPDATE one_stop_run SET lease_until=? WHERE id=? AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)",Timestamp.from(Instant.now().plusSeconds(60)),id)==0)continue;
            try{process(id);}catch(Exception error){
                String message=Objects.toString(error.getMessage(),error.getClass().getSimpleName());
                if(message.length()>500)message=message.substring(0,500);
                jdbc.update("UPDATE one_stop_run SET status='paused',last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",message,id);
            }finally{jdbc.update("UPDATE one_stop_run SET lease_until=NULL WHERE id=?",id);}
        }
    }

    private void process(UUID id){
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM one_stop_run WHERE id=?",id);
        UUID user=uuid(row,"user_id"),resume=uuid(row,"resume_version_id");
        UUID identity=row.get("platform_identity_id")==null?null:uuid(row,"platform_identity_id");
        String phase=row.get("phase").toString();
        if("DISCOVER".equals(phase)){
            Set<String> platforms=strings(row.get("platforms_text"));
            List<UUID> accountIds=automation.platforms(user).stream().filter(item->platforms.contains(item.platform()))
                    .filter(item->"connected".equals(item.status())).map(AutomationService.PlatformView::id).toList();
            Map<String,Object> discoverySpec=new LinkedHashMap<>(jsons.map(row.get("spec_text").toString()));
            // One-stop follows the user's saved discovery settings instead of silently
            // applying a smaller page/job budget than manual discovery.
            discoverySpec.put("maxJobs",Math.max(1,Math.min(150,number(discoverySpec.get("maxJobs"),50))));
            discoverySpec.put("maxPages",Math.max(1,Math.min(10,number(discoverySpec.get("maxPages"),2))));
            discoverySpec.put("maxTotalPages",Math.max(1,Math.min(100,number(discoverySpec.get("maxTotalPages"),30))));
            discoverySpec.put("bypassCache",true);
            AutomationService.DiscoveryView discovery=automation.enqueueDiscovery(user,accountIds,resume,discoverySpec,"one_stop");
            jdbc.update("UPDATE one_stop_run SET phase='WAIT_DISCOVERY',discovery_run_id=?,job_ids_text='[]',task_ids_text='[]',group_offset=0,next_run_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    discovery.id(),Timestamp.from(Instant.now().plusSeconds(2)),id);return;
        }
        if("WAIT_DISCOVERY".equals(phase)){
            UUID discoveryId=uuid(row,"discovery_run_id");
            AutomationService.DiscoveryView discovery=automation.discovery(user,discoveryId);
            // DiscoveryDispatcher writes terminal state with JDBC. Read that control state
            // directly so a JPA persistence context cannot keep one-stop waiting on a stale row.
            String discoveryStatus=jdbc.queryForObject("SELECT status FROM discovery_run WHERE id=?",String.class,discoveryId);
            if(Set.of("queued","running","searching").contains(discoveryStatus)){later(id,2);return;}
            if("partial".equals(discoveryStatus)){
                String detail=boundedError(Objects.toString(discovery.errorMessage(),"职位发现部分完成，已保留结果；请处理登录或验证后继续。"));
                if(isTransientDiscoveryFailure(detail)){
                    int failures=number(row.get("transient_discovery_failures"),0)+1;
                    if(automation.resumePartialDiscovery(user,discoveryId)){
                        jdbc.update("UPDATE one_stop_run SET transient_discovery_failures=?,last_error=?,next_run_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                                failures,boundedError("页面导航短暂中断，已保留结果；后台将在"+transientDiscoveryDelaySeconds(failures)+"秒后从断点自动继续"),
                                Timestamp.from(Instant.now().plusSeconds(transientDiscoveryDelaySeconds(failures))),id);
                        return;
                    }
                }
                jdbc.update("UPDATE one_stop_run SET status='paused',last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",detail,id);
                return;
            }
            if(!"completed".equals(discoveryStatus)){
                String detail=boundedError(Objects.toString(discovery.errorMessage(),"职位发现未完成"));
                if(isTransientDiscoveryFailure(detail)){
                    int failures=number(row.get("transient_discovery_failures"),0)+1;
                    jdbc.update("UPDATE one_stop_run SET phase='DISCOVER',discovery_run_id=NULL,transient_discovery_failures=?,last_error=?,next_run_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                            failures,boundedError("浏览器页面短暂不可用，后台将在"+transientDiscoveryDelaySeconds(failures)+"秒后自动重新发现岗位"),
                            Timestamp.from(Instant.now().plusSeconds(transientDiscoveryDelaySeconds(failures))),id);
                    return;
                }
                throw new IllegalStateException(detail);
            }
            Map<String,Object> spec=jsons.map(row.get("spec_text").toString());
            int minimum=number(spec.get("minimumScore"),70);
            Set<String> processed=new HashSet<>(jdbc.query("SELECT platform||':'||external_job_id FROM one_stop_processed_job WHERE run_id=?",(rs,n)->rs.getString(1),id));
            Set<String> unresolved=new HashSet<>(jdbc.query("SELECT platform||':'||external_job_id FROM automation_task WHERE user_id=? AND platform_identity_id=? AND task_status IN ('queued','preparing','submitting','verifying','awaiting_login','awaiting_captcha','awaiting_question','unknown_outcome')",
                    (rs,n)->rs.getString(1),user,identity));
            int selectionLimit=Math.max(0,targetCount(row)-number(row.get("processed_count"),0));
            Set<String> companies=new HashSet<>();List<AutomationService.JobView> chosen=new ArrayList<>();
            for(AutomationService.JobView job:automation.jobs(user,discoveryId)){
                if(chosen.size()>=selectionLimit)break;
                String externalKey=job.platform()+":"+job.externalJobId();
                if(job.alreadyTracked()||job.contacted()||processed.contains(externalKey)||unresolved.contains(externalKey)||job.matchScore()<minimum||!job.hardConflicts().isEmpty())continue;
                String company=job.platform()+":"+job.company().toLowerCase(Locale.ROOT).replaceAll("[\\s·•・,，.。()（）【】\\[\\]{}<>《》_-]","");
                if("boss".equals(job.platform())&&!companies.add(company))continue;
                chosen.add(job);
            }
            if(chosen.isEmpty()){
                int emptyCount=number(row.get("empty_discovery_count"),0)+1;
                AutomationService.DiscoveryFilterStats stats=automation.discoveryFilterStats(user,discoveryId);
                String reason=emptyDiscoveryReason(discovery,stats);
                if(emptyCount>=2){complete(id,"重新搜索后"+reason);return;}
                int waitSeconds=adaptiveEmptyDelaySeconds(emptyCount,discovery.rawCount(),discovery.deduplicatedCount());
                jdbc.update("UPDATE one_stop_run SET phase='DISCOVER',discovery_run_id=NULL,discovery_round=discovery_round+1,empty_discovery_count=?,transient_discovery_failures=0,last_error=?,next_run_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        emptyCount,boundedError(reason+"；下一轮将重新请求平台"),Timestamp.from(Instant.now().plusSeconds(waitSeconds)),id);return;
            }
            jdbc.update("UPDATE one_stop_run SET phase='APPROVE_GROUP',job_ids_text=?,group_offset=0,discovery_round=discovery_round+1,empty_discovery_count=0,transient_discovery_failures=0,last_error=NULL,next_run_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    jsons.write(chosen.stream().map(job->job.id().toString()).toList()),id);return;
        }
        if("APPROVE_GROUP".equals(phase)){
            List<UUID> jobs=uuids(row.get("job_ids_text"));int offset=number(row.get("group_offset"),0);
            List<UUID> active=new ArrayList<>(uuids(row.get("task_ids_text")));
            int target=targetCount(row),processed=number(row.get("processed_count"),0);
            if(active.isEmpty()&&processed>=target){complete(id,"已达到本次处理目标");return;}
            AutomationService.DailyQuota quota=automation.dailyQuota(user,identity);
            if(!quota.dryRun()&&quota.remaining()<=0&&active.isEmpty()){
                complete(id,"已达到今日本地执行上限（"+quota.used()+"/"+quota.dailyLimit()+"），BOSS平台硬上限为150");return;
            }
            int remainingQuota=quota.dryRun()?Integer.MAX_VALUE:quota.remaining();
            int capacity=Math.min(Math.min(Math.max(0,ACTIVE_WINDOW_SIZE-active.size()),Math.max(0,target-processed-active.size())),remainingQuota);
            if(capacity==0){jdbc.update("UPDATE one_stop_run SET phase='WAIT_GROUP',next_run_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",Timestamp.from(Instant.now().plusSeconds(2)),id);return;}
            List<UUID> group=jobs.stream().skip(offset).limit(capacity).toList();
            if(group.isEmpty()){
                if(active.isEmpty()&&processed>=target)complete(id,"已达到本次处理目标");
                else if(active.isEmpty())nextDiscovery(id);
                else jdbc.update("UPDATE one_stop_run SET phase='WAIT_GROUP',next_run_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",Timestamp.from(Instant.now().plusSeconds(2)),id);
                return;
            }
            List<AutomationService.PlanView> plans=automation.createPlans(user,uuid(row,"discovery_run_id"),group,resume);
            AutomationService.ApprovalResult approval=automation.approve(user,plans.stream().map(AutomationService.PlanView::id).toList(),true,false);
            active.addAll(approval.tasks().stream().map(AutomationService.TaskView::id).toList());
            jdbc.update("UPDATE one_stop_run SET phase='WAIT_GROUP',task_ids_text=?,group_offset=?,next_run_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    jsons.write(active.stream().map(UUID::toString).toList()),offset+group.size(),Timestamp.from(Instant.now().plusSeconds(2)),id);return;
        }
        if("WAIT_GROUP".equals(phase)){
            List<UUID> activeIds=new ArrayList<>(uuids(row.get("task_ids_text")));
            Set<UUID> ids=new HashSet<>(activeIds);
            List<AutomationService.TaskView> tasks=automation.tasks(user,ids);
            if(tasks.size()!=ids.size()){
                Set<UUID> existing=tasks.stream().map(AutomationService.TaskView::id).collect(java.util.stream.Collectors.toSet());
                activeIds.removeIf(taskId->!existing.contains(taskId));
            }
            if(tasks.stream().anyMatch(OneStopDispatcher::isDailyLimit)){
                terminateAtDailyLimit(id,tasks);
                return;
            }
            List<AutomationService.TaskView> human=tasks.stream().filter(task->task.status().startsWith("awaiting_")).toList();
            List<AutomationService.TaskView> accountHuman=human.stream().filter(task->humanBlockerScope(task)==HumanBlockerScope.ACCOUNT).toList();
            List<AutomationService.TaskView> taskHuman=human.stream().filter(task->humanBlockerScope(task)==HumanBlockerScope.TASK).toList();
            Map<String,Long> repeatedTaskBlockers=taskHuman.stream().collect(java.util.stream.Collectors.groupingBy(
                    OneStopDispatcher::humanBlockerSignature,java.util.stream.Collectors.counting()));
            Optional<AutomationService.TaskView> repeated=taskHuman.stream()
                    .filter(task->repeatedTaskBlockers.getOrDefault(humanBlockerSignature(task),0L)>=2).findFirst();
            if(!accountHuman.isEmpty()||repeated.isPresent()){
                Long pending=jdbc.queryForObject("SELECT COUNT(*) FROM automation_task WHERE user_id=? AND platform_identity_id=? AND task_status LIKE 'awaiting_%'",Long.class,user,identity);
                jdbc.update("UPDATE one_stop_run SET attention_count=? WHERE id=?",pending==null?human.size():pending,id);
                AutomationService.TaskView blocker=!accountHuman.isEmpty()?accountHuman.get(0):repeated.orElseThrow();
                throw new IllegalStateException(Objects.toString(blocker.humanAction(),"BOSS账号需要人工处理"));
            }
            Set<String> terminal=Set.of("succeeded","failed","cancelled","dry_run","page_changed");
            List<AutomationService.TaskView> finalized=new ArrayList<>(tasks.stream().filter(task->terminal.contains(task.status())).toList());
            Map<String,Object> reconcileState=new LinkedHashMap<>(jsons.map(Objects.toString(row.get("reconcile_state_text"),"{}")));
            boolean reconciliationQueued=false;
            for(AutomationService.TaskView task:tasks.stream().filter(item->"unknown_outcome".equals(item.status())).toList()){
                int attempts=number(reconcileState.get(task.id().toString()),0);
                if(attempts>=3){finalized.add(task);continue;}
                automation.queueReconcile(user,task.id());
                reconcileState.put(task.id().toString(),attempts+1);
                reconciliationQueued=true;
            }
            int succeeded=(int)finalized.stream().filter(task->"succeeded".equals(task.status())).count();
            int failed=(int)finalized.stream().filter(task->Set.of("failed","page_changed").contains(task.status())).count();
            int cancelled=(int)finalized.stream().filter(task->Set.of("cancelled","dry_run").contains(task.status())).count();
            int attention=(int)finalized.stream().filter(task->"unknown_outcome".equals(task.status())).count()+taskHuman.size();
            for(AutomationService.TaskView task:finalized){
                Long exists=jdbc.queryForObject("SELECT COUNT(*) FROM one_stop_processed_job WHERE run_id=? AND platform=? AND external_job_id=?",Long.class,id,task.platform(),task.externalJobId());
                if(exists==null||exists==0)jdbc.update("INSERT INTO one_stop_processed_job(run_id,platform,external_job_id,outcome) VALUES (?,?,?,?)",id,task.platform(),task.externalJobId(),task.status());
                activeIds.remove(task.id());
                reconcileState.remove(task.id().toString());
            }
            for(AutomationService.TaskView task:taskHuman){
                Long exists=jdbc.queryForObject("SELECT COUNT(*) FROM one_stop_processed_job WHERE run_id=? AND platform=? AND external_job_id=?",Long.class,id,task.platform(),task.externalJobId());
                if(exists==null||exists==0)jdbc.update("INSERT INTO one_stop_processed_job(run_id,platform,external_job_id,outcome) VALUES (?,?,?,'deferred_human')",id,task.platform(),task.externalJobId());
                activeIds.remove(task.id());
                reconcileState.remove(task.id().toString());
            }
            List<UUID> allJobs=uuids(row.get("job_ids_text"));
            int offset=number(row.get("group_offset"),0);
            int processedAfter=number(row.get("processed_count"),0)+finalized.size()+taskHuman.size();
            int target=targetCount(row);
            boolean targetFilled=processedAfter+activeIds.size()>=target;
            String nextPhase=targetFilled?(activeIds.isEmpty()?"COMPLETED":"WAIT_GROUP")
                    :activeIds.size()<ACTIVE_WINDOW_SIZE&&offset<allJobs.size()?"APPROVE_GROUP":activeIds.isEmpty()?"DISCOVER":"WAIT_GROUP";
            boolean nextDiscovery="DISCOVER".equals(nextPhase);
            int waitSeconds="WAIT_GROUP".equals(nextPhase)?reconciliationQueued?10:3:0;
            jdbc.update("UPDATE one_stop_run SET status=?,phase=?,discovery_run_id=?,job_ids_text=?,task_ids_text=?,group_offset=?,reconcile_state_text=?,processed_count=processed_count+?,succeeded_count=succeeded_count+?,failed_count=failed_count+?,cancelled_count=cancelled_count+?,attention_count=attention_count+?,last_error=?,next_run_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    "COMPLETED".equals(nextPhase)?"completed":"running",nextPhase,nextDiscovery?null:row.get("discovery_run_id"),nextDiscovery?"[]":row.get("job_ids_text"),
                    jsons.write(activeIds.stream().map(UUID::toString).toList()),nextDiscovery?0:offset,jsons.write(reconcileState),finalized.size()+taskHuman.size(),
                    succeeded,failed,cancelled,attention,boundedError("COMPLETED".equals(nextPhase)?"已达到本次处理目标":Objects.toString(row.get("last_error"),null)),Timestamp.from(Instant.now().plusSeconds(waitSeconds)),id);
        }
    }

    static HumanBlockerScope humanBlockerScope(AutomationService.TaskView task){
        if(Set.of("awaiting_login","awaiting_captcha").contains(task.status()))return HumanBlockerScope.ACCOUNT;
        String value=(Objects.toString(task.currentStep(),"")+" "+Objects.toString(task.humanAction(),"")).toUpperCase(Locale.ROOT);
        String[] accountMarkers={"BOSS_ACCOUNT_","BOSS_JOB_API_36","BOSS_JOB_API_37","BOSS_CONTACT_RATE_LIMITED",
                "BOSS_DAILY_CONTACT_LIMIT","BOSS_JOB_REDIRECTED_HOME","BOSS_PAGE_NAVIGATION_UNSTABLE","BOSS_BROWSER_",
                "PLATFORM_LOGIN_REQUIRED","LOGIN_REQUIRED","CAPTCHA_REQUIRED","验证码","滑块","安全验证","操作过于频繁","沟通上限"};
        return Arrays.stream(accountMarkers).anyMatch(value::contains)?HumanBlockerScope.ACCOUNT:HumanBlockerScope.TASK;
    }

    static String humanBlockerSignature(AutomationService.TaskView task){
        String value=Objects.toString(task.humanAction(),Objects.toString(task.currentStep(),"awaiting_question")).trim();
        int separator=value.indexOf(':');
        return (separator>0?value.substring(0,separator):value).toUpperCase(Locale.ROOT);
    }

    static boolean isDailyLimit(AutomationService.TaskView task){
        String value=(Objects.toString(task.currentStep(),"")+" "+Objects.toString(task.humanAction(),"")).toUpperCase(Locale.ROOT);
        return value.contains("BOSS_DAILY_CONTACT_LIMIT")||value.contains("DAILY_QUOTA_REACHED");
    }

    static boolean isTransientDiscoveryFailure(String detail){
        String value=Objects.toString(detail,"").toUpperCase(Locale.ROOT);
        return value.contains("BOSS_PAGE_NAVIGATION_UNSTABLE")||value.contains("BOSS_DISCOVERY_PAGE_NOT_STABLE")
                ||value.contains("EXECUTION CONTEXT WAS DESTROYED")||value.contains("TARGET.CREATETARGET")
                ||value.contains("FAILED TO OPEN A NEW TAB")||value.contains("BROWSERCONTEXT.NEWPAGE");
    }

    static int transientDiscoveryDelaySeconds(int failures){return Math.min(60,Math.max(1,failures)*15);}

    static int adaptiveEmptyDelaySeconds(int emptyCount,int rawCount,int uniqueCount){
        int attempt=Math.max(1,emptyCount);
        if(rawCount>0&&uniqueCount==0)return Math.min(300,30*(1<<Math.min(3,attempt-1)));
        if(rawCount>0&&uniqueCount>0)return Math.min(300,60*(1<<Math.min(3,attempt-1)));
        return Math.min(1800,300*(1<<Math.min(3,attempt-1)));
    }

    static String emptyDiscoveryReason(AutomationService.DiscoveryView discovery,AutomationService.DiscoveryFilterStats stats){
        if(stats.platformReturned()>0&&stats.runnerReturned()==0)
            return"平台返回"+stats.platformReturned()+"个候选，但全部未通过当前筛选";
        if(discovery.rawCount()==0)return"平台没有返回岗位";
        if(discovery.deduplicatedCount()==0)return"平台返回"+discovery.rawCount()+"个岗位，全部是历史重复";
        if(stats.linkedJobs()==0)return"平台返回了岗位，但没有可用的本地记录";
        if(stats.alreadyTracked()==stats.linkedJobs())return"找到"+stats.linkedJobs()+"个岗位，但都已跟进";
        if(stats.eligible()==0)return"找到"+stats.linkedJobs()+"个岗位，均未通过当前条件";
        return"找到"+stats.linkedJobs()+"个岗位，但都已在本轮处理或属于同一公司";
    }

    private void later(UUID id,int seconds){jdbc.update("UPDATE one_stop_run SET next_run_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",Timestamp.from(Instant.now().plusSeconds(seconds)),id);}
    private void nextDiscovery(UUID id){jdbc.update("UPDATE one_stop_run SET phase='DISCOVER',discovery_run_id=NULL,job_ids_text='[]',task_ids_text='[]',group_offset=0,next_run_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);}
    private void complete(UUID id,String detail){jdbc.update("UPDATE one_stop_run SET status='completed',phase='COMPLETED',task_ids_text='[]',last_error=?,next_run_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",boundedError(detail),id);}
    static String boundedError(String value){
        if(value==null)return null;
        String normalized=value.replaceAll("\\s+"," ").trim();
        return normalized.length()<=500?normalized:normalized.substring(0,497)+"...";
    }
    private void terminateAtDailyLimit(UUID id,List<AutomationService.TaskView> tasks){
        int processed=0,failed=0,cancelled=0;
        for(AutomationService.TaskView task:tasks){
            if(Set.of("submitting","verifying","unknown_outcome").contains(task.status()))continue;
            boolean quotaTask=isDailyLimit(task);
            String status=quotaTask?"failed":Set.of("succeeded","failed","cancelled","dry_run","page_changed").contains(task.status())?task.status():"cancelled";
            String step=quotaTask?"BOSS每日沟通已达上限，今日任务已终止":"BOSS今日沟通额度已用完，剩余任务已取消";
            jdbc.update("UPDATE automation_task SET task_status=?,progress=100,current_step=?,human_action_text=NULL,failure_category=?,retryable=FALSE,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    status,step,quotaTask?"PLATFORM_LIMITED":null,task.id());
            jdbc.update("UPDATE automation_outbox SET phase=? WHERE task_id=?",quotaTask?"FAILED":"DONE",task.id());
            Long exists=jdbc.queryForObject("SELECT COUNT(*) FROM one_stop_processed_job WHERE run_id=? AND platform=? AND external_job_id=?",Long.class,id,task.platform(),task.externalJobId());
            if(exists==null||exists==0){
                jdbc.update("INSERT INTO one_stop_processed_job(run_id,platform,external_job_id,outcome) VALUES (?,?,?,?)",id,task.platform(),task.externalJobId(),quotaTask?"daily_limit":status);
                processed++;
                if("failed".equals(status)||"page_changed".equals(status))failed++;
                if("cancelled".equals(status)||"dry_run".equals(status))cancelled++;
            }
        }
        jdbc.update("UPDATE one_stop_run SET status='completed',phase='DAILY_LIMIT_REACHED',task_ids_text='[]',processed_count=processed_count+?,failed_count=failed_count+?,cancelled_count=cancelled_count+?,attention_count=0,last_error='BOSS今日最多沟通150位，已自动终止本次任务；明天可重新开始',next_run_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                processed,failed,cancelled,id);
    }
    private int targetCount(Map<String,Object> row){return Math.max(1,Math.min(150,number(jsons.map(row.get("spec_text").toString()).get("maxJobs"),50)));}
    private UUID uuid(Map<String,Object> row,String key){return UUID.fromString(row.get(key).toString());}
    private int number(Object value,int fallback){return value instanceof Number number?number.intValue():fallback;}
    private Set<String> strings(Object json){Set<String> result=new LinkedHashSet<>();for(Object value:jsons.list(Objects.toString(json,"[]")))result.add(value.toString());return result;}
    private List<UUID> uuids(Object json){return jsons.list(Objects.toString(json,"[]")).stream().map(value->UUID.fromString(value.toString())).toList();}
}
