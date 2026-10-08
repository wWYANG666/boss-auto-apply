package com.careerlens.core.automation;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DataRetentionService {
    private final JdbcTemplate jdbc;
    @Value("${careerlens.retention.task-days:90}") private int taskDays;
    @Value("${careerlens.retention.discovery-days:30}") private int discoveryDays;

    @Scheduled(cron="${careerlens.retention.cron:0 25 3 * * *}")
    @Transactional
    public void scheduledArchive(){archiveAll();}

    @Transactional
    public Map<String,Integer> archive(UUID userId){return archiveWhere(" AND user_id=?",userId);}

    @Transactional
    public Map<String,Integer> archiveAll(){return archiveWhere("",null);}

    private Map<String,Integer> archiveWhere(String ownerClause,UUID userId){
        Instant now=Instant.now();
        Object[] taskArgs=userId==null?new Object[]{Timestamp.from(now),Timestamp.from(now.minus(Math.max(1,taskDays),ChronoUnit.DAYS))}:new Object[]{Timestamp.from(now),Timestamp.from(now.minus(Math.max(1,taskDays),ChronoUnit.DAYS)),userId};
        Object[] discoveryArgs=userId==null?new Object[]{Timestamp.from(now),Timestamp.from(now.minus(Math.max(1,discoveryDays),ChronoUnit.DAYS))}:new Object[]{Timestamp.from(now),Timestamp.from(now.minus(Math.max(1,discoveryDays),ChronoUnit.DAYS)),userId};
        int tasks=jdbc.update("UPDATE automation_task SET archived_at=? WHERE archived_at IS NULL AND task_status IN ('succeeded','failed','cancelled','dry_run','page_changed') AND updated_at<?"+ownerClause,taskArgs);
        int plans=jdbc.update("UPDATE application_plan SET archived_at=? WHERE archived_at IS NULL AND approval_status IN ('executed','skipped') AND updated_at<?"+ownerClause,taskArgs);
        int discoveries=jdbc.update("UPDATE discovery_run SET archived_at=? WHERE archived_at IS NULL AND status IN ('completed','failed') AND updated_at<?"+ownerClause,discoveryArgs);
        return Map.of("tasks",tasks,"plans",plans,"discoveries",discoveries);
    }
}
