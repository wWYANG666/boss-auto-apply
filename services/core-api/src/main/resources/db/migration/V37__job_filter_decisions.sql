CREATE TABLE job_filter_decision (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES discovery_run(id) ON DELETE CASCADE,
    external_job_id VARCHAR(256) NOT NULL,
    company VARCHAR(180),
    role_name VARCHAR(180),
    reason VARCHAR(60) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_job_filter_decision_run ON job_filter_decision(run_id,reason);
