CREATE TABLE admin_accounts (
    player_uuid UUID PRIMARY KEY REFERENCES users(player_uuid) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL DEFAULT 'MODERATOR',
    password_hash TEXT NOT NULL,
    must_change_password BOOLEAN NOT NULL DEFAULT true,
    disabled_at TIMESTAMPTZ,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ,
    CONSTRAINT admin_role_valid CHECK (role IN ('MODERATOR', 'OWNER')),
    CONSTRAINT admin_failed_attempts_valid CHECK (failed_attempts >= 0)
);

CREATE TABLE admin_sessions (
    token_hash CHAR(64) PRIMARY KEY,
    player_uuid UUID NOT NULL REFERENCES admin_accounts(player_uuid) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX admin_sessions_player_idx ON admin_sessions (player_uuid);
CREATE INDEX admin_sessions_expiry_idx ON admin_sessions (expires_at);

CREATE TABLE message_reports (
    report_id UUID PRIMARY KEY,
    kind VARCHAR(8) NOT NULL,
    source_message_id BIGINT NOT NULL,
    conversation_id TEXT NOT NULL,
    reporter_uuid UUID NOT NULL,
    reporter_username VARCHAR(16) NOT NULL,
    reported_uuid UUID NOT NULL,
    reported_username VARCHAR(16) NOT NULL,
    selected_body VARCHAR(500) NOT NULL,
    selected_created_at TIMESTAMPTZ NOT NULL,
    context_truncated BOOLEAN NOT NULL DEFAULT false,
    reason VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    reviewed_by UUID,
    CONSTRAINT report_kind_valid CHECK (kind IN ('DIRECT', 'GROUP')),
    CONSTRAINT report_status_valid CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT report_not_self CHECK (reporter_uuid <> reported_uuid),
    CONSTRAINT report_unique_per_reporter UNIQUE (reporter_uuid, kind, source_message_id)
);

CREATE INDEX message_reports_queue_idx ON message_reports (status, created_at DESC);
CREATE INDEX message_reports_reported_idx ON message_reports (reported_uuid, created_at DESC);

CREATE TABLE message_report_context (
    report_id UUID NOT NULL REFERENCES message_reports(report_id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL,
    source_message_id BIGINT NOT NULL,
    sender_uuid UUID NOT NULL,
    sender_username VARCHAR(16) NOT NULL,
    body VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (report_id, sequence_number)
);

CREATE TABLE admin_audit_log (
    audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    admin_uuid UUID NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32),
    target_id TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX admin_audit_actor_idx ON admin_audit_log (admin_uuid, created_at DESC);
CREATE INDEX admin_audit_target_idx ON admin_audit_log (target_type, target_id, created_at DESC);
