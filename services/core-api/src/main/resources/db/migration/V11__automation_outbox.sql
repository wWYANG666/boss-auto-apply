CREATE TABLE automation_outbox (
 task_id UUID PRIMARY KEY REFERENCES automation_task(id),
 user_id UUID NOT NULL REFERENCES app_user(id),
 phase VARCHAR(40) NOT NULL DEFAULT 'NEW',
 payload TEXT NOT NULL DEFAULT '{}',
 runner_id VARCHAR(128),
 lease_until TIMESTAMP WITH TIME ZONE,
 next_run_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 attempts INTEGER NOT NULL DEFAULT 0,
 last_error VARCHAR(500)
);
CREATE INDEX idx_outbox_due ON automation_outbox(next_run_at,lease_until);
CREATE TABLE discovery_run_job (
 discovery_run_id UUID NOT NULL REFERENCES discovery_run(id),
 discovered_job_id UUID NOT NULL REFERENCES discovered_job(id),
 PRIMARY KEY(discovery_run_id,discovered_job_id)
);
INSERT INTO discovery_run_job SELECT discovery_run_id,id FROM discovered_job WHERE discovery_run_id IS NOT NULL;
