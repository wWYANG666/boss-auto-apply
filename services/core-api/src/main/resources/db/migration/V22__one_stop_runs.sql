CREATE TABLE one_stop_run (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    resume_version_id UUID NOT NULL REFERENCES resume_version(id),
    platforms_text TEXT NOT NULL,
    spec_text TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    phase VARCHAR(40) NOT NULL,
    discovery_run_id UUID REFERENCES discovery_run(id),
    job_ids_text TEXT NOT NULL DEFAULT '[]',
    task_ids_text TEXT NOT NULL DEFAULT '[]',
    processed_external_ids_text TEXT NOT NULL DEFAULT '[]',
    group_offset INTEGER NOT NULL DEFAULT 0,
    discovery_round INTEGER NOT NULL DEFAULT 0,
    processed_count INTEGER NOT NULL DEFAULT 0,
    reconcile_attempts INTEGER NOT NULL DEFAULT 0,
    stop_requested BOOLEAN NOT NULL DEFAULT FALSE,
    last_error VARCHAR(500),
    lease_until TIMESTAMP WITH TIME ZONE,
    next_run_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_one_stop_run_due ON one_stop_run(status,next_run_at,lease_until);
CREATE INDEX idx_one_stop_run_user ON one_stop_run(user_id,created_at);
