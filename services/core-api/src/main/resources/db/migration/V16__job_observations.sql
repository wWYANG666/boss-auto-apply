CREATE TABLE job_observation (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES app_user(id),
 run_id UUID NOT NULL REFERENCES discovery_run(id),
 job_id UUID NOT NULL REFERENCES discovered_job(id),
 view_json TEXT NOT NULL,
 raw_json TEXT NOT NULL,
 content_hash VARCHAR(64) NOT NULL,
 observed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(run_id,job_id)
);
ALTER TABLE application_plan ADD COLUMN observation_id UUID REFERENCES job_observation(id);
