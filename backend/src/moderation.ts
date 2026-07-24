import { randomBytes, randomUUID, scrypt, timingSafeEqual } from "node:crypto";
import type { Pool, PoolClient } from "pg";
import { first, transaction } from "./db.js";
import {
  ApiError,
  hashToken,
  orderedPair,
  parseMessageId,
  parseReportKind,
  parseReportStatus,
  parseUuid,
  randomToken,
  validatePassword,
  validateReportReason,
} from "./domain.js";
import type { Actor, AdminActor } from "./types.js";

const SCRYPT_N = 16_384;
const SCRYPT_R = 8;
const SCRYPT_P = 1;
const SCRYPT_LENGTH = 64;
const SCRYPT_MAX_MEMORY = 64 * 1024 * 1024;
const DUMMY_PASSWORD_HASH =
  "scrypt$16384$8$1$nw3XS1hnQZ2W7VvW8W1JSQ$wDdCtRyGOrBGNFjpkNzrYGJD3la1S2-g6yQXdFDEyPIW_EQEPPqkz3k4MP_a0r4dQF4B1qJ9N2EV5TZ0JYTq2g";

interface AdminAccountRow {
  player_uuid: string;
  username: string;
  role: "MODERATOR" | "OWNER";
  password_hash: string;
  must_change_password: boolean;
  disabled_at: Date | null;
  failed_attempts: number;
  locked_until: Date | null;
}

interface AdminSessionRow {
  player_uuid: string;
  username: string;
  role: "MODERATOR" | "OWNER";
  must_change_password: boolean;
}

interface ReportMessageRow {
  message_id: string;
  sender_uuid: string;
  sender_username: string;
  body: string;
  created_at: Date;
}

interface DirectReportMessageRow extends ReportMessageRow {
  recipient_uuid: string;
}

interface GroupReportMessageRow extends ReportMessageRow {
  group_id: string;
}

interface ReportRow {
  report_id: string;
  kind: "DIRECT" | "GROUP";
  source_message_id: string;
  conversation_id: string;
  reporter_uuid: string;
  reporter_username: string;
  reported_uuid: string;
  reported_username: string;
  selected_body: string;
  selected_created_at: Date;
  context_truncated: boolean;
  reason: string;
  status: "OPEN" | "RESOLVED" | "DISMISSED";
  created_at: Date;
  reviewed_at: Date | null;
  reviewed_by: string | null;
}

interface ContextRow extends ReportMessageRow {
  sequence_number?: number;
}

export async function hashAdminPassword(password: string): Promise<string> {
  validatePassword(password);
  const salt = randomBytes(16);
  const derived = await derivePassword(password, salt);
  return `scrypt$${SCRYPT_N}$${SCRYPT_R}$${SCRYPT_P}$${salt.toString("base64url")}$${derived.toString("base64url")}`;
}

async function verifyAdminPassword(password: string, encoded: string): Promise<boolean> {
  const parts = encoded.split("$");
  if (
    parts.length !== 6 ||
    parts[0] !== "scrypt" ||
    Number(parts[1]) !== SCRYPT_N ||
    Number(parts[2]) !== SCRYPT_R ||
    Number(parts[3]) !== SCRYPT_P
  ) {
    return false;
  }
  try {
    const salt = Buffer.from(parts[4]!, "base64url");
    const expected = Buffer.from(parts[5]!, "base64url");
    if (salt.length !== 16 || expected.length !== SCRYPT_LENGTH) return false;
    const actual = await derivePassword(password, salt);
    return timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

async function derivePassword(password: string, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    scrypt(password, salt, SCRYPT_LENGTH, {
      N: SCRYPT_N,
      r: SCRYPT_R,
      p: SCRYPT_P,
      maxmem: SCRYPT_MAX_MEMORY,
    }, (error, derivedKey) => {
      if (error) reject(error);
      else resolve(derivedKey);
    });
  });
}

export class AdminAuthService {
  constructor(
    private readonly pool: Pool,
    private readonly sessionTtlMinutes: number,
  ) {}

  async access(actor: Actor): Promise<{
    eligible: boolean;
    role: "MODERATOR" | "OWNER" | null;
    mustChangePassword: boolean;
  }> {
    const result = await this.pool.query<Pick<AdminAccountRow, "role" | "must_change_password">>(
      "SELECT role, must_change_password FROM admin_accounts WHERE player_uuid = $1 AND disabled_at IS NULL",
      [actor.playerUuid],
    );
    const account = result.rows[0];
    return {
      eligible: account !== undefined,
      role: account?.role ?? null,
      mustChangePassword: account?.must_change_password ?? false,
    };
  }

