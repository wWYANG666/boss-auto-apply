CREATE TABLE resume_artifact (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES app_user(id),
 resume_version_id UUID NOT NULL REFERENCES resume_version(id),
 sha256 VARCHAR(64) NOT NULL,
 font_sha256 VARCHAR(64) NOT NULL,
 template_version VARCHAR(60) NOT NULL,
 byte_size BIGINT NOT NULL,
 page_count INTEGER NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(user_id,resume_version_id,template_version,font_sha256)
);
