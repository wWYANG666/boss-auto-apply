ALTER TABLE automation_task ADD COLUMN runner_task_id VARCHAR(128);
ALTER TABLE automation_task ADD COLUMN runner_command_id VARCHAR(128);
ALTER TABLE automation_task ADD COLUMN runner_phase VARCHAR(40);
ALTER TABLE automation_task ADD COLUMN last_synced_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_automation_task_runner_task ON automation_task (runner_task_id);
