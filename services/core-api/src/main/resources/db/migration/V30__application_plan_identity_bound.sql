ALTER TABLE application_plan ADD COLUMN identity_bound BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE application_plan SET identity_bound=TRUE WHERE platform_identity_id IS NOT NULL;
