ALTER TABLE automation_task ADD COLUMN retry_of_task_id UUID REFERENCES automation_task(id) ON DELETE SET NULL;
ALTER TABLE automation_task ADD COLUMN retry_root_task_id UUID REFERENCES automation_task(id) ON DELETE SET NULL;
ALTER TABLE automation_task ADD COLUMN failure_category VARCHAR(60);
ALTER TABLE automation_task ADD COLUMN retryable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE automation_task ADD COLUMN execution_snapshot_text TEXT;

UPDATE automation_task SET
    failure_category = CASE
        WHEN current_step LIKE '%VERIFIED_NOT_SUBMITTED%' THEN 'NOT_SUBMITTED_CONFIRMED'
        WHEN current_step LIKE '%BOSS_CONTACT_URL_MISSING%' THEN 'CONTACT_ROUTE_STALE'
        WHEN current_step LIKE '%BOSS_PAGE_JOB_ID_MISMATCH%' OR current_step LIKE '%BOSS_PAGE_IDENTITY_MISMATCH%' THEN 'REQUIRES_REDISCOVERY'
        WHEN current_step LIKE '%Approval expired%' OR current_step LIKE '%审批凭证%' THEN 'APPROVAL_EXPIRED'
        WHEN current_step LIKE '%JOB_UNAVAILABLE%' OR current_step LIKE '%职位已关闭%' THEN 'JOB_UNAVAILABLE'
        ELSE 'EXECUTION_FAILED'
    END,
    retryable = CASE
        WHEN current_step LIKE '%VERIFIED_NOT_SUBMITTED%' OR current_step LIKE '%BOSS_CONTACT_URL_MISSING%'
             OR current_step LIKE '%Approval expired%' OR current_step LIKE '%审批凭证%' THEN TRUE
        ELSE FALSE
    END
WHERE task_status IN ('failed','page_changed');

UPDATE application_plan SET included=FALSE, approval_status=CASE
    WHEN EXISTS (SELECT 1 FROM automation_task t WHERE t.application_plan_id=application_plan.id AND t.task_status IN ('failed','page_changed') AND t.retryable=TRUE) THEN 'failed_retryable'
    WHEN EXISTS (SELECT 1 FROM automation_task t WHERE t.application_plan_id=application_plan.id AND t.failure_category='REQUIRES_REDISCOVERY') THEN 'requires_rediscovery'
    ELSE 'failed_final'
END
WHERE approval_status<>'executed' AND EXISTS (
    SELECT 1 FROM automation_task t WHERE t.application_plan_id=application_plan.id AND t.task_status IN ('failed','page_changed')
);

CREATE INDEX idx_automation_task_retry_root ON automation_task(user_id,retry_root_task_id,created_at);
CREATE INDEX idx_automation_task_failure ON automation_task(user_id,retryable,failure_category,updated_at);
