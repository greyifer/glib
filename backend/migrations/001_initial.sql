CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    player_uuid UUID PRIMARY KEY,
    username VARCHAR(16) NOT NULL,
    normalized_username VARCHAR(16) UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_authenticated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ
);

CREATE TABLE auth_challenges (
    challenge_id UUID PRIMARY KEY,
    username VARCHAR(16) NOT NULL,
    server_id VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);

CREATE INDEX auth_challenges_expiry_idx ON auth_challenges (expires_at);

CREATE TABLE sessions (
    token_hash CHAR(64) PRIMARY KEY,
    player_uuid UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX sessions_player_idx ON sessions (player_uuid);
CREATE INDEX sessions_expiry_idx ON sessions (expires_at);

CREATE TABLE friend_requests (
    request_id UUID PRIMARY KEY,
    sender_uuid UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    recipient_uuid UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT friend_request_not_self CHECK (sender_uuid <> recipient_uuid),
    CONSTRAINT friend_request_unique_direction UNIQUE (sender_uuid, recipient_uuid)
);

CREATE INDEX friend_requests_recipient_idx ON friend_requests (recipient_uuid, created_at DESC);

CREATE TABLE friendships (
    player_low UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    player_high UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_low, player_high),
    CONSTRAINT friendship_ordered CHECK (player_low::text < player_high::text)
);

CREATE INDEX friendships_high_idx ON friendships (player_high);

CREATE TABLE blocks (
    blocker_uuid UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    blocked_uuid UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_uuid, blocked_uuid),
    CONSTRAINT block_not_self CHECK (blocker_uuid <> blocked_uuid)
);

CREATE TABLE messages (
    message_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    client_message_id UUID NOT NULL,
    sender_uuid UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    recipient_uuid UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    body VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at TIMESTAMPTZ,
    CONSTRAINT message_not_self CHECK (sender_uuid <> recipient_uuid),
    CONSTRAINT message_not_blank CHECK (length(btrim(body)) > 0),
    CONSTRAINT message_client_id_unique UNIQUE (sender_uuid, client_message_id)
);

CREATE INDEX messages_conversation_idx
    ON messages (LEAST(sender_uuid, recipient_uuid), GREATEST(sender_uuid, recipient_uuid), created_at DESC);
CREATE INDEX messages_unread_idx ON messages (recipient_uuid, read_at) WHERE read_at IS NULL;
