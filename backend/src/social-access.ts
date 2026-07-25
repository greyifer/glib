import type { Pool } from "pg";
import { ApiError } from "./domain.js";

export interface SocialAccessState {
  enabled: boolean;
  reason: string | null;
  disabledAt: string | null;
  disabledByUuid: string | null;
  disabledByUsername: string | null;
}

export async function socialAccessState(pool: Pool, playerUuid: string): Promise<SocialAccessState> {
  const result = await pool.query<{
    disable_reason: string;
    disabled_at: Date;
    disabled_by: string | null;
    disabled_by_username: string | null;
  }>(
    `SELECT restriction.disable_reason, restriction.disabled_at, restriction.disabled_by,
            moderator.username AS disabled_by_username
     FROM social_access_restrictions restriction
     LEFT JOIN users moderator ON moderator.player_uuid = restriction.disabled_by
     WHERE restriction.player_uuid = $1 AND restriction.restored_at IS NULL
     ORDER BY restriction.disabled_at DESC
     LIMIT 1`,
    [playerUuid],
  );
  const restriction = result.rows[0];
  if (!restriction) {
    return {
      enabled: true,
      reason: null,
      disabledAt: null,
      disabledByUuid: null,
      disabledByUsername: null,
    };
  }
  return {
    enabled: false,
    reason: restriction.disable_reason,
    disabledAt: restriction.disabled_at.toISOString(),
    disabledByUuid: restriction.disabled_by,
    disabledByUsername: restriction.disabled_by_username,
  };
}

export async function requireSocialAccess(pool: Pool, playerUuid: string): Promise<void> {
  const result = await pool.query(
    `SELECT 1 FROM social_access_restrictions
     WHERE player_uuid = $1 AND restored_at IS NULL
     LIMIT 1`,
    [playerUuid],
  );
  if (result.rowCount !== 0) {
    throw new ApiError(
      403,
      "SOCIAL_ACCESS_DISABLED",
      "Friends & Chat access has been disabled for this account. Contact a G'Network moderator if you believe this is a mistake.",
    );
  }
}
