ALTER TABLE discovered_job ADD COLUMN recruiter_id VARCHAR(256);

CREATE INDEX idx_discovered_job_recruiter_identity
    ON discovered_job(user_id, platform_identity_id, recruiter_id);
