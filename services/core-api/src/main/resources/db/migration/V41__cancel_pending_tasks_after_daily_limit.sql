UPDATE automation_outbox
SET phase='DONE',last_error='Cancelled after BOSS daily contact limit'
WHERE task_id IN (
  SELECT t.id
  FROM automation_task t
  WHERE t.task_status IN ('queued','preparing','awaiting_login','awaiting_captcha','awaiting_question')
    AND EXISTS (
      SELECT 1
      FROM automation_task quota
      WHERE quota.user_id=t.user_id
        AND (quota.platform_identity_id=t.platform_identity_id OR quota.platform_identity_id IS NULL AND t.platform_identity_id IS NULL)
        AND quota.current_step='BOSS每日沟通已达上限，今日任务已终止'
        AND CAST(quota.updated_at AS DATE)=CURRENT_DATE
    )
);

UPDATE automation_task t
SET task_status='cancelled',
    progress=100,
    current_step='BOSS今日沟通额度已用完，剩余任务已取消',
    human_action_text=NULL,
    retryable=FALSE,
    state_version=state_version+1,
    updated_at=CURRENT_TIMESTAMP
WHERE task_status IN ('queued','preparing','awaiting_login','awaiting_captcha','awaiting_question')
  AND EXISTS (
    SELECT 1
    FROM automation_task quota
    WHERE quota.user_id=t.user_id
      AND (quota.platform_identity_id=t.platform_identity_id OR quota.platform_identity_id IS NULL AND t.platform_identity_id IS NULL)
      AND quota.current_step='BOSS每日沟通已达上限，今日任务已终止'
      AND CAST(quota.updated_at AS DATE)=CURRENT_DATE
  );
