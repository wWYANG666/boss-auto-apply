package com.careerlens.core.automation;
import com.careerlens.core.auth.AppPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor
public class ExecutionPolicyController {
 private final JdbcTemplate jdbc;
 private final PlatformIdentityService identities;
 private final AutomationService automation;
 @GetMapping("/api/v1/execution-policy")
 Map<String,Object> get(@AuthenticationPrincipal AppPrincipal user){
   UUID identity=identities.activeIdOrNull(user.id());
   var rows=identity==null?List.<Map<String,Object>>of():jdbc.query("SELECT paused,daily_limit,dry_run FROM platform_execution_policy WHERE user_id=? AND platform_identity_id=?",(rs,n)->Map.<String,Object>of("paused",rs.getBoolean(1),"dailyLimit",rs.getInt(2),"dryRun",rs.getBoolean(3)),user.id(),identity);
   Map<String,Object> policy=rows.isEmpty()?new LinkedHashMap<>(Map.of("paused",false,"dailyLimit",20,"dryRun",false)):new LinkedHashMap<>(rows.get(0));
   AutomationService.DailyQuota quota=automation.dailyQuota(user.id(),identity);
   policy.put("dailyLimit",quota.dailyLimit());policy.put("used",quota.used());policy.put("remaining",quota.remaining());policy.put("platformLimit",quota.platformLimit());
   return policy;
 }
 @PatchMapping("/api/v1/execution-policy")
 Map<String,Object> update(@AuthenticationPrincipal AppPrincipal user,@Valid @RequestBody Policy policy){
   UUID identity=identities.activeId(user.id());
   if(jdbc.update("UPDATE platform_execution_policy SET paused=?,daily_limit=?,dry_run=? WHERE user_id=? AND platform_identity_id=?",policy.paused(),policy.dailyLimit(),policy.dryRun(),user.id(),identity)==0)
     jdbc.update("INSERT INTO platform_execution_policy(platform_identity_id,user_id,paused,daily_limit,dry_run) VALUES (?,?,?,?,?)",identity,user.id(),policy.paused(),policy.dailyLimit(),policy.dryRun());
   return get(user);
 }
 record Policy(boolean paused,@Min(1) @Max(150) int dailyLimit,boolean dryRun){}
}
