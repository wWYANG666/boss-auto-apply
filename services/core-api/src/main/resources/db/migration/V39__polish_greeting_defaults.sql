ALTER TABLE greeting_policy ALTER COLUMN default_style SET DEFAULT 'SKILL_MATCH';
ALTER TABLE greeting_policy ALTER COLUMN max_length SET DEFAULT 85;
UPDATE greeting_policy SET default_style='SKILL_MATCH',max_length=LEAST(max_length,85),updated_at=CURRENT_TIMESTAMP
WHERE default_style='PROJECT_EVIDENCE';
