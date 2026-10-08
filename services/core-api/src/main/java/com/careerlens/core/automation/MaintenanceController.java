package com.careerlens.core.automation;

import com.careerlens.core.auth.AppPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {
    private final DataRetentionService retention;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final java.util.Optional<LocalDatabaseBackupService> localBackup;
    @PostMapping("/archive") Map<String,Integer> archive(@AuthenticationPrincipal AppPrincipal user){return retention.archive(user.id());}
    @GetMapping("/stats") Map<String,Long> stats(@AuthenticationPrincipal AppPrincipal user){
        return Map.of(
            "applications",count("SELECT COUNT(*) FROM job_application WHERE user_id=?",user.id()),
            "discoveries",count("SELECT COUNT(*) FROM discovery_run WHERE user_id=?",user.id()),
            "plans",count("SELECT COUNT(*) FROM application_plan WHERE user_id=?",user.id()),
            "tasks",count("SELECT COUNT(*) FROM automation_task WHERE user_id=?",user.id()),
            "archivedTasks",count("SELECT COUNT(*) FROM automation_task WHERE user_id=? AND archived_at IS NOT NULL",user.id()),
            "artifactBytes",sum("SELECT COALESCE(SUM(byte_size),0) FROM resume_artifact WHERE user_id=?",user.id()),
            "artifacts",count("SELECT COUNT(*) FROM resume_artifact WHERE user_id=?",user.id())
        );
    }
    private long count(String sql,UUID userId){Long value=jdbc.queryForObject(sql,Long.class,userId);return value==null?0:value;}
    private long sum(String sql,UUID userId){Long value=jdbc.queryForObject(sql,Long.class,userId);return value==null?0:value;}
    @PostMapping("/local-backup") Map<String,Object> localBackup() throws java.io.IOException{return localBackup.orElseThrow(()->com.careerlens.core.common.ApiException.badRequest("LOCAL_BACKUP_UNAVAILABLE","本机H2备份仅在local配置启用")).backup();}
    @GetMapping("/local-backup") Map<String,Object> localBackupStatus() throws java.io.IOException{return localBackup.isPresent()?localBackup.get().status():Map.of("count",0,"bytes",0);}
}
