CREATE TABLE gsave_rooms (
    room_id UUID PRIMARY KEY,
    archive_id VARCHAR(64) NOT NULL UNIQUE,
    owner_uuid UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    payload BYTEA NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    size_bytes INTEGER NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gsave_room_archive_id_format CHECK (archive_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT gsave_room_archive_id_not_dos_device CHECK (
        archive_id !~ '^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\.|$)'
    ),
    CONSTRAINT gsave_room_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT gsave_room_payload_not_empty CHECK (octet_length(payload) > 0),
    CONSTRAINT gsave_room_size_matches_payload CHECK (size_bytes = octet_length(payload)),
    CONSTRAINT gsave_room_size_limit CHECK (size_bytes <= 8388608),
    CONSTRAINT gsave_room_sha256_format CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT gsave_room_revision_positive CHECK (revision > 0)
);

CREATE INDEX gsave_rooms_library_idx ON gsave_rooms (updated_at DESC, room_id DESC);
CREATE INDEX gsave_rooms_owner_idx ON gsave_rooms (owner_uuid, updated_at DESC);
