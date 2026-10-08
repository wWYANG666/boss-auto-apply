ALTER TABLE discovery_command ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE discovery_command ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX idx_discovery_command_due ON discovery_command(status,next_attempt_at,lease_until);
