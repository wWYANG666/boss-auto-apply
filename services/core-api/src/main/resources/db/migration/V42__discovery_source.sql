ALTER TABLE discovery_run ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'manual';
CREATE INDEX idx_discovery_run_source ON discovery_run(user_id,source,created_at);
