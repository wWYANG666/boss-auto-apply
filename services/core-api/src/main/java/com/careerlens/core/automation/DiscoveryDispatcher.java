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
import java.time.Instant;
import java.util.*;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="careerlens.dispatcher.enabled",havingValue="true",matchIfMissing=true)
public class DiscoveryDispatcher {
 private final JdbcTemplate jdbc;
 private final RunnerClient runner;
 private final AutomationService automation;
 private final Jsons jsons;
 @Scheduled(fixedDelayString="${careerlens.dispatcher.discovery-delay-ms:750}")
 @SuppressWarnings("unchecked")
 public void tick(){
   var rows=jdbc.queryForList("SELECT * FROM discovery_command WHERE status IN ('NEW','POLL') AND cancel_requested=FALSE AND next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)");
   for(var row:rows.stream().limit(2).toList()){
     UUID id=UUID.fromString(row.get("id").toString()),user=UUID.fromString(row.get("user_id").toString()),run=UUID.fromString(row.get("run_id").toString());
     if(jdbc.update("UPDATE discovery_command SET lease_until=? WHERE id=? AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)",
         java.sql.Timestamp.from(Instant.now().plusSeconds(300)),id)==0)continue;
     try{
       if(!"ACTIVE".equals(jdbc.queryForObject("SELECT status FROM app_user WHERE id=?",String.class,user)))throw new IllegalStateException("Account inactive");
       SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new AppPrincipal(user,"","","USER"),null,List.of()));
       if("NEW".equals(row.get("status"))){
         var accepted=runner.discover(jsons.map(row.get("payload").toString()));
         if(runner.unavailable(accepted)){
             if("RUNNER_REJECTED".equals(Objects.toString(accepted.get("error"),""))){
                 jdbc.update("UPDATE discovery_command SET status='FAILED',last_error='职位发现参数被Runner拒绝，请检查筛选范围' WHERE id=?",id);
                 continue;
             }
             String commandId=jsons.map(row.get("payload").toString()).getOrDefault("commandId",id.toString()).toString();
             var existing=runner.taskByCommand(commandId);
             if(!runner.unavailable(existing)&&runner.data(existing).get("id")!=null){
                 var task=runner.data(existing);jdbc.update("UPDATE discovery_command SET status='POLL',runner_id=?,last_error=NULL,next_attempt_at=CURRENT_TIMESTAMP WHERE id=?",task.get("id").toString(),id);
             }else {retryOrFail(id,"Runner暂时不可用");}
             continue;
         }
         var task=runner.data(accepted);
         if(task.get("id")==null)throw new IllegalStateException("Runner task missing");
         jdbc.update("UPDATE discovery_command SET status='POLL',runner_id=? WHERE id=?",task.get("id").toString(),id);
       }else{
         var task=runner.data(runner.task(row.get("runner_id").toString()));
         String status=Objects.toString(task.get("status"),"");
         if("running".equals(status)&&task.get("metadata") instanceof Map<?,?> metadata)
           automation.attachDiscoveryProgress(user,run,task.get("progress") instanceof Number number?number.intValue():15,(Map<String,Object>)(Map<?,?>)metadata);
         if(Set.of("succeeded","partial").contains(status)){
           if(!(task.get("result") instanceof List<?> jobs))throw new IllegalStateException("Invalid discovery result");
           automation.attachDiscoveryJobs(user,run,UUID.fromString(row.get("resume_version_id").toString()),row.get("platform").toString(),
             (List<Map<String,Object>>)(List<?>)jobs);
           String partialDetail=null;
           if(task.get("metadata") instanceof Map<?,?> metadata){
             automation.attachDiscoveryFilterStats(user,run,(Map<String,Object>)(Map<?,?>)metadata);
             if(metadata.get("interruption") instanceof Map<?,?> interruption)partialDetail=Objects.toString(interruption.get("message"),null);
           }
           jdbc.update("UPDATE discovery_command SET status=?,last_error=? WHERE id=?","partial".equals(status)?"PARTIAL":"DONE",partialDetail,id);
           if("succeeded".equals(status)&&task.get("metadata") instanceof Map<?,?> metadata)
             automation.continueDiscovery(user,run,id,(Map<String,Object>)(Map<?,?>)metadata);
         }else if(Set.of("failed","waiting_human","unknown_outcome","cancelled").contains(status)){
           String detail=Objects.toString(task.get("errorMessage"),"");
           if(detail.isBlank() && task.get("humanAction") instanceof Map<?,?> action)
             detail=Objects.toString(action.get("description"),"");
           throw new IllegalStateException(detail.isBlank()?status:detail);
         }
       }
     }catch(Exception error){
       String detail=Objects.toString(error.getMessage(),error.getClass().getSimpleName());
       if(detail.length()>500)detail=detail.substring(0,500);
       if(isRetryable(detail))retryOrFail(id,detail);else jdbc.update("UPDATE discovery_command SET status='FAILED',last_error=? WHERE id=?",detail,id);
     }
     finally{
       SecurityContextHolder.clearContext();jdbc.update("UPDATE discovery_command SET lease_until=NULL WHERE id=?",id);
       Long remaining=jdbc.queryForObject("SELECT COUNT(*) FROM discovery_command WHERE run_id=? AND status IN ('NEW','POLL')",Long.class,run);
       if(remaining!=null&&remaining==0){
         Long failed=jdbc.queryForObject("SELECT COUNT(*) FROM discovery_command WHERE run_id=? AND status='FAILED'",Long.class,run);
         Long partial=jdbc.queryForObject("SELECT COUNT(*) FROM discovery_command WHERE run_id=? AND status='PARTIAL'",Long.class,run);
         jdbc.update("UPDATE discovery_run SET status=?,progress=100 WHERE id=?",failed!=null&&failed>0?"failed":partial!=null&&partial>0?"partial":"completed",run);
       }
     }
   }
 }

 private boolean isRetryable(String detail){String value=Objects.toString(detail,"").toUpperCase(Locale.ROOT);return value.contains("RUNNER")||value.contains("TIMEOUT")||value.contains("连接")||value.contains("不可用")||value.contains("TARGET.CREATETARGET")||value.contains("FAILED TO OPEN A NEW TAB")||value.contains("BROWSERCONTEXT.NEWPAGE");}
 private void retryOrFail(UUID id,String detail){
   Integer attempts=jdbc.queryForObject("SELECT attempt_count FROM discovery_command WHERE id=?",Integer.class,id);int next=(attempts==null?0:attempts)+1;
   if(next<=5){int delay=next==1?5:next==2?15:next==3?30:60;jdbc.update("UPDATE discovery_command SET status='NEW',attempt_count=?,last_error=?,next_attempt_at=?,lease_until=NULL WHERE id=?",next,detail,java.sql.Timestamp.from(Instant.now().plusSeconds(delay)),id);}
   else jdbc.update("UPDATE discovery_command SET status='FAILED',attempt_count=?,last_error=?,lease_until=NULL WHERE id=?",next,detail,id);
 }
}
