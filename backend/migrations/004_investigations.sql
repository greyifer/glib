ALTER TABLE messages
    ADD COLUMN sender_username_at_send VARCHAR(16),
    ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'SENT',
    ADD COLUMN edited_at TIMESTAMPTZ,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN reply_to_message_id BIGINT REFERENCES messages(message_id) ON DELETE SET NULL,
    ADD COLUMN attachment_kind VARCHAR(32),
    ADD COLUMN attachment_label VARCHAR(120);

UPDATE messages message
SET sender_username_at_send = users.username
FROM users
WHERE users.player_uuid = message.sender_uuid
  AND message.sender_username_at_send IS NULL;

ALTER TABLE messages
    ADD CONSTRAINT message_state_valid CHECK (state IN ('SENT', 'EDITED', 'DELETED'));

CREATE INDEX messages_reply_idx ON messages (reply_to_message_id) WHERE reply_to_message_id IS NOT NULL;

ALTER TABLE group_messages
    ADD COLUMN sender_username_at_send VARCHAR(16),
    ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'SENT',
    ADD COLUMN edited_at TIMESTAMPTZ,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN reply_to_message_id BIGINT REFERENCES group_messages(message_id) ON DELETE SET NULL,
    ADD COLUMN attachment_kind VARCHAR(32),
    ADD COLUMN attachment_label VARCHAR(120);

UPDATE group_messages message
SET sender_username_at_send = users.username
FROM users
WHERE users.player_uuid = message.sender_uuid
  AND message.sender_username_at_send IS NULL;

ALTER TABLE group_messages
    ADD CONSTRAINT group_message_state_valid CHECK (state IN ('SENT', 'EDITED', 'DELETED'));

CREATE INDEX group_messages_reply_idx
    ON group_messages (reply_to_message_id) WHERE reply_to_message_id IS NOT NULL;

ALTER TABLE message_report_context
    ADD COLUMN message_state VARCHAR(16) NOT NULL DEFAULT 'SENT',
    ADD COLUMN edited_at TIMESTAMPTZ,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN reply_to_source_message_id BIGINT,
    ADD COLUMN attachment_kind VARCHAR(32),
    ADD COLUMN attachment_label VARCHAR(120);

CREATE TABLE admin_conversation_bookmarks (
    admin_uuid UUID NOT NULL REFERENCES admin_accounts(player_uuid) ON DELETE CASCADE,
    kind VARCHAR(8) NOT NULL,
    conversation_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    PRIMARY KEY (admin_uuid, kind, conversation_id),
    CONSTRAINT admin_bookmark_kind_valid CHECK (kind IN ('DIRECT', 'GROUP', 'REPORT'))
);

CREATE INDEX admin_bookmark_open_idx
    ON admin_conversation_bookmarks (admin_uuid, created_at DESC) WHERE resolved_at IS NULL;

CREATE TABLE admin_case_notes (
    note_id UUID PRIMARY KEY,
    admin_uuid UUID NOT NULL REFERENCES admin_accounts(player_uuid) ON DELETE CASCADE,
    target_type VARCHAR(16) NOT NULL,
    target_id TEXT NOT NULL,
    body VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT admin_note_target_valid CHECK (target_type IN ('DIRECT', 'GROUP', 'REPORT')),
    CONSTRAINT admin_note_not_blank CHECK (length(btrim(body)) > 0)
);

CREATE INDEX admin_case_notes_target_idx
    ON admin_case_notes (target_type, target_id, created_at);

CREATE TABLE admin_message_stars (
    admin_uuid UUID NOT NULL REFERENCES admin_accounts(player_uuid) ON DELETE CASCADE,
    kind VARCHAR(8) NOT NULL,
    source_message_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (admin_uuid, kind, source_message_id),
    CONSTRAINT admin_star_kind_valid CHECK (kind IN ('DIRECT', 'GROUP'))
);

CREATE INDEX admin_message_stars_actor_idx
    ON admin_message_stars (admin_uuid, created_at DESC);