  async login(actor: Actor, rawPassword: unknown, ip: string): Promise<{
    token: string;
    expiresAt: string;
    role: "MODERATOR" | "OWNER";
    mustChangePassword: boolean;
  }> {
    const password = validatePassword(rawPassword);
    const result = await this.pool.query<AdminAccountRow>(
      `SELECT a.player_uuid, u.username, a.role, a.password_hash, a.must_change_password,
              a.disabled_at, a.failed_attempts, a.locked_until
       FROM admin_accounts a JOIN users u ON u.player_uuid = a.player_uuid
       WHERE a.player_uuid = $1`,
      [actor.playerUuid],
    );
    const account = result.rows[0];
    const passwordMatches = await verifyAdminPassword(password, account?.password_hash ?? DUMMY_PASSWORD_HASH);

    if (!account || account.disabled_at || !passwordMatches) {
      if (account && !account.disabled_at) {
        await this.pool.query(
          `UPDATE admin_accounts
           SET failed_attempts = failed_attempts + 1,
               locked_until = CASE WHEN failed_attempts + 1 >= 5 THEN now() + interval '15 minutes' ELSE locked_until END,
               updated_at = now()
           WHERE player_uuid = $1`,
          [actor.playerUuid],
        );
      }
      await this.audit(actor.playerUuid, "ADMIN_LOGIN_FAILED", "ADMIN_ACCOUNT", actor.playerUuid, { ip });
      throw new ApiError(403, "ADMIN_ACCESS_DENIED", "The moderator login was rejected.");
    }
    if (account.locked_until && account.locked_until.getTime() > Date.now()) {
      await this.audit(actor.playerUuid, "ADMIN_LOGIN_LOCKED", "ADMIN_ACCOUNT", actor.playerUuid, { ip });
      throw new ApiError(429, "ADMIN_LOGIN_LOCKED", "Too many failed attempts. Try again later.");
    }

    const token = randomToken();
    const inserted = await transaction(this.pool, async (client) => {
      await client.query(
        `UPDATE admin_accounts SET failed_attempts = 0, locked_until = NULL,
           last_login_at = now(), updated_at = now() WHERE player_uuid = $1`,
        [actor.playerUuid],
      );
      const session = await client.query<{ expires_at: Date }>(
        `INSERT INTO admin_sessions(token_hash, player_uuid, expires_at)
         VALUES ($1, $2, now() + ($3 * interval '1 minute')) RETURNING expires_at`,
        [hashToken(token), actor.playerUuid, this.sessionTtlMinutes],
      );
      await client.query(
        `DELETE FROM admin_sessions WHERE token_hash IN (
           SELECT token_hash FROM admin_sessions WHERE player_uuid = $1 ORDER BY created_at DESC OFFSET 2
         )`,
        [actor.playerUuid],
      );
      return first(session.rows);
    });
    await this.audit(actor.playerUuid, "ADMIN_LOGIN_SUCCEEDED", "ADMIN_ACCOUNT", actor.playerUuid, { ip });
    return {
      token,
      expiresAt: inserted.expires_at.toISOString(),
      role: account.role,
      mustChangePassword: account.must_change_password,
    };
  }

  async actorForToken(actor: Actor, token: string): Promise<AdminActor> {
    const result = await this.pool.query<AdminSessionRow>(
      `SELECT a.player_uuid, u.username, a.role, a.must_change_password
       FROM admin_sessions s
       JOIN admin_accounts a ON a.player_uuid = s.player_uuid
       JOIN users u ON u.player_uuid = a.player_uuid
       WHERE s.token_hash = $1 AND s.player_uuid = $2
         AND s.expires_at > now() AND a.disabled_at IS NULL`,
      [hashToken(token), actor.playerUuid],
    );
    const row = result.rows[0];
    if (!row) throw new ApiError(401, "ADMIN_UNAUTHENTICATED", "The moderator session is invalid or expired.");
    return {
      playerUuid: row.player_uuid,
      username: row.username,
      role: row.role,
      mustChangePassword: row.must_change_password,
    };
  }

