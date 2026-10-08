package com.careerlens.core.automation;

import com.careerlens.core.auth.AppPrincipal;
import com.careerlens.core.common.Jsons;
import com.careerlens.core.integration.RunnerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Durable phase machine. Network calls execute outside database transactions. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="careerlens.dispatcher.enabled",havingValue="true",matchIfMissing=true)
public class AutomationDispatcher {
 private final JdbcTemplate jdbc;
 private final AutomationService automation;
 private final RunnerClient runner;
 private final Jsons jsons;
 @Scheduled(fixedDelayString="${careerlens.dispatcher.automation-delay-ms:500}")
 public void tick(){
   List<UUID> due=jdbc.query("SELECT o.task_id FROM automation_outbox o JOIN automation_task t ON t.id=o.task_id WHERE o.phase NOT IN ('DONE','FAILED','UNKNOWN') AND (o.phase NOT LIKE 'WAITING%' OR t.task_status='awaiting_login' AND o.attempts<3) AND o.next_run_at<=CURRENT_TIMESTAMP AND (o.lease_until IS NULL OR o.lease_until<CURRENT_TIMESTAMP) ORDER BY o.next_run_at",
     (rs,n)->rs.getObject(1,UUID.class));
   for(UUID id:due.stream().limit(3).toList()){
    int claim=jdbc.update("UPDATE automation_outbox SET lease_until=? WHERE task_id=? AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)",
      Timestamp.from(Instant.now().plusSeconds(120)),id);
    if(claim==0)continue;
    try{process(id);}catch(Exception error){
      jdbc.update("UPDATE automation_outbox SET attempts=attempts+1,last_error=?,next_run_at=? WHERE task_id=?",
        error.getClass().getSimpleName(),Timestamp.from(Instant.now().plusSeconds(10)),id);
      Integer attempts=jdbc.queryForObject("SELECT attempts FROM automation_outbox WHERE task_id=?",Integer.class,id);
      if(attempts!=null&&attempts>=3){
        String phase=jdbc.queryForObject("SELECT phase FROM automation_outbox WHERE task_id=?",String.class,id);
        boolean uncertain=phase!=null&&phase.startsWith("SUBMIT");
        var row=jdbc.queryForMap("SELECT user_id FROM automation_outbox WHERE task_id=?",id);
        automation.recordExecution(UUID.fromString(row.get("user_id").toString()),id,Map.of("status",uncertain?"unknown_outcome":"failed","errorMessage","后台任务未完成，请检查执行器、审批或服务配置"));
        phase(id,uncertain?"UNKNOWN":"FAILED",null,null);
      }
    }finally{
      SecurityContextHolder.clearContext();
      jdbc.update("UPDATE automation_outbox SET lease_until=NULL WHERE task_id=?",id);
    }
   }
 }
 @SuppressWarnings("unchecked")
 private void process(UUID id){
   var row=jdbc.queryForMap("SELECT * FROM automation_outbox WHERE task_id=?",id);
   UUID owner=UUID.fromString(row.get("user_id").toString());
   String status=jdbc.queryForObject("SELECT task_status FROM automation_task WHERE id=?",String.class,id);
   if(Set.of("cancelled","succeeded").contains(status)){phase(id,"DONE",null,null);return;}
   String active=jdbc.queryForObject("SELECT status FROM app_user WHERE id=?",String.class,owner);
   if(!"ACTIVE".equals(active)){phase(id,"FAILED",null,null);return;}
   SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
     new AppPrincipal(owner,"","","USER"),null,List.of()));
   Integer attempt=jdbc.queryForObject("SELECT attempt FROM automation_task WHERE id=?",Integer.class,id);
   UUID identity=jdbc.queryForObject("SELECT platform_identity_id FROM automation_task WHERE id=?",UUID.class,id);
   String stage=row.get("phase").toString();
   Boolean paused=identity==null?false:jdbc.query("SELECT paused FROM platform_execution_policy WHERE user_id=? AND platform_identity_id=?",(rs,n)->rs.getBoolean(1),owner,identity).stream().findFirst().orElse(false);
   Boolean dryRun=identity==null?false:jdbc.query("SELECT dry_run FROM platform_execution_policy WHERE user_id=? AND platform_identity_id=?",(rs,n)->rs.getBoolean(1),owner,identity).stream().findFirst().orElse(false);
   if(paused&&Set.of("NEW","PREPARE_SEND","SIGN","SUBMIT_SEND","RESOLVE_PREPARE","RESOLVE_SUBMIT").contains(stage))return;
   Map<String,Object> payload=jsons.map(row.get("payload").toString());
   String runnerId=Objects.toString(row.get("runner_id"),"");
   if(stage.startsWith("WAITING_")){
     if(!"awaiting_login".equals(status)||runnerId.isBlank())return;
     Map<String,Object> response=runner.autoResolveHumanAction(runnerId);
     if(runner.unavailable(response)){scheduleLoginRecheck(id,false);return;}
     Map<String,Object> result=runner.data(response);
     String outcome=Objects.toString(result.get("outcome"),"unknown");
     if("resolved".equals(outcome)){
       if(result.get("task") instanceof Map<?,?> taskMap)automation.recordExecution(owner,id,(Map<String,Object>)(Map<?,?>)taskMap);
       phase(id,stage.substring(8)+"_POLL",payload,runnerId);
       resumePausedOneStop(owner,identity);
     }else scheduleLoginRecheck(id,"required".equals(outcome));
     return;
   }
   if("NEW".equals(stage)){
     var health=runner.status();
     if(!Boolean.TRUE.equals(health.get("online")) || !"real".equals(((Map<?,?>)health.getOrDefault("details",Map.of())).get("mode")))
       throw new IllegalStateException("Real paired runner required");
     payload=new LinkedHashMap<>(Map.of("plan",automation.executionInput(owner,id),"scenario","happy_path","commandId",id+"-prepare-"+attempt));
     phase(id,"PREPARE_SEND",payload,null);return;
   }
   if(stage.endsWith("_SEND")){
     if("SUBMIT_SEND".equals(stage))automation.executionInput(owner,id);
     Map<String,Object> accepted=switch(stage){
       case "PREPARE_SEND" -> runner.prepare(payload);
       case "SUBMIT_SEND" -> runner.submit(payload);
       case "RECONCILE_SEND" -> runner.reconcile(payload);
       default -> throw new IllegalStateException("Unexpected phase");
     };
     if(runner.unavailable(accepted)){
       if("SUBMIT_SEND".equals(stage)){
         automation.recordExecution(owner,id,Map.of("status","unknown_outcome","errorMessage","提交响应未知，请核对"));
         phase(id,"UNKNOWN",payload,null);return;
       }
       throw new IllegalStateException("Runner did not accept command");
     }
     Map<String,Object> task=runner.data(accepted);
     if(!task.containsKey("id"))throw new IllegalStateException("Missing Runner task");
     phase(id,stage.replace("_SEND","_POLL"),payload,task.get("id").toString());
     automation.recordExecution(owner,id,task);return;
   }
   if(stage.startsWith("RESOLVE_")){
     var response=runner.resolveHumanAction(runnerId);
     if(runner.unavailable(response))throw new IllegalStateException("Human action unresolved");
     phase(id,stage.substring(8)+"_POLL",payload,runnerId);resumePausedOneStop(owner,identity);return;
   }
   if("SIGN".equals(stage)){
     automation.executionInput(owner,id);
     var response=runner.approve(payload.get("plan"));
     if(runner.unavailable(response))throw new IllegalStateException("Approval unavailable");
     var proof=runner.data(response);
     if(!proof.containsKey("signature"))throw new IllegalStateException("Invalid proof");
     payload=new LinkedHashMap<>(payload);payload.put("approval",proof);payload.put("commandId",id+"-submit-"+attempt);
     phase(id,"SUBMIT_SEND",payload,null);return;
   }
   Map<String,Object> response=runner.task(runnerId);
   if(runner.unavailable(response))throw new IllegalStateException("Runner poll unavailable");
   Map<String,Object> task=runner.data(response);
   automation.recordExecution(owner,id,task);
   String taskStatus=Objects.toString(task.get("status"),"");
   if("waiting_human".equals(taskStatus)){phase(id,"WAITING_"+(stage.startsWith("PREPARE")?"PREPARE":"SUBMIT"),payload,runnerId);return;}
   if("unknown_outcome".equals(taskStatus)){phase(id,"UNKNOWN",payload,runnerId);return;}
   if(Set.of("failed","cancelled").contains(taskStatus)){phase(id,"FAILED",payload,runnerId);return;}
   if("succeeded".equals(taskStatus)){
    if(stage.startsWith("PREPARE")){
       if(Boolean.TRUE.equals(dryRun)){automation.recordDryRun(owner,id);phase(id,"DONE",payload,runnerId);return;}
       payload=new LinkedHashMap<>(payload);
       Map<String,Object> plan=new LinkedHashMap<>((Map<String,Object>)payload.get("plan"));
       Map<String,Object> result=(Map<String,Object>)task.getOrDefault("result",Map.of());
       if(result.get("pageFingerprint")!=null)plan.put("pageFingerprint",result.get("pageFingerprint"));
       payload.put("plan",plan);phase(id,"SIGN",payload,null);
     }else {
       String current=jdbc.queryForObject("SELECT task_status FROM automation_task WHERE id=?",String.class,id);
       phase(id,"succeeded".equals(current)?"DONE":"UNKNOWN",payload,runnerId);
     }
   }
 }

 private void scheduleLoginRecheck(UUID id,boolean explicitlyRequired){
   Integer attempts=jdbc.queryForObject("SELECT attempts FROM automation_outbox WHERE task_id=?",Integer.class,id);
   int next=explicitlyRequired?3:Math.min(3,(attempts==null?0:attempts)+1);
   jdbc.update("UPDATE automation_outbox SET attempts=?,next_run_at=? WHERE task_id=?",next,Timestamp.from(Instant.now().plusSeconds(next==1?3:next==2?10:30)),id);
 }

 private void resumePausedOneStop(UUID owner,UUID identity){
   jdbc.update("UPDATE one_stop_run SET status='running',last_error=NULL,next_run_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND (platform_identity_id=? OR platform_identity_id IS NULL AND ? IS NULL) AND status='paused' AND phase='WAIT_GROUP'",owner,identity,identity);
 }
 private void phase(UUID id,String phase,Map<String,Object> payload,String runnerId){
   if(payload==null)jdbc.update("UPDATE automation_outbox SET phase=?,next_run_at=CURRENT_TIMESTAMP WHERE task_id=?",phase,id);
   else jdbc.update("UPDATE automation_outbox SET phase=?,payload=?,runner_id=?,attempts=0,next_run_at=CURRENT_TIMESTAMP WHERE task_id=?",
      phase,jsons.write(payload),runnerId,id);
 }
}
