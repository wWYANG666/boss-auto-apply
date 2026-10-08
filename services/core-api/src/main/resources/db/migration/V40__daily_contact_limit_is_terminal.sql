UPDATE one_stop_run r
SET status='completed',
    phase='DAILY_LIMIT_REACHED',
    task_ids_text='[]',
    attention_count=0,
    last_error='BOSS今日最多沟通150位，已自动终止本次任务；明天可重新开始',
    next_run_at=CURRENT_TIMESTAMP,
    updated_at=CURRENT_TIMESTAMP
WHERE status IN ('running','paused')
  AND EXISTS (
    SELECT 1
    FROM automation_task t
    WHERE t.user_id=r.user_id
      AND (t.platform_identity_id=r.platform_identity_id OR t.platform_identity_id IS NULL AND r.platform_identity_id IS NULL)
      AND t.human_action_text LIKE '%BOSS_DAILY_CONTACT_LIMIT%'
  );

UPDATE automation_outbox
SET phase='FAILED',last_error='BOSS_DAILY_CONTACT_LIMIT'
WHERE task_id IN (
  SELECT id FROM automation_task WHERE human_action_text LIKE '%BOSS_DAILY_CONTACT_LIMIT%'
);

UPDATE automation_task
SET task_status='failed',
    progress=100,
    current_step='BOSS每日沟通已达上限，今日任务已终止',
    human_action_text=NULL,
    failure_category='PLATFORM_LIMITED',
    retryable=FALSE,
    state_version=state_version+1,
    updated_at=CURRENT_TIMESTAMP
WHERE human_action_text LIKE '%BOSS_DAILY_CONTACT_LIMIT%';
