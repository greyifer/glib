CREATE TABLE chat_groups (
    group_id UUID PRIMARY KEY,
    name VARCHAR(48) NOT NULL,
    owner_uuid UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT group_name_not_blank CHECK (length(btrim(name)) > 0)
);

CREATE TABLE group_members (
    group_id UUID NOT NULL REFERENCES chat_groups(group_id) ON DELETE CASCADE,
    player_uuid UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, player_uuid)
);

CREATE INDEX group_members_player_idx ON group_members (player_uuid, joined_at DESC);

CREATE TABLE group_messages (
    message_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES chat_groups(group_id) ON DELETE CASCADE,
    client_message_id UUID NOT NULL,
    sender_uuid UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    body VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT group_message_not_blank CHECK (length(btrim(body)) > 0),
    CONSTRAINT group_message_client_id_unique UNIQUE (sender_uuid, client_message_id)
);

CREATE INDEX group_messages_history_idx ON group_messages (group_id, created_at DESC);
