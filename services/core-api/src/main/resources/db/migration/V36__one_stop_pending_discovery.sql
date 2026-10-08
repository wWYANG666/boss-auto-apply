ALTER TABLE one_stop_run ADD COLUMN pending_discovery_run_id UUID REFERENCES discovery_run(id);