  async changePassword(
    actor: AdminActor,
    adminToken: string,
    rawCurrentPassword: unknown,
    rawNewPassword: unknown,
    ip: string,
  ): Promise<void> {
    const currentPassword = validatePassword(rawCurrentPassword, "currentPassword");
    const newPassword = validatePassword(rawNewPassword, "newPassword");
    if (currentPassword === newPassword) {
      throw new ApiError(400, "PASSWORD_UNCHANGED", "Choose a different password.");
    }
    const result = await this.pool.query<{ password_hash: string }>(
      "SELECT password_hash FROM admin_accounts WHERE player_uuid = $1 AND disabled_at IS NULL",
      [actor.playerUuid],
    );
    const account = result.rows[0];
    if (!account || !await verifyAdminPassword(currentPassword, account.password_hash)) {
      throw new ApiError(403, "CURRENT_PASSWORD_REJECTED", "The current password is incorrect.");
    }
    const replacement = await hashAdminPassword(newPassword);
    const currentTokenHash = hashToken(adminToken);
    await transaction(this.pool, async (client) => {
      await client.query(
        `UPDATE admin_accounts SET password_hash = $1, must_change_password = false,
           failed_attempts = 0, locked_until = NULL, updated_at = now()
         WHERE player_uuid = $2`,
        [replacement, actor.playerUuid],
      );
      await client.query(
        "DELETE FROM admin_sessions WHERE player_uuid = $1 AND token_hash <> $2",
        [actor.playerUuid, currentTokenHash],
      );
    });
    await this.audit(actor.playerUuid, "ADMIN_PASSWORD_CHANGED", "ADMIN_ACCOUNT", actor.playerUuid, { ip });
  }

  async revoke(token: string): Promise<void> {
    await this.pool.query("DELETE FROM admin_sessions WHERE token_hash = $1", [hashToken(token)]);
  }

  async cleanExpired(): Promise<void> {
    await this.pool.query("DELETE FROM admin_sessions WHERE expires_at < now()");
  }

  async audit(
    adminUuid: string,
    action: string,
    targetType: string | null,
    targetId: string | null,
    metadata: Record<string, unknown> = {},
  ): Promise<void> {
    await this.pool.query(
      `INSERT INTO admin_audit_log(admin_uuid, action, target_type, target_id, metadata)
       VALUES ($1, $2, $3, $4, $5::jsonb)`,
      [adminUuid, action, targetType, targetId, JSON.stringify(metadata)],
    );
  }
}

export class ReportService {
  constructor(private readonly pool: Pool) {}

  async submit(actor: Actor, rawKind: unknown, rawMessageId: unknown, rawReason: unknown): Promise<unknown> {
    const kind = parseReportKind(rawKind);
    const messageId = parseMessageId(rawMessageId);
    const reason = validateReportReason(rawReason);
    try {
      return await transaction(this.pool, async (client) => {
        return kind === "DIRECT"
          ? this.submitDirect(client, actor, messageId, reason)
          : this.submitGroup(client, actor, messageId, reason);
      });
    } catch (error) {
      if (isUniqueViolation(error)) {
        throw new ApiError(409, "REPORT_EXISTS", "You have already reported this message.");
      }
      throw error;
    }
  }

  private async submitDirect(client: PoolClient, actor: Actor, messageId: string, reason: string): Promise<unknown> {
    const selectedResult = await client.query<DirectReportMessageRow>(
      `SELECT m.message_id, m.sender_uuid, m.recipient_uuid, u.username AS sender_username,
              m.body, m.created_at
       FROM messages m JOIN users u ON u.player_uuid = m.sender_uuid
       WHERE m.message_id = $1 AND (m.sender_uuid = $2 OR m.recipient_uuid = $2)`,
      [messageId, actor.playerUuid],
    );
    const selected = selectedResult.rows[0];
    if (!selected) throw new ApiError(404, "MESSAGE_NOT_FOUND", "That message is unavailable.");
    if (selected.sender_uuid === actor.playerUuid) {
      throw new ApiError(400, "CANNOT_REPORT_SELF", "You cannot report your own message.");
    }
    const [low, high] = orderedPair(selected.sender_uuid, selected.recipient_uuid);
    const boundary = await directBoundaries(client, selected, low, high);
    const context = await directContext(client, selected, low, high, boundary.previous, boundary.next);
    return this.insertReport(client, actor, selected, "DIRECT", `${low}:${high}`, reason, context);
  }

