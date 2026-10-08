package com.careerlens.core.application;

import com.careerlens.core.auth.AppPrincipal;
import com.careerlens.core.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Portable business backup. Sessions, device secrets and executable outbox commands are deliberately excluded. */
@RestController @RequiredArgsConstructor
public class WorkspaceBackupController {
 private final JdbcTemplate jdbc;
 private static final List<String> TABLES=List.of("resume","resume_draft","resume_version","job_description","job_description_version",
   "jd_requirement","match_run","match_item","suggestion_batch","resume_suggestion","platform_identity","platform_execution_policy","job_application","application_event",
   "platform_account","discovery_run","discovered_job","job_observation","application_plan","automation_task","human_action","audit_event","application_followup");
 @GetMapping("/api/v1/workspace/backup") @Transactional(readOnly=true)
 Map<String,Object> export(@AuthenticationPrincipal AppPrincipal user){
   Map<String,Object> tables=new LinkedHashMap<>();
   for(String table:TABLES)tables.put(table,jdbc.query("SELECT * FROM "+table+" WHERE user_id=?",(rs,n)->row(rs),user.id()));
   return Map.of("format","careerlens-backup-v1","exportedAt",Instant.now().toString(),"tables",tables,
     "excluded",List.of("credentials","access_tokens","executable_outbox","artifact_files"));
 }
 @GetMapping("/api/v1/workspace/backup/validate") @Transactional(readOnly=true)
 Map<String,Object> validate(@AuthenticationPrincipal AppPrincipal user){
   Map<String,Long> counts=new LinkedHashMap<>();long total=0;
   for(String table:TABLES){Long value=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Long.class,user.id());long count=value==null?0:value;counts.put(table,count);total+=count;}
   Long missingReferences=jdbc.queryForObject("SELECT COUNT(*) FROM automation_task t LEFT JOIN application_plan p ON p.id=t.application_plan_id WHERE t.user_id=? AND t.application_plan_id IS NOT NULL AND p.id IS NULL",Long.class,user.id());
   return Map.of("valid",missingReferences==null||missingReferences==0,"totalRecords",total,"tableCounts",counts,"missingReferences",missingReferences==null?0:missingReferences,"artifactFilesExcluded",true);
 }
 private Map<String,Object> row(ResultSet rs)throws SQLException{
   var meta=rs.getMetaData();Map<String,Object> data=new LinkedHashMap<>();
   for(int i=1;i<=meta.getColumnCount();i++){
     Object value=rs.getObject(i);String name=meta.getColumnName(i).toLowerCase(Locale.ROOT);
     if(value!=null){
       if(meta.getColumnType(i)==Types.TIMESTAMP||meta.getColumnType(i)==Types.TIMESTAMP_WITH_TIMEZONE)value=rs.getTimestamp(i).toInstant().toString();
       else if("uuid".equalsIgnoreCase(meta.getColumnTypeName(i)))value=value.toString();
       else if(value instanceof Clob)value=rs.getString(i);
     }
     data.put(name,value);
   }
   return data;
 }
 @PostMapping("/api/v1/workspace/restore") @Transactional
 Map<String,Object> restore(@AuthenticationPrincipal AppPrincipal user,@RequestBody Backup body){
   if(!"careerlens-backup-v1".equals(body.format())||!body.acknowledged())throw ApiException.badRequest("BACKUP_CONFIRMATION_REQUIRED","请确认备份格式和恢复操作");
   for(String table:TABLES)if(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Long.class,user.id())>0)
     throw ApiException.conflict("RESTORE_REQUIRES_EMPTY_WORKSPACE","请使用空账号恢复，现有数据不会被覆盖");
   int count=body.tables().values().stream().mapToInt(List::size).sum();
   if(count>10000)throw ApiException.badRequest("BACKUP_TOO_LARGE","单次恢复最多10000条业务记录");
   Map<String,UUID> ids=new HashMap<>();
   body.tables().values().forEach(rows->rows.forEach(row->{if(row.get("id")!=null)ids.put(row.get("id").toString(),UUID.randomUUID());}));
   for(String table:TABLES)for(Map<String,Object> record:body.tables().getOrDefault(table,List.of())){
     List<Map<String,Object>> metadata=jdbc.query("SELECT * FROM "+table+" WHERE 1=0",rs->{
       var m=rs.getMetaData();List<Map<String,Object>> result=new ArrayList<>();
       for(int i=1;i<=m.getColumnCount();i++)result.add(Map.of("name",m.getColumnName(i).toLowerCase(Locale.ROOT),"type",m.getColumnType(i),"typeName",m.getColumnTypeName(i)));
       return result;
     });
     List<String> columns=new ArrayList<>();List<Object> values=new ArrayList<>();
     for(var column:metadata){
       String name=column.get("name").toString();if(!record.containsKey(name))continue;
       Object value=record.get(name);
       if("user_id".equals(name))value=user.id();
       else if(value!=null&&"uuid".equalsIgnoreCase(column.get("typeName").toString())){
         value=ids.get(value.toString());if(value==null)throw ApiException.badRequest("BACKUP_REFERENCE_MISSING","备份引用缺失");
       }else if(value!=null&&Set.of(Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE).contains(column.get("type")))
         value=Timestamp.from(Instant.parse(value.toString()));
       if("connection_status".equals(name))value="disconnected";
       if("approval_status".equals(name))value="draft";
       if(Set.of("approved_hash","approval_token_hash","approval_expires_at","runner_task_id","runner_command_id").contains(name))value=null;
       if("task_status".equals(name)&&!Set.of("succeeded","cancelled","failed").contains(value))value="unknown_outcome";
       columns.add(name);values.add(value);
     }
     jdbc.update("INSERT INTO "+table+" ("+String.join(",",columns)+") VALUES ("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.toArray());
   }
   jdbc.update("INSERT INTO discovery_run_job(discovery_run_id,discovered_job_id) SELECT discovery_run_id,id FROM discovered_job WHERE user_id=? AND discovery_run_id IS NOT NULL",user.id());
   return Map.of("restoredRecords",count,"externalExecutionResumed",false);
 }
 record Backup(String format,boolean acknowledged,Map<String,List<Map<String,Object>>> tables){}
}
