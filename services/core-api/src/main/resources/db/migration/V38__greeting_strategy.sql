CREATE TABLE greeting_policy (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    platform_identity_id UUID NOT NULL REFERENCES platform_identity(id) ON DELETE CASCADE,
    active_resume_version_id UUID REFERENCES resume_version(id),
    default_style VARCHAR(40) NOT NULL DEFAULT 'PROJECT_EVIDENCE',
    include_question BOOLEAN NOT NULL DEFAULT TRUE,
    max_length INTEGER NOT NULL DEFAULT 95,
    banned_words_text TEXT NOT NULL DEFAULT '["精通","专家","大师","顶尖"]',
    preferred_words_text TEXT NOT NULL DEFAULT '["做过","实现过","独立完成","熟悉"]',
    ai_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id,platform_identity_id)
);

ALTER TABLE application_plan ADD COLUMN greeting_style VARCHAR(40);
ALTER TABLE application_plan ADD COLUMN greeting_evidence_text TEXT NOT NULL DEFAULT '{}';
ALTER TABLE application_plan ADD COLUMN greeting_candidates_text TEXT NOT NULL DEFAULT '[]';

ALTER TABLE job_application ADD COLUMN handoff_status VARCHAR(40);
ALTER TABLE job_application ADD COLUMN handed_off_at TIMESTAMP WITH TIME ZONE;
