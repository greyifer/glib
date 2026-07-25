CREATE TABLE social_access_restrictions (
    restriction_id UUID PRIMARY KEY,
    player_uuid UUID NOT NULL REFERENCES users(player_uuid) ON DELETE CASCADE,
    disabled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    disabled_by UUID REFERENCES users(player_uuid) ON DELETE SET NULL,
    disable_reason VARCHAR(500) NOT NULL,
    restored_at TIMESTAMPTZ,
    restored_by UUID REFERENCES users(player_uuid) ON DELETE SET NULL,
    restore_reason VARCHAR(500),
    CONSTRAINT social_access_disable_reason_not_blank CHECK (length(btrim(disable_reason)) > 0),
    CONSTRAINT social_access_restore_reason_valid CHECK (
        (restored_at IS NULL AND restored_by IS NULL AND restore_reason IS NULL)
        OR
        (restored_at IS NOT NULL AND restore_reason IS NOT NULL AND length(btrim(restore_reason)) > 0)
    )
);

CREATE UNIQUE INDEX social_access_one_active_restriction_idx
    ON social_access_restrictions (player_uuid)
    WHERE restored_at IS NULL;

CREATE INDEX social_access_restriction_history_idx
    ON social_access_restrictions (player_uuid, disabled_at DESC);
