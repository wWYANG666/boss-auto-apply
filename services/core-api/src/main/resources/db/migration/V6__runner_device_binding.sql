CREATE TABLE runner_device_binding (
    user_id UUID PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    encrypted_token TEXT NOT NULL,
    paired_at TIMESTAMP WITH TIME ZONE NOT NULL
);
