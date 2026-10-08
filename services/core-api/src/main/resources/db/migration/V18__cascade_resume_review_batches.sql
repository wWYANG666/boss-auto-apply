ALTER TABLE suggestion_batch DROP CONSTRAINT IF EXISTS CONSTRAINT_172C7;
ALTER TABLE suggestion_batch DROP CONSTRAINT IF EXISTS suggestion_batch_resume_id_fkey;
ALTER TABLE suggestion_batch ADD CONSTRAINT fk_suggestion_batch_resume
    FOREIGN KEY (resume_id) REFERENCES resume(id) ON DELETE CASCADE;
