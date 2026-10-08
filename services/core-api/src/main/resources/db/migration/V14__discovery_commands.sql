CREATE TABLE discovery_command (
 id UUID PRIMARY KEY,
 run_id UUID NOT NULL REFERENCES discovery_run(id),
 user_id UUID NOT NULL REFERENCES app_user(id),
 resume_version_id UUID NOT NULL REFERENCES resume_version(id),
 platform VARCHAR(30) NOT NULL,
 payload TEXT NOT NULL,
 runner_id VARCHAR(128),
 status VARCHAR(30) NOT NULL DEFAULT 'NEW',
 lease_until TIMESTAMP WITH TIME ZONE,
 last_error VARCHAR(500)
);