  private async submitGroup(client: PoolClient, actor: Actor, messageId: string, reason: string): Promise<unknown> {
    const selectedResult = await client.query<GroupReportMessageRow>(
      `SELECT m.message_id, m.group_id, m.sender_uuid, u.username AS sender_username,
              m.body, m.created_at
       FROM group_messages m
       JOIN users u ON u.player_uuid = m.sender_uuid
       JOIN group_members member ON member.group_id = m.group_id AND member.player_uuid = $2
       WHERE m.message_id = $1`,
      [messageId, actor.playerUuid],
    );
    const selected = selectedResult.rows[0];
    if (!selected) throw new ApiError(404, "MESSAGE_NOT_FOUND", "That group message is unavailable.");
    if (selected.sender_uuid === actor.playerUuid) {
      throw new ApiError(400, "CANNOT_REPORT_SELF", "You cannot report your own message.");
    }
    const boundary = await groupBoundaries(client, selected);
    const context = await groupContext(client, selected, boundary.previous, boundary.next);
    return this.insertReport(client, actor, selected, "GROUP", selected.group_id, reason, context);
  }

  private async insertReport(
    client: PoolClient,
    actor: Actor,
    selected: ReportMessageRow,
    kind: "DIRECT" | "GROUP",
    conversationId: string,
    reason: string,
    contextResult: { messages: ContextRow[]; truncated: boolean },
  ): Promise<unknown> {
    const reportId = randomUUID();
    await client.query(
      `INSERT INTO message_reports(
         report_id, kind, source_message_id, conversation_id, reporter_uuid, reporter_username,
         reported_uuid, reported_username, selected_body, selected_created_at, context_truncated, reason
       ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
      [
        reportId,
        kind,
        selected.message_id,
        conversationId,
        actor.playerUuid,
        actor.username,
        selected.sender_uuid,
        selected.sender_username,
        selected.body,
        selected.created_at,
        contextResult.truncated,
        reason,
      ],
    );
    for (let index = 0; index < contextResult.messages.length; index++) {
      const message = contextResult.messages[index]!;
      await client.query(
        `INSERT INTO message_report_context(
           report_id, sequence_number, source_message_id, sender_uuid, sender_username, body, created_at
         ) VALUES ($1, $2, $3, $4, $5, $6, $7)`,
        [reportId, index, message.message_id, message.sender_uuid, message.sender_username, message.body, message.created_at],
      );
    }
    return { reportId, status: "OPEN", contextMessageCount: contextResult.messages.length };
  }
}

export class ModerationService {
  constructor(
    private readonly pool: Pool,
    private readonly auth: AdminAuthService,
  ) {}

  async users(admin: AdminActor, rawSearch: unknown, ip: string): Promise<unknown[]> {
    const search = adminSearch(rawSearch);
    const result = await this.pool.query<{
      player_uuid: string;
      username: string;
      created_at: Date;
      last_authenticated_at: Date;
      last_seen_at: Date | null;
      direct_count: string;
      group_count: string;
    }>(
      `SELECT u.player_uuid, u.username, u.created_at, u.last_authenticated_at, u.last_seen_at,
          (SELECT count(DISTINCT CASE WHEN m.sender_uuid = u.player_uuid THEN m.recipient_uuid ELSE m.sender_uuid END)::text
           FROM messages m WHERE m.sender_uuid = u.player_uuid OR m.recipient_uuid = u.player_uuid) AS direct_count,
          (SELECT count(*)::text FROM group_members gm WHERE gm.player_uuid = u.player_uuid) AS group_count
       FROM users u
       WHERE ($1 = '' OR strpos(lower(u.username), $1) > 0)
       ORDER BY lower(u.username), u.player_uuid
       LIMIT 100`,
      [search],
    );
    await this.auth.audit(admin.playerUuid, "ADMIN_USERS_VIEWED", "USER_LIST", search || "all", {
      ip,
      resultCount: result.rows.length,
    });
    return result.rows.map((row) => ({
      playerUuid: row.player_uuid,
      username: row.username,
      createdAt: row.created_at.toISOString(),
      lastAuthenticatedAt: row.last_authenticated_at.toISOString(),
      lastSeenAt: row.last_seen_at?.toISOString() ?? null,
      directCount: Number(row.direct_count),
      groupCount: Number(row.group_count),
    }));
  }

  async user(admin: AdminActor, rawPlayerUuid: unknown, ip: string): Promise<unknown> {
    const playerUuid = parseUuid(rawPlayerUuid);
    const userResult = await this.pool.query<{
      player_uuid: string;
      username: string;
      created_at: Date;
      last_authenticated_at: Date;
      last_seen_at: Date | null;
    }>(
      `SELECT player_uuid, username, created_at, last_authenticated_at, last_seen_at
       FROM users WHERE player_uuid = $1`,
      [playerUuid],
    );
    const user = userResult.rows[0];
    if (!user) throw new ApiError(404, "PLAYER_NOT_FOUND", "That G'Lib player does not exist.");
    const [directs, groups] = await Promise.all([
      this.pool.query<{
        player_uuid: string;
        username: string;
        message_count: string;
        last_message_at: Date;
      }>(
        `SELECT u.player_uuid, u.username, count(*)::text AS message_count, max(m.created_at) AS last_message_at
         FROM messages m
         JOIN users u ON u.player_uuid = CASE WHEN m.sender_uuid = $1 THEN m.recipient_uuid ELSE m.sender_uuid END
         WHERE m.sender_uuid = $1 OR m.recipient_uuid = $1
         GROUP BY u.player_uuid, u.username
         ORDER BY last_message_at DESC`,
        [playerUuid],
      ),
      this.pool.query<{
        group_id: string;
        name: string;
        owner_uuid: string;
        created_at: Date;
        member_count: string;
        message_count: string;
        last_message_at: Date | null;
      }>(
        `SELECT g.group_id, g.name, g.owner_uuid, g.created_at,
                count(DISTINCT members.player_uuid)::text AS member_count,
                count(DISTINCT messages.message_id)::text AS message_count,
                max(messages.created_at) AS last_message_at
         FROM group_members mine
         JOIN chat_groups g ON g.group_id = mine.group_id
         JOIN group_members members ON members.group_id = g.group_id
         LEFT JOIN group_messages messages ON messages.group_id = g.group_id
         WHERE mine.player_uuid = $1
         GROUP BY g.group_id, g.name, g.owner_uuid, g.created_at
         ORDER BY last_message_at DESC NULLS LAST, lower(g.name)`,
        [playerUuid],
      ),
    ]);
    await this.auth.audit(admin.playerUuid, "ADMIN_USER_VIEWED", "USER", playerUuid, { ip });
    return {
      player: {
        playerUuid: user.player_uuid,
        username: user.username,
        createdAt: user.created_at.toISOString(),
        lastAuthenticatedAt: user.last_authenticated_at.toISOString(),
        lastSeenAt: user.last_seen_at?.toISOString() ?? null,
      },
      directConversations: directs.rows.map((row) => ({
        playerUuid: row.player_uuid,
        username: row.username,
        messageCount: Number(row.message_count),
        lastMessageAt: row.last_message_at.toISOString(),
      })),
      groups: groups.rows.map((row) => ({
        groupId: row.group_id,
        name: row.name,
        ownerUuid: row.owner_uuid,
        createdAt: row.created_at.toISOString(),
        memberCount: Number(row.member_count),
        messageCount: Number(row.message_count),
        lastMessageAt: row.last_message_at?.toISOString() ?? null,
      })),
    };
  }

  async directMessages(
    admin: AdminActor,
    rawFirstUuid: unknown,
    rawSecondUuid: unknown,
    rawBefore: unknown,
    ip: string,
  ): Promise<unknown> {
    const firstUuid = parseUuid(rawFirstUuid, "firstUuid");
    const secondUuid = parseUuid(rawSecondUuid, "secondUuid");
    const [low, high] = orderedPair(firstUuid, secondUuid);
    const before = cursorDate(rawBefore);
    const participants = await this.pool.query<{ player_uuid: string; username: string }>(
      "SELECT player_uuid, username FROM users WHERE player_uuid IN ($1, $2) ORDER BY player_uuid",
      [low, high],
    );
    if (participants.rowCount !== 2) throw new ApiError(404, "CONVERSATION_NOT_FOUND", "That conversation is unavailable.");
    const result = await this.pool.query<{
      message_id: string;
      sender_uuid: string;
      recipient_uuid: string;
      sender_username: string;
      body: string;
      created_at: Date;
    }>(
      `SELECT m.message_id, m.sender_uuid, m.recipient_uuid, u.username AS sender_username, m.body, m.created_at
       FROM messages m JOIN users u ON u.player_uuid = m.sender_uuid
       WHERE LEAST(m.sender_uuid, m.recipient_uuid) = $1
         AND GREATEST(m.sender_uuid, m.recipient_uuid) = $2
         AND m.created_at < $3
       ORDER BY m.created_at DESC, m.message_id DESC LIMIT 100`,
      [low, high, before],
    );
    const targetId = `${low}:${high}`;
    await this.auth.audit(admin.playerUuid, "ADMIN_DIRECT_MESSAGES_VIEWED", "DIRECT_CONVERSATION", targetId, {
      ip,
      resultCount: result.rows.length,
    });
    return {
      participants: participants.rows.map((row) => ({ playerUuid: row.player_uuid, username: row.username })),
      messages: result.rows.reverse().map((row) => ({
        messageId: row.message_id,
        senderUuid: row.sender_uuid,
        recipientUuid: row.recipient_uuid,
        senderUsername: row.sender_username,
        body: row.body,
        createdAt: row.created_at.toISOString(),
      })),
    };
  }

  async groupMessages(admin: AdminActor, rawGroupId: unknown, rawBefore: unknown, ip: string): Promise<unknown> {
    const groupId = parseUuid(rawGroupId, "groupId");
    const before = cursorDate(rawBefore);
    const groupResult = await this.pool.query<{ group_id: string; name: string; owner_uuid: string }>(
      "SELECT group_id, name, owner_uuid FROM chat_groups WHERE group_id = $1",
      [groupId],
    );
    const group = groupResult.rows[0];
    if (!group) throw new ApiError(404, "GROUP_NOT_FOUND", "That group is unavailable.");
    const [members, messages] = await Promise.all([
      this.pool.query<{ player_uuid: string; username: string }>(
        `SELECT u.player_uuid, u.username FROM group_members gm
         JOIN users u ON u.player_uuid = gm.player_uuid
         WHERE gm.group_id = $1 ORDER BY lower(u.username)`,
        [groupId],
      ),
      this.pool.query<{
        message_id: string;
        sender_uuid: string;
        sender_username: string;
        body: string;
        created_at: Date;
      }>(
        `SELECT m.message_id, m.sender_uuid, u.username AS sender_username, m.body, m.created_at
         FROM group_messages m JOIN users u ON u.player_uuid = m.sender_uuid
         WHERE m.group_id = $1 AND m.created_at < $2
         ORDER BY m.created_at DESC, m.message_id DESC LIMIT 100`,
        [groupId, before],
      ),
    ]);
    await this.auth.audit(admin.playerUuid, "ADMIN_GROUP_MESSAGES_VIEWED", "GROUP", groupId, {
      ip,
      resultCount: messages.rows.length,
    });
    return {
      group: {
        groupId: group.group_id,
        name: group.name,
        ownerUuid: group.owner_uuid,
        members: members.rows.map((row) => ({ playerUuid: row.player_uuid, username: row.username })),
      },
      messages: messages.rows.reverse().map((row) => ({
        messageId: row.message_id,
        senderUuid: row.sender_uuid,
        senderUsername: row.sender_username,
        body: row.body,
        createdAt: row.created_at.toISOString(),
      })),
    };
  }

  async reports(admin: AdminActor, rawStatus: unknown, ip: string): Promise<unknown[]> {
    const status = rawStatus === undefined ? "OPEN" : parseReportStatus(rawStatus);
    const result = await this.pool.query<ReportRow>(
      `SELECT report_id, kind, source_message_id, conversation_id, reporter_uuid, reporter_username,
              reported_uuid, reported_username, selected_body, selected_created_at, context_truncated,
              reason, status, created_at, reviewed_at, reviewed_by
       FROM message_reports WHERE status = $1 ORDER BY created_at DESC LIMIT 100`,
      [status],
    );
    await this.auth.audit(admin.playerUuid, "ADMIN_REPORT_QUEUE_VIEWED", "REPORT_QUEUE", status, {
      ip,
      resultCount: result.rows.length,
    });
    return result.rows.map(reportJson);
  }

  async report(admin: AdminActor, rawReportId: unknown, ip: string): Promise<unknown> {
    const reportId = parseUuid(rawReportId, "reportId");
    const reportResult = await this.pool.query<ReportRow>(
      `SELECT report_id, kind, source_message_id, conversation_id, reporter_uuid, reporter_username,
              reported_uuid, reported_username, selected_body, selected_created_at, context_truncated,
              reason, status, created_at, reviewed_at, reviewed_by
       FROM message_reports WHERE report_id = $1`,
      [reportId],
    );
    const report = reportResult.rows[0];
    if (!report) throw new ApiError(404, "REPORT_NOT_FOUND", "That report does not exist.");
    const context = await this.pool.query<{
      sequence_number: number;
      source_message_id: string;
      sender_uuid: string;
      sender_username: string;
      body: string;
      created_at: Date;
    }>(
      `SELECT sequence_number, source_message_id, sender_uuid, sender_username, body, created_at
       FROM message_report_context WHERE report_id = $1 ORDER BY sequence_number`,
      [reportId],
    );
    await this.auth.audit(admin.playerUuid, "ADMIN_REPORT_VIEWED", "REPORT", reportId, { ip });
    return {
      ...reportJson(report),
      context: context.rows.map((row) => ({
        messageId: row.source_message_id,
        senderUuid: row.sender_uuid,
        senderUsername: row.sender_username,
        body: row.body,
        createdAt: row.created_at.toISOString(),
      })),
    };
  }

  async updateReport(admin: AdminActor, rawReportId: unknown, rawStatus: unknown, ip: string): Promise<unknown> {
    const reportId = parseUuid(rawReportId, "reportId");
    const status = parseReportStatus(rawStatus);
    const result = await this.pool.query<ReportRow>(
      `UPDATE message_reports SET status = $1, reviewed_at = now(), reviewed_by = $2
       WHERE report_id = $3
       RETURNING report_id, kind, source_message_id, conversation_id, reporter_uuid, reporter_username,
                 reported_uuid, reported_username, selected_body, selected_created_at, context_truncated,
                 reason, status, created_at, reviewed_at, reviewed_by`,
      [status, admin.playerUuid, reportId],
    );
    const report = result.rows[0];
    if (!report) throw new ApiError(404, "REPORT_NOT_FOUND", "That report does not exist.");
    await this.auth.audit(admin.playerUuid, "ADMIN_REPORT_STATUS_CHANGED", "REPORT", reportId, { ip, status });
    return reportJson(report);
  }
}

async function directBoundaries(
  client: PoolClient,
  selected: DirectReportMessageRow,
  low: string,
  high: string,
): Promise<{ previous: string; next: string }> {
  const result = await client.query<{ previous_id: string | null; next_id: string | null }>(
    `SELECT
       (SELECT message_id FROM messages
        WHERE LEAST(sender_uuid, recipient_uuid) = $1 AND GREATEST(sender_uuid, recipient_uuid) = $2
          AND sender_uuid = $3 AND message_id < $4 ORDER BY message_id DESC LIMIT 1) AS previous_id,
       (SELECT message_id FROM messages
        WHERE LEAST(sender_uuid, recipient_uuid) = $1 AND GREATEST(sender_uuid, recipient_uuid) = $2
          AND sender_uuid = $3 AND message_id > $4 ORDER BY message_id ASC LIMIT 1) AS next_id`,
    [low, high, selected.sender_uuid, selected.message_id],
  );
  const row = first(result.rows);
  return { previous: row.previous_id ?? selected.message_id, next: row.next_id ?? selected.message_id };
}

async function groupBoundaries(
  client: PoolClient,
  selected: GroupReportMessageRow,
): Promise<{ previous: string; next: string }> {
  const result = await client.query<{ previous_id: string | null; next_id: string | null }>(
    `SELECT
       (SELECT message_id FROM group_messages
        WHERE group_id = $1 AND sender_uuid = $2 AND message_id < $3 ORDER BY message_id DESC LIMIT 1) AS previous_id,
       (SELECT message_id FROM group_messages
        WHERE group_id = $1 AND sender_uuid = $2 AND message_id > $3 ORDER BY message_id ASC LIMIT 1) AS next_id`,
    [selected.group_id, selected.sender_uuid, selected.message_id],
  );
  const row = first(result.rows);
  return { previous: row.previous_id ?? selected.message_id, next: row.next_id ?? selected.message_id };
}

async function directContext(
  client: PoolClient,
  selected: DirectReportMessageRow,
  low: string,
  high: string,
  previous: string,
  next: string,
): Promise<{ messages: ContextRow[]; truncated: boolean }> {
  const count = await client.query<{ message_count: string }>(
    `SELECT count(*)::text AS message_count FROM messages
     WHERE LEAST(sender_uuid, recipient_uuid) = $1 AND GREATEST(sender_uuid, recipient_uuid) = $2
       AND message_id BETWEEN $3 AND $4`,
    [low, high, previous, next],
  );
  const result = await client.query<ContextRow>(
    `WITH chosen AS (
       (SELECT message_id FROM messages
        WHERE LEAST(sender_uuid, recipient_uuid) = $1 AND GREATEST(sender_uuid, recipient_uuid) = $2
          AND message_id BETWEEN $3 AND $5
        ORDER BY message_id DESC LIMIT 249)
       UNION
       (SELECT message_id FROM messages
        WHERE LEAST(sender_uuid, recipient_uuid) = $1 AND GREATEST(sender_uuid, recipient_uuid) = $2
          AND message_id BETWEEN $5 AND $4
        ORDER BY message_id ASC LIMIT 249)
       UNION SELECT $3::bigint
       UNION SELECT $4::bigint
     )
     SELECT m.message_id, m.sender_uuid, u.username AS sender_username, m.body, m.created_at
     FROM chosen JOIN messages m USING (message_id) JOIN users u ON u.player_uuid = m.sender_uuid
     ORDER BY m.message_id`,
    [low, high, previous, next, selected.message_id],
  );
  return { messages: result.rows, truncated: Number(first(count.rows).message_count) > result.rows.length };
}

async function groupContext(
  client: PoolClient,
  selected: GroupReportMessageRow,
  previous: string,
  next: string,
): Promise<{ messages: ContextRow[]; truncated: boolean }> {
  const count = await client.query<{ message_count: string }>(
    "SELECT count(*)::text AS message_count FROM group_messages WHERE group_id = $1 AND message_id BETWEEN $2 AND $3",
    [selected.group_id, previous, next],
  );
  const result = await client.query<ContextRow>(
    `WITH chosen AS (
       (SELECT message_id FROM group_messages
        WHERE group_id = $1 AND message_id BETWEEN $2 AND $4
        ORDER BY message_id DESC LIMIT 249)
       UNION
       (SELECT message_id FROM group_messages
        WHERE group_id = $1 AND message_id BETWEEN $4 AND $3
        ORDER BY message_id ASC LIMIT 249)
       UNION SELECT $2::bigint
       UNION SELECT $3::bigint
     )
     SELECT m.message_id, m.sender_uuid, u.username AS sender_username, m.body, m.created_at
     FROM chosen JOIN group_messages m USING (message_id) JOIN users u ON u.player_uuid = m.sender_uuid
     ORDER BY m.message_id`,
    [selected.group_id, previous, next, selected.message_id],
  );
  return { messages: result.rows, truncated: Number(first(count.rows).message_count) > result.rows.length };
}

function reportJson(row: ReportRow): Record<string, unknown> {
  return {
    reportId: row.report_id,
    kind: row.kind,
    messageId: row.source_message_id,
    conversationId: row.conversation_id,
    reporterUuid: row.reporter_uuid,
    reporterUsername: row.reporter_username,
    reportedUuid: row.reported_uuid,
    reportedUsername: row.reported_username,
    selectedBody: row.selected_body,
    selectedCreatedAt: row.selected_created_at.toISOString(),
    contextTruncated: row.context_truncated,
    reason: row.reason,
    status: row.status,
    createdAt: row.created_at.toISOString(),
    reviewedAt: row.reviewed_at?.toISOString() ?? null,
    reviewedBy: row.reviewed_by,
  };
}

function adminSearch(value: unknown): string {
  if (value === undefined) return "";
  if (typeof value !== "string") throw new ApiError(400, "INVALID_SEARCH", "Search text must be a string.");
  const search = value.trim().toLowerCase();
  if (search.length > 16 || (search.length > 0 && !/^[a-z0-9_]+$/.test(search))) {
    throw new ApiError(400, "INVALID_SEARCH", "Search text must contain at most 16 Minecraft username characters.");
  }
  return search;
}

function cursorDate(value: unknown): Date {
  if (value === undefined) return new Date();
  if (typeof value !== "string") throw new ApiError(400, "INVALID_CURSOR", "before must be an ISO timestamp.");
  const before = new Date(value);
  if (Number.isNaN(before.getTime())) throw new ApiError(400, "INVALID_CURSOR", "before must be an ISO timestamp.");
  return before;
}

function isUniqueViolation(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && error.code === "23505";
}
