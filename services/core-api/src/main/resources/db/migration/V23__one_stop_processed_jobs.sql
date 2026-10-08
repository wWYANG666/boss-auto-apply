CREATE TABLE one_stop_processed_job (
    run_id UUID NOT NULL REFERENCES one_stop_run(id) ON DELETE CASCADE,
    platform VARCHAR(30) NOT NULL,
    external_job_id VARCHAR(160) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(run_id,platform,external_job_id)
);

CREATE INDEX idx_one_stop_processed_external ON one_stop_processed_job(run_id,external_job_id);
