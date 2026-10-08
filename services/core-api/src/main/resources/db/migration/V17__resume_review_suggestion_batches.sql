ALTER TABLE suggestion_batch ALTER COLUMN match_run_id DROP NOT NULL;
ALTER TABLE suggestion_batch ALTER COLUMN resume_version_id DROP NOT NULL;
ALTER TABLE suggestion_batch ADD COLUMN resume_id UUID REFERENCES resume(id);
ALTER TABLE suggestion_batch ADD COLUMN draft_revision BIGINT;
