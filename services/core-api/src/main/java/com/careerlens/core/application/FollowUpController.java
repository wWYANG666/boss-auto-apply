package com.careerlens.core.application;
import com.careerlens.core.auth.AppPrincipal;
import com.careerlens.core.common.ApiException;
import com.careerlens.core.common.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

@RestController @RequiredArgsConstructor
public class FollowUpController {
 private final JdbcTemplate jdbc;
 private final ApplicationService applications;
 @GetMapping("/api/v1/followups")
 List<Map<String,Object>> list(@AuthenticationPrincipal AppPrincipal user){
   return jdbc.query("SELECT * FROM application_followup WHERE user_id=? ORDER BY due_at",(rs,n)->Map.<String,Object>of(
       "id",rs.getString("id"),"applicationId",rs.getString("application_id"),"title",rs.getString("title"),
       "dueAt",rs.getTimestamp("due_at").toInstant().toString(),"completed",rs.getBoolean("completed")),user.id());
 }
 @GetMapping("/api/v1/followups/page") PageResult<Map<String,Object>> page(@AuthenticationPrincipal AppPrincipal user,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){
   var all=list(user);int size=Math.max(1,Math.min(100,limit)),start=Math.max(0,offset);var items=all.stream().skip(start).limit(size).toList();boolean more=start+items.size()<all.size();return new PageResult<>(items,all.size(),more?String.valueOf(start+items.size()):null,more);
 }
 @PostMapping("/api/v1/applications/{id}/followups")
 Map<String,Object> create(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id,@Valid @RequestBody Create request){
   applications.owned(user.id(),id);UUID followup=UUID.randomUUID();
   jdbc.update("INSERT INTO application_followup(id,user_id,application_id,title,due_at) VALUES (?,?,?,?,?)",followup,user.id(),id,request.title(),java.sql.Timestamp.from(request.dueAt()));
   return Map.of("id",followup);
 }
 @PatchMapping("/api/v1/followups/{id}")
 @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
 void update(@AuthenticationPrincipal AppPrincipal user,@PathVariable UUID id,@RequestBody Update request){
   if(jdbc.update("UPDATE application_followup SET completed=? WHERE id=? AND user_id=?",request.completed(),id,user.id())==0)throw ApiException.notFound("跟进待办");
 }
 record Create(@NotBlank @Size(max=240) String title,@NotNull Instant dueAt){}
 record Update(boolean completed){}
}
