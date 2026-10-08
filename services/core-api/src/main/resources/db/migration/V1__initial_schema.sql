CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE access_token (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE resume (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    title VARCHAR(160) NOT NULL,
    headline VARCHAR(240),
    current_version_number INTEGER NOT NULL DEFAULT 0,
    current_version_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE resume_draft (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    resume_id UUID NOT NULL REFERENCES resume(id) ON DELETE CASCADE,
    content_text TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_resume_draft UNIQUE (resume_id)
);

CREATE TABLE resume_version (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    resume_id UUID NOT NULL REFERENCES resume(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    content_text TEXT NOT NULL,
    source VARCHAR(40) NOT NULL,
    source_version_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_resume_version UNIQUE (resume_id, version_number)
);

CREATE TABLE job_description (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    company VARCHAR(180) NOT NULL,
    role_name VARCHAR(180) NOT NULL,
    location VARCHAR(160),
    salary VARCHAR(120),
    current_version_number INTEGER NOT NULL DEFAULT 0,
    current_version_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE job_description_version (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    job_description_id UUID NOT NULL REFERENCES job_description(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    raw_text TEXT NOT NULL,
    structured_text TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_jd_version UNIQUE (job_description_id, version_number)
);

CREATE TABLE jd_requirement (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    jd_version_id UUID NOT NULL REFERENCES job_description_version(id) ON DELETE CASCADE,
    requirement_text TEXT NOT NULL,
    category VARCHAR(80) NOT NULL,
    importance VARCHAR(30) NOT NULL,
    hard_condition BOOLEAN NOT NULL DEFAULT FALSE,
    aliases_text TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE match_run (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    resume_version_id UUID NOT NULL REFERENCES resume_version(id),
    jd_version_id UUID NOT NULL REFERENCES job_description_version(id),
    total_score NUMERIC(5,2) NOT NULL,
    breakdown_text TEXT NOT NULL,
    rule_version VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE match_item (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    match_run_id UUID NOT NULL REFERENCES match_run(id) ON DELETE CASCADE,
    requirement_id UUID NOT NULL REFERENCES jd_requirement(id),
    requirement_text TEXT NOT NULL,
    category VARCHAR(80) NOT NULL,
    importance VARCHAR(30) NOT NULL,
    verdict VARCHAR(30) NOT NULL,
    score NUMERIC(6,2) NOT NULL,
    max_score NUMERIC(6,2) NOT NULL,
    jd_evidence TEXT NOT NULL,
    resume_evidence TEXT NOT NULL,
    resume_source VARCHAR(240) NOT NULL,
    match_method VARCHAR(80) NOT NULL,
    confidence NUMERIC(5,4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE suggestion_batch (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    match_run_id UUID NOT NULL REFERENCES match_run(id),
    resume_version_id UUID NOT NULL REFERENCES resume_version(id),
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE resume_suggestion (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    batch_id UUID NOT NULL REFERENCES suggestion_batch(id) ON DELETE CASCADE,
    target_element_id VARCHAR(80),
    section_name VARCHAR(100) NOT NULL,
    title VARCHAR(240) NOT NULL,
    before_text TEXT NOT NULL,
    after_text TEXT NOT NULL,
    reason_text TEXT NOT NULL,
    evidence_text TEXT NOT NULL,
    suggestion_kind VARCHAR(30) NOT NULL,
    suggestion_status VARCHAR(30) NOT NULL,
    patch_text TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE job_application (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    company VARCHAR(180) NOT NULL,
    role_name VARCHAR(180) NOT NULL,
    location VARCHAR(160),
    stage VARCHAR(30) NOT NULL,
    match_score INTEGER NOT NULL DEFAULT 0,
    next_action VARCHAR(240),
    logo_text VARCHAR(10),
    logo_tone VARCHAR(20),
    tags_text TEXT NOT NULL,
    platform VARCHAR(30),
    external_job_id VARCHAR(160),
    resume_version_number INTEGER,
    action_type VARCHAR(40),
    automation_status VARCHAR(40),
    receipt VARCHAR(240),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_application_external_action UNIQUE (user_id, platform, external_job_id, action_type)
);

CREATE TABLE application_event (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    application_id UUID NOT NULL REFERENCES job_application(id) ON DELETE CASCADE,
    event_type VARCHAR(60) NOT NULL,
    from_stage VARCHAR(30),
    to_stage VARCHAR(30),
    detail_text TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE platform_account (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    platform VARCHAR(30) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    connection_type VARCHAR(40) NOT NULL,
    connection_status VARCHAR(40) NOT NULL,
    masked_identity VARCHAR(160),
    capabilities_text TEXT NOT NULL,
    adapter_version VARCHAR(60),
    last_checked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_platform_account UNIQUE (user_id, platform)
);

CREATE TABLE discovery_run (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    platforms_text TEXT NOT NULL,
    spec_text TEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    discovered_count INTEGER NOT NULL DEFAULT 0,
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE discovered_job (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    discovery_run_id UUID REFERENCES discovery_run(id) ON DELETE SET NULL,
    platform VARCHAR(30) NOT NULL,
    external_job_id VARCHAR(160) NOT NULL,
    company VARCHAR(180) NOT NULL,
    role_name VARCHAR(180) NOT NULL,
    location VARCHAR(160),
    salary VARCHAR(120),
    experience VARCHAR(120),
    degree VARCHAR(120),
    published_at_text VARCHAR(120),
    recruiter VARCHAR(160),
    recruiter_active VARCHAR(120),
    company_tone VARCHAR(20),
    match_score INTEGER NOT NULL DEFAULT 0,
    grade VARCHAR(5) NOT NULL,
    matched_skills_text TEXT NOT NULL,
    missing_skills_text TEXT NOT NULL,
    hard_conflicts_text TEXT NOT NULL,
    summary_text TEXT NOT NULL,
    job_content_text TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    already_tracked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_discovered_job UNIQUE (user_id, platform, external_job_id)
);

CREATE TABLE application_plan (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    discovered_job_id UUID NOT NULL REFERENCES discovered_job(id),
    platform VARCHAR(30) NOT NULL,
    external_job_id VARCHAR(160) NOT NULL,
    action_type VARCHAR(40) NOT NULL,
    resume_version_id UUID NOT NULL REFERENCES resume_version(id),
    resume_version_number INTEGER NOT NULL,
    greeting_text TEXT NOT NULL,
    form_answers_text TEXT NOT NULL,
    included BOOLEAN NOT NULL DEFAULT TRUE,
    approval_status VARCHAR(30) NOT NULL,
    plan_hash VARCHAR(64) NOT NULL,
    approved_hash VARCHAR(64),
    approval_token_hash VARCHAR(64),
    approval_expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_application_plan UNIQUE (user_id, platform, external_job_id, action_type)
);

CREATE TABLE automation_task (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    application_plan_id UUID REFERENCES application_plan(id),
    platform VARCHAR(30) NOT NULL,
    external_job_id VARCHAR(160) NOT NULL,
    company VARCHAR(180) NOT NULL,
    role_name VARCHAR(180) NOT NULL,
    action_type VARCHAR(40) NOT NULL,
    resume_version_number INTEGER NOT NULL,
    task_status VARCHAR(40) NOT NULL,
    progress INTEGER NOT NULL DEFAULT 0,
    current_step VARCHAR(240) NOT NULL,
    human_action_text TEXT,
    receipt VARCHAR(240),
    attempt INTEGER NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE human_action (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    automation_task_id UUID NOT NULL REFERENCES automation_task(id) ON DELETE CASCADE,
    action_kind VARCHAR(40) NOT NULL,
    title VARCHAR(180) NOT NULL,
    description_text TEXT NOT NULL,
    action_status VARCHAR(30) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    aggregate_type VARCHAR(60) NOT NULL,
    aggregate_id UUID,
    event_type VARCHAR(80) NOT NULL,
    detail_text TEXT NOT NULL,
    request_id VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_token_user ON access_token(user_id);
CREATE INDEX idx_resume_user ON resume(user_id, updated_at);
CREATE INDEX idx_resume_version_resume ON resume_version(resume_id, version_number);
CREATE INDEX idx_jd_user ON job_description(user_id, updated_at);
CREATE INDEX idx_jd_requirement_version ON jd_requirement(jd_version_id, sort_order);
CREATE INDEX idx_match_user ON match_run(user_id, created_at);
CREATE INDEX idx_match_item_run ON match_item(match_run_id);
CREATE INDEX idx_suggestion_batch_match ON suggestion_batch(match_run_id);
CREATE INDEX idx_application_user_stage ON job_application(user_id, stage, updated_at);
CREATE INDEX idx_discovered_job_run ON discovered_job(discovery_run_id, match_score);
CREATE INDEX idx_plan_user_status ON application_plan(user_id, approval_status);
CREATE INDEX idx_task_user_status ON automation_task(user_id, task_status, updated_at);
CREATE INDEX idx_human_action_task ON human_action(automation_task_id, action_status);
CREATE INDEX idx_audit_aggregate ON audit_event(user_id, aggregate_type, aggregate_id, created_at);
