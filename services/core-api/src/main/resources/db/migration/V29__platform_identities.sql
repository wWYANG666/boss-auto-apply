CREATE TABLE platform_identity (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    platform VARCHAR(30) NOT NULL,
    profile_name VARCHAR(32) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    account_fingerprint VARCHAR(64),
    masked_identity VARCHAR(160),
    connection_status VARCHAR(40) NOT NULL DEFAULT 'disconnected',
    active BOOLEAN NOT NULL DEFAULT FALSE,
    last_checked_at TIMESTAMP WITH TIME ZONE,
    archived_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_platform_identity_profile UNIQUE(user_id,platform,profile_name),
    CONSTRAINT uq_platform_identity_fingerprint UNIQUE(user_id,platform,account_fingerprint)
);

INSERT INTO platform_identity(id,user_id,platform,profile_name,display_name,masked_identity,connection_status,active,last_checked_at)
SELECT id,user_id,platform,'default','默认BOSS账号',masked_identity,connection_status,TRUE,last_checked_at
FROM platform_account WHERE platform='boss';

ALTER TABLE discovery_run ADD COLUMN platform_identity_id UUID REFERENCES platform_identity(id);
ALTER TABLE discovered_job ADD COLUMN platform_identity_id UUID REFERENCES platform_identity(id);
ALTER TABLE application_plan ADD COLUMN platform_identity_id UUID REFERENCES platform_identity(id);
ALTER TABLE automation_task ADD COLUMN platform_identity_id UUID REFERENCES platform_identity(id);
ALTER TABLE job_application ADD COLUMN platform_identity_id UUID REFERENCES platform_identity(id);
ALTER TABLE one_stop_run ADD COLUMN platform_identity_id UUID REFERENCES platform_identity(id);

UPDATE discovery_run r SET platform_identity_id=(SELECT i.id FROM platform_identity i WHERE i.user_id=r.user_id AND i.platform='boss' AND i.profile_name='default') WHERE platforms_text LIKE '%boss%';
UPDATE discovered_job j SET platform_identity_id=(SELECT i.id FROM platform_identity i WHERE i.user_id=j.user_id AND i.platform='boss' AND i.profile_name='default') WHERE platform='boss';
UPDATE application_plan p SET platform_identity_id=(SELECT i.id FROM platform_identity i WHERE i.user_id=p.user_id AND i.platform='boss' AND i.profile_name='default') WHERE platform='boss';
UPDATE automation_task t SET platform_identity_id=(SELECT i.id FROM platform_identity i WHERE i.user_id=t.user_id AND i.platform='boss' AND i.profile_name='default') WHERE platform='boss';
UPDATE job_application a SET platform_identity_id=(SELECT i.id FROM platform_identity i WHERE i.user_id=a.user_id AND i.platform='boss' AND i.profile_name='default') WHERE platform='boss';
UPDATE one_stop_run r SET platform_identity_id=(SELECT i.id FROM platform_identity i WHERE i.user_id=r.user_id AND i.platform='boss' AND i.profile_name='default') WHERE platforms_text LIKE '%boss%';

ALTER TABLE discovered_job DROP CONSTRAINT uq_discovered_job;
ALTER TABLE discovered_job ADD CONSTRAINT uq_discovered_job_identity UNIQUE(user_id,platform_identity_id,platform,external_job_id);
ALTER TABLE application_plan DROP CONSTRAINT uq_application_plan;
ALTER TABLE application_plan ADD CONSTRAINT uq_application_plan_identity UNIQUE(user_id,platform_identity_id,platform,external_job_id,action_type);
ALTER TABLE job_application DROP CONSTRAINT uq_application_external_action;
ALTER TABLE job_application ADD CONSTRAINT uq_application_external_identity_action UNIQUE(user_id,platform_identity_id,platform,external_job_id,action_type);

CREATE INDEX idx_platform_identity_active ON platform_identity(user_id,platform,active,archived_at);
CREATE INDEX idx_discovery_identity ON discovery_run(user_id,platform_identity_id,created_at);
CREATE INDEX idx_task_identity ON automation_task(user_id,platform_identity_id,task_status,updated_at);
CREATE INDEX idx_application_identity ON job_application(user_id,platform_identity_id,stage,updated_at);

CREATE TABLE platform_execution_policy (
    platform_identity_id UUID PRIMARY KEY REFERENCES platform_identity(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    daily_limit INTEGER NOT NULL DEFAULT 20,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO platform_execution_policy(platform_identity_id,user_id,paused,daily_limit,dry_run)
SELECT i.id,i.user_id,COALESCE(p.paused,FALSE),COALESCE(p.daily_limit,20),COALESCE(p.dry_run,FALSE)
FROM platform_identity i LEFT JOIN execution_policy p ON p.user_id=i.user_id;
