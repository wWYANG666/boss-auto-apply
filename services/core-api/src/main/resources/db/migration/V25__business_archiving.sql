ALTER TABLE discovery_run ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE application_plan ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE automation_task ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_discovery_run_archive ON discovery_run(user_id,archived_at,created_at);
CREATE INDEX idx_application_plan_archive ON application_plan(user_id,archived_at,created_at);
CREATE INDEX idx_automation_task_archive ON automation_task(user_id,archived_at,created_at);
