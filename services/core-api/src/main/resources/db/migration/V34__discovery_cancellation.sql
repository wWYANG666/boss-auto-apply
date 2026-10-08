ALTER TABLE discovery_command ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE discovery_command ADD COLUMN cancelled_at TIMESTAMP WITH TIME ZONE;
