-- Retire the historically generated account without deleting subsequently edited content.
-- Its existing records remain recoverable by an operator; it no longer enters the real UI.
UPDATE app_user SET status = 'ARCHIVED', updated_at = CURRENT_TIMESTAMP
WHERE email = 'demo@careerlens.local' AND display_name = '林一';
DELETE FROM access_token WHERE user_id IN (
    SELECT id FROM app_user WHERE email = 'demo@careerlens.local' AND status = 'ARCHIVED'
);
