import { randomUUID } from "node:crypto";
import type { Pool, PoolClient } from "pg";
import { first, transaction } from "./db.js";
import {
  ApiError,
  hashToken,
  normalizePlayerSearch,
  normalizeUsername,
  orderedPair,
  parseClientMessageId,
  parsePresenceStatus,
  parseUuid,
  randomToken,
  validateGroupName,
  validateMessage,
} from "./domain.js";
import { verifyMinecraftJoin, type VerifiedMinecraftProfile } from "./minecraft.js";
import type { RealtimeHub } from "./realtime.js";
import type { Actor, ChatMessage, GroupChatMessage, GroupSummary, PublicPlayer } from "./types.js";

interface ChallengeRow {
  username: string;
  server_id: string;
}

interface ActorRow {
  player_uuid: string;
  username: string;
}

interface PlayerRow extends ActorRow {
  last_seen_at: Date | null;
}

interface RequestRow {
  request_id: string;
  player_uuid: string;
  username: string;
  created_at: Date;
}

interface MessageRow {
  message_id: string;
  client_message_id: string;
  sender_uuid: string;
  recipient_uuid: string;
  sender_username_at_send: string;
  body: string;
  created_at: Date;
  read_at: Date | null;
  state: "SENT" | "EDITED" | "DELETED";
  edited_at: Date | null;
  deleted_at: Date | null;
  reply_to_message_id: string | null;
  attachment_kind: string | null;
  attachment_label: string | null;
}

interface PlayerSearchRow extends ActorRow {
  relationship: "FRIEND" | "INCOMING" | "OUTGOING" | "NONE";
  request_id: string | null;
}

interface GroupDetailsRow extends PlayerRow {
  group_id: string;
  group_name: string;
  owner_uuid: string;
  group_created_at: Date;
}

interface GroupMessageRow {
  message_id: string;
  client_message_id: string;
  group_id: string;
  sender_uuid: string;
  sender_username_at_send: string;
  body: string;
  created_at: Date;
  state: "SENT" | "EDITED" | "DELETED";
  edited_at: Date | null;
  deleted_at: Date | null;
  reply_to_message_id: string | null;
  attachment_kind: string | null;
  attachment_label: string | null;
}

function publicPlayer(row: PlayerRow, realtime: RealtimeHub): PublicPlayer {
  const status = realtime.status(row.player_uuid);
  return {
    playerUuid: row.player_uuid,
    username: row.username,
    online: status !== "OFFLINE",
    status,
    lastSeenAt: row.last_seen_at?.toISOString() ?? null,
  };
}

function chatMessage(row: MessageRow): ChatMessage {
  return {
    messageId: row.message_id,
    clientMessageId: row.client_message_id,
    senderUuid: row.sender_uuid,
    recipientUuid: row.recipient_uuid,
    senderUsernameAtSend: row.sender_username_at_send,
    body: row.body,
    createdAt: row.created_at.toISOString(),
    readAt: row.read_at?.toISOString() ?? null,
    state: row.state,
    editedAt: row.edited_at?.toISOString() ?? null,
    deletedAt: row.deleted_at?.toISOString() ?? null,
    replyToMessageId: row.reply_to_message_id,
    attachmentKind: row.attachment_kind,
    attachmentLabel: row.attachment_label,
  };
}

function groupChatMessage(row: GroupMessageRow): GroupChatMessage {
  return {
    messageId: row.message_id,
    clientMessageId: row.client_message_id,
    groupId: row.group_id,
    senderUuid: row.sender_uuid,
    senderUsernameAtSend: row.sender_username_at_send,
    body: row.body,
    createdAt: row.created_at.toISOString(),
    state: row.state,
    editedAt: row.edited_at?.toISOString() ?? null,
    deletedAt: row.deleted_at?.toISOString() ?? null,
    replyToMessageId: row.reply_to_message_id,
    attachmentKind: row.attachment_kind,
    attachmentLabel: row.attachment_label,
  };
}

export class AuthService {
  constructor(
    private readonly pool: Pool,
    private readonly sessionTtlHours: number,
  ) {}

  async createChallenge(rawUsername: unknown): Promise<{ challengeId: string; serverId: string; expiresAt: string }> {
    normalizeUsername(rawUsername);
    const username = rawUsername as string;
    const challengeId = randomUUID();
    const serverId = randomToken(24);
    const result = await this.pool.query<{ expires_at: Date }>(
      `INSERT INTO auth_challenges(challenge_id, username, server_id, expires_at)
       VALUES ($1, $2, $3, now() + interval '2 minutes') RETURNING expires_at`,
      [challengeId, username, serverId],
    );
    return { challengeId, serverId, expiresAt: first(result.rows).expires_at.toISOString() };
  }

  async completeChallenge(rawChallengeId: unknown): Promise<{
    token: string;
    expiresAt: string;
    player: { playerUuid: string; username: string };
  }> {
    const challengeId = parseUuid(rawChallengeId, "challengeId");
    const claimed = await this.pool.query<ChallengeRow>(
      `UPDATE auth_challenges SET used_at = now()
       WHERE challenge_id = $1 AND used_at IS NULL AND expires_at > now()
       RETURNING username, server_id`,
      [challengeId],
    );
    if (claimed.rowCount !== 1) {
      throw new ApiError(401, "CHALLENGE_INVALID", "This login challenge is invalid or expired.");
    }

    const challenge = first(claimed.rows);
    const profile = await verifyMinecraftJoin(challenge.username, challenge.server_id);
    return this.issueSession(profile);
  }

  async actorForToken(token: string): Promise<Actor> {
    const result = await this.pool.query<ActorRow>(
      `SELECT u.player_uuid, u.username
       FROM sessions s JOIN users u ON u.player_uuid = s.player_uuid
       WHERE s.token_hash = $1 AND s.expires_at > now()`,
      [hashToken(token)],
    );
    const row = result.rows[0];
    if (!row) throw new ApiError(401, "UNAUTHENTICATED", "The session is invalid or expired.");
    return { playerUuid: row.player_uuid, username: row.username };
  }

  async revoke(token: string): Promise<void> {
    await this.pool.query("DELETE FROM sessions WHERE token_hash = $1", [hashToken(token)]);
  }

  async cleanExpired(): Promise<void> {
    await this.pool.query("DELETE FROM auth_challenges WHERE expires_at < now() - interval '1 hour'");
    await this.pool.query("DELETE FROM sessions WHERE expires_at < now()");
  }

  private async issueSession(profile: VerifiedMinecraftProfile): Promise<{
    token: string;
    expiresAt: string;
    player: { playerUuid: string; username: string };
  }> {
    const token = randomToken();
    const tokenHash = hashToken(token);
    const normalized = profile.username.toLowerCase();
    const expiresAt = await transaction(this.pool, async (client) => {
      await client.query(
        "UPDATE users SET normalized_username = NULL WHERE normalized_username = $1 AND player_uuid <> $2",
        [normalized, profile.playerUuid],
      );
      await client.query(
        `INSERT INTO users(player_uuid, username, normalized_username)
         VALUES ($1, $2, $3)
         ON CONFLICT (player_uuid) DO UPDATE SET
           username = EXCLUDED.username,
           normalized_username = EXCLUDED.normalized_username,
           last_authenticated_at = now()`,
        [profile.playerUuid, profile.username, normalized],
      );
      const inserted = await client.query<{ expires_at: Date }>(
        `INSERT INTO sessions(token_hash, player_uuid, expires_at)
         VALUES ($1, $2, now() + ($3 * interval '1 hour')) RETURNING expires_at`,
        [tokenHash, profile.playerUuid, this.sessionTtlHours],
      );
      await client.query(
        `DELETE FROM sessions WHERE token_hash IN (
           SELECT token_hash FROM sessions WHERE player_uuid = $1 ORDER BY created_at DESC OFFSET 5
         )`,
        [profile.playerUuid],
      );
      return first(inserted.rows).expires_at.toISOString();
    });
    return {
      token,
      expiresAt,
      player: { playerUuid: profile.playerUuid, username: profile.username },
    };
  }
}

export class SocialService {
  constructor(
    private readonly pool: Pool,
    private readonly realtime: RealtimeHub,
  ) {}

  async snapshot(actor: Actor): Promise<unknown> {
    const [friends, incoming, outgoing, blocked, groups] = await Promise.all([
      this.pool.query<PlayerRow & { friends_since: Date }>(
        `SELECT u.player_uuid, u.username, u.last_seen_at, f.created_at AS friends_since
         FROM friendships f
         JOIN users u ON u.player_uuid = CASE WHEN f.player_low = $1 THEN f.player_high ELSE f.player_low END
         WHERE f.player_low = $1 OR f.player_high = $1 ORDER BY lower(u.username)`,
        [actor.playerUuid],
      ),
      this.pool.query<RequestRow>(
        `SELECT r.request_id, u.player_uuid, u.username, r.created_at
         FROM friend_requests r JOIN users u ON u.player_uuid = r.sender_uuid
         WHERE r.recipient_uuid = $1 ORDER BY r.created_at DESC`,
        [actor.playerUuid],
      ),
      this.pool.query<RequestRow>(
        `SELECT r.request_id, u.player_uuid, u.username, r.created_at
         FROM friend_requests r JOIN users u ON u.player_uuid = r.recipient_uuid
         WHERE r.sender_uuid = $1 ORDER BY r.created_at DESC`,
        [actor.playerUuid],
      ),
      this.pool.query<PlayerRow & { blocked_at: Date }>(
        `SELECT u.player_uuid, u.username, u.last_seen_at, b.created_at AS blocked_at
         FROM blocks b JOIN users u ON u.player_uuid = b.blocked_uuid
         WHERE b.blocker_uuid = $1 ORDER BY lower(u.username)`,
        [actor.playerUuid],
      ),
      this.groups(actor),
    ]);

    const request = (row: RequestRow) => ({
      requestId: row.request_id,
      player: publicPlayer({ ...row, last_seen_at: null }, this.realtime),
      createdAt: row.created_at.toISOString(),
    });
    return {
      me: { playerUuid: actor.playerUuid, username: actor.username },
      friends: friends.rows.map((row) => ({
        ...publicPlayer(row, this.realtime),
        friendsSince: row.friends_since.toISOString(),
      })),
      incoming: incoming.rows.map(request),
      outgoing: outgoing.rows.map(request),
      blocked: blocked.rows.map((row) => ({
        ...publicPlayer(row, this.realtime),
        blockedAt: row.blocked_at.toISOString(),
      })),
      groups,
    };
  }

  async searchPlayers(actor: Actor, rawQuery: unknown): Promise<unknown[]> {
    const query = normalizePlayerSearch(rawQuery);
    const result = await this.pool.query<PlayerSearchRow>(
      `SELECT u.player_uuid, u.username,
         CASE
           WHEN f.player_low IS NOT NULL THEN 'FRIEND'
           WHEN incoming.request_id IS NOT NULL THEN 'INCOMING'
           WHEN outgoing.request_id IS NOT NULL THEN 'OUTGOING'
           ELSE 'NONE'
         END AS relationship,
         COALESCE(incoming.request_id, outgoing.request_id) AS request_id
       FROM users u
       LEFT JOIN friendships f
         ON f.player_low = LEAST(u.player_uuid, $1::uuid)
        AND f.player_high = GREATEST(u.player_uuid, $1::uuid)
       LEFT JOIN friend_requests incoming
         ON incoming.sender_uuid = u.player_uuid AND incoming.recipient_uuid = $1
       LEFT JOIN friend_requests outgoing
         ON outgoing.sender_uuid = $1 AND outgoing.recipient_uuid = u.player_uuid
       WHERE u.player_uuid <> $1
         AND u.normalized_username IS NOT NULL
         AND strpos(u.normalized_username, $2) > 0
         AND NOT EXISTS (
           SELECT 1 FROM social_access_restrictions restriction
           WHERE restriction.player_uuid = u.player_uuid AND restriction.restored_at IS NULL
         )
         AND NOT EXISTS (
           SELECT 1 FROM blocks b
           WHERE (b.blocker_uuid = $1 AND b.blocked_uuid = u.player_uuid)
              OR (b.blocker_uuid = u.player_uuid AND b.blocked_uuid = $1)
         )
       ORDER BY
         CASE WHEN strpos(u.normalized_username, $2) = 1 THEN 0 ELSE 1 END,
         length(u.normalized_username),
         u.normalized_username
       LIMIT 8`,
      [actor.playerUuid, query],
    );
    return result.rows.map((row) => ({
      playerUuid: row.player_uuid,
      username: row.username,
      relationship: row.relationship,
      requestId: row.request_id,
    }));
  }

  async sendRequest(actor: Actor, rawUsername: unknown): Promise<unknown> {
    const normalized = normalizeUsername(rawUsername);
    const targetResult = await this.pool.query<PlayerRow>(
      "SELECT player_uuid, username, last_seen_at FROM users WHERE normalized_username = $1",
      [normalized],
    );
    const target = targetResult.rows[0];
    if (!target) throw new ApiError(404, "PLAYER_NOT_FOUND", "No G'Lib player with that username was found.");
    if (target.player_uuid === actor.playerUuid) throw new ApiError(400, "SELF_TARGET", "You cannot friend yourself.");
    await this.assertSocialAvailable(this.pool, target.player_uuid);

    const requestId = await transaction(this.pool, async (client) => {
      await this.assertNotBlocked(client, actor.playerUuid, target.player_uuid);
      const [low, high] = orderedPair(actor.playerUuid, target.player_uuid);
      if ((await client.query("SELECT 1 FROM friendships WHERE player_low = $1 AND player_high = $2", [low, high])).rowCount) {
        throw new ApiError(409, "ALREADY_FRIENDS", "You are already friends.");
      }
      if ((await client.query(
        "SELECT 1 FROM friend_requests WHERE sender_uuid = $1 AND recipient_uuid = $2",
        [target.player_uuid, actor.playerUuid],
      )).rowCount) {
        throw new ApiError(409, "INCOMING_REQUEST_EXISTS", "This player has already sent you a request.");
      }
      const id = randomUUID();
      try {
        await client.query(
          "INSERT INTO friend_requests(request_id, sender_uuid, recipient_uuid) VALUES ($1, $2, $3)",
          [id, actor.playerUuid, target.player_uuid],
        );
      } catch (error) {
        if (isUniqueViolation(error)) throw new ApiError(409, "REQUEST_EXISTS", "A friend request is already pending.");
        throw error;
      }
      return id;
    });

    const event = {
      requestId,
      player: {
        playerUuid: actor.playerUuid,
        username: actor.username,
        online: this.realtime.isOnline(actor.playerUuid),
        status: this.realtime.status(actor.playerUuid),
        lastSeenAt: null,
      },
      createdAt: new Date().toISOString(),
    };
    this.realtime.send(target.player_uuid, "friend.request", event);
    return event;
  }

  async acceptRequest(actor: Actor, rawRequestId: unknown): Promise<unknown> {
    const requestId = parseUuid(rawRequestId, "requestId");
    const sender = await transaction(this.pool, async (client) => {
      const request = await client.query<ActorRow>(
        `SELECT u.player_uuid, u.username FROM friend_requests r
         JOIN users u ON u.player_uuid = r.sender_uuid
         WHERE r.request_id = $1 AND r.recipient_uuid = $2 FOR UPDATE`,
        [requestId, actor.playerUuid],
      );
      const row = request.rows[0];
      if (!row) throw new ApiError(404, "REQUEST_NOT_FOUND", "That friend request no longer exists.");
      await this.assertSocialAvailable(client, row.player_uuid);
      await this.assertNotBlocked(client, actor.playerUuid, row.player_uuid);
      const [low, high] = orderedPair(actor.playerUuid, row.player_uuid);
      await client.query(
        "INSERT INTO friendships(player_low, player_high) VALUES ($1, $2) ON CONFLICT DO NOTHING",
        [low, high],
      );
      await client.query(
        `DELETE FROM friend_requests
         WHERE (sender_uuid = $1 AND recipient_uuid = $2) OR (sender_uuid = $2 AND recipient_uuid = $1)`,
        [actor.playerUuid, row.player_uuid],
      );
      return row;
    });

    this.realtime.send(sender.player_uuid, "friend.accepted", {
      player: {
        playerUuid: actor.playerUuid,
        username: actor.username,
        online: this.realtime.isOnline(actor.playerUuid),
        status: this.realtime.status(actor.playerUuid),
        lastSeenAt: null,
      },
    });
    return { playerUuid: sender.player_uuid, username: sender.username };
  }

  async deleteRequest(actor: Actor, rawRequestId: unknown): Promise<void> {
    const requestId = parseUuid(rawRequestId, "requestId");
    const result = await this.pool.query<{ sender_uuid: string; recipient_uuid: string }>(
      `DELETE FROM friend_requests r
       WHERE request_id = $1 AND (sender_uuid = $2 OR recipient_uuid = $2)
       RETURNING sender_uuid, recipient_uuid`,
      [requestId, actor.playerUuid],
    );
    if (result.rowCount !== 1) throw new ApiError(404, "REQUEST_NOT_FOUND", "That friend request no longer exists.");
    const row = first(result.rows);
    const other = row.sender_uuid === actor.playerUuid ? row.recipient_uuid : row.sender_uuid;
    this.realtime.send(other, "friend.request_removed", { requestId });
  }

  async removeFriend(actor: Actor, rawPlayerUuid: unknown): Promise<void> {
    const targetUuid = parseUuid(rawPlayerUuid);
    const [low, high] = orderedPair(actor.playerUuid, targetUuid);
    const result = await this.pool.query("DELETE FROM friendships WHERE player_low = $1 AND player_high = $2", [low, high]);
    if (result.rowCount !== 1) throw new ApiError(404, "FRIEND_NOT_FOUND", "That player is not your friend.");
    this.realtime.send(targetUuid, "friend.removed", { playerUuid: actor.playerUuid });
  }

  async block(actor: Actor, rawPlayerUuid: unknown): Promise<void> {
    const targetUuid = parseUuid(rawPlayerUuid);
    const [low, high] = orderedPair(actor.playerUuid, targetUuid);
    await transaction(this.pool, async (client) => {
      if (!(await client.query("SELECT 1 FROM users WHERE player_uuid = $1", [targetUuid])).rowCount) {
        throw new ApiError(404, "PLAYER_NOT_FOUND", "That player does not exist.");
      }
      await client.query(
        "INSERT INTO blocks(blocker_uuid, blocked_uuid) VALUES ($1, $2) ON CONFLICT DO NOTHING",
        [actor.playerUuid, targetUuid],
      );
      await client.query("DELETE FROM friendships WHERE player_low = $1 AND player_high = $2", [low, high]);
      await client.query(
        `DELETE FROM friend_requests
         WHERE (sender_uuid = $1 AND recipient_uuid = $2) OR (sender_uuid = $2 AND recipient_uuid = $1)`,
        [actor.playerUuid, targetUuid],
      );
    });
    this.realtime.send(targetUuid, "friend.removed", { playerUuid: actor.playerUuid });
  }

  async unblock(actor: Actor, rawPlayerUuid: unknown): Promise<void> {
    const targetUuid = parseUuid(rawPlayerUuid);
    await this.pool.query("DELETE FROM blocks WHERE blocker_uuid = $1 AND blocked_uuid = $2", [actor.playerUuid, targetUuid]);
  }

  async deleteAccount(actor: Actor): Promise<void> {
    const friends = await this.pool.query<{ friend_uuid: string }>(
      `SELECT CASE WHEN player_low = $1 THEN player_high ELSE player_low END AS friend_uuid
       FROM friendships WHERE player_low = $1 OR player_high = $1`,
      [actor.playerUuid],
    );
    await this.pool.query("DELETE FROM users WHERE player_uuid = $1", [actor.playerUuid]);
    for (const friend of friends.rows) {
      this.realtime.send(friend.friend_uuid, "friend.removed", { playerUuid: actor.playerUuid });
    }
  }

  async messages(actor: Actor, rawTargetUuid: unknown, rawBefore: unknown): Promise<ChatMessage[]> {
    const targetUuid = parseUuid(rawTargetUuid);
    await this.assertFriends(this.pool, actor.playerUuid, targetUuid);
    let before = new Date();
    if (typeof rawBefore === "string") {
      before = new Date(rawBefore);
      if (Number.isNaN(before.getTime())) throw new ApiError(400, "INVALID_CURSOR", "before must be an ISO timestamp.");
    }
    const result = await this.pool.query<MessageRow>(
      `SELECT message_id, client_message_id, sender_uuid, recipient_uuid, sender_username_at_send,
              body, created_at, read_at, state, edited_at, deleted_at, reply_to_message_id,
              attachment_kind, attachment_label
       FROM messages
       WHERE ((sender_uuid = $1 AND recipient_uuid = $2) OR (sender_uuid = $2 AND recipient_uuid = $1))
         AND created_at < $3
       ORDER BY created_at DESC LIMIT 100`,
      [actor.playerUuid, targetUuid, before],
    );
    await this.pool.query(
      "UPDATE messages SET read_at = now() WHERE sender_uuid = $1 AND recipient_uuid = $2 AND read_at IS NULL",
      [targetUuid, actor.playerUuid],
    );
    this.realtime.send(targetUuid, "chat.read", { byPlayerUuid: actor.playerUuid });
    return result.rows.reverse().map(chatMessage);
  }

  async sendMessage(actor: Actor, rawTargetUuid: unknown, rawClientMessageId: unknown, rawBody: unknown): Promise<ChatMessage> {
    const targetUuid = parseUuid(rawTargetUuid);
    const clientMessageId = parseClientMessageId(rawClientMessageId);
    const body = validateMessage(rawBody);
    await this.assertFriends(this.pool, actor.playerUuid, targetUuid);
    await this.assertNotBlocked(this.pool, actor.playerUuid, targetUuid);
    await this.assertSocialAvailable(this.pool, targetUuid);

    const result = await this.pool.query<MessageRow>(
      `INSERT INTO messages(client_message_id, sender_uuid, recipient_uuid, sender_username_at_send, body)
       VALUES ($1, $2, $3, $4, $5)
       ON CONFLICT (sender_uuid, client_message_id) DO UPDATE SET body = messages.body
       WHERE messages.recipient_uuid = EXCLUDED.recipient_uuid AND messages.body = EXCLUDED.body
       RETURNING message_id, client_message_id, sender_uuid, recipient_uuid, sender_username_at_send,
                 body, created_at, read_at, state, edited_at, deleted_at, reply_to_message_id,
                 attachment_kind, attachment_label`,
      [clientMessageId, actor.playerUuid, targetUuid, actor.username, body],
    );
    if (result.rowCount !== 1) {
      throw new ApiError(409, "CLIENT_MESSAGE_ID_REUSED", "That client message ID was already used for different content.");
    }
    const message = chatMessage(first(result.rows));
    this.realtime.send(targetUuid, "chat.message", message);
    this.realtime.send(actor.playerUuid, "chat.message", message);
    return message;
  }

  async sendTyping(actor: Actor, rawTargetUuid: unknown, rawTyping: unknown): Promise<void> {
    const targetUuid = parseUuid(rawTargetUuid);
    if (typeof rawTyping !== "boolean") {
      throw new ApiError(400, "INVALID_TYPING_STATE", "typing must be true or false.");
    }
    await this.assertFriends(this.pool, actor.playerUuid, targetUuid);
    await this.assertNotBlocked(this.pool, actor.playerUuid, targetUuid);
    await this.assertSocialAvailable(this.pool, targetUuid);
    this.realtime.send(targetUuid, "chat.typing", {
      playerUuid: actor.playerUuid,
      username: actor.username,
      typing: rawTyping,
    });
  }

  async setPresence(actor: Actor, rawStatus: unknown): Promise<void> {
    await this.realtime.setStatus(actor, parsePresenceStatus(rawStatus));
  }

  async groups(actor: Actor): Promise<GroupSummary[]> {
    return this.loadGroups(actor.playerUuid, null);
  }

  async group(actor: Actor, rawGroupId: unknown): Promise<GroupSummary> {
    const groupId = parseUuid(rawGroupId, "groupId");
    const group = (await this.loadGroups(actor.playerUuid, groupId))[0];
    if (!group) throw new ApiError(404, "GROUP_NOT_FOUND", "That group does not exist or you are not a member.");
    return group;
  }

  async createGroup(actor: Actor, rawName: unknown, rawMemberUuids: unknown): Promise<GroupSummary> {
    const name = validateGroupName(rawName);
    const memberUuids = this.parseGroupMembers(actor.playerUuid, rawMemberUuids);
    const groupId = randomUUID();
    await transaction(this.pool, async (client) => {
      for (const memberUuid of memberUuids) {
        await this.assertFriends(client, actor.playerUuid, memberUuid);
        await this.assertNotBlocked(client, actor.playerUuid, memberUuid);
        await this.assertSocialAvailable(client, memberUuid);
      }
      await client.query(
        "INSERT INTO chat_groups(group_id, name, owner_uuid) VALUES ($1, $2, $3)",
        [groupId, name, actor.playerUuid],
      );
      await client.query(
        "INSERT INTO group_members(group_id, player_uuid) VALUES ($1, $2)",
        [groupId, actor.playerUuid],
      );
      for (const memberUuid of memberUuids) {
        await client.query(
          "INSERT INTO group_members(group_id, player_uuid) VALUES ($1, $2)",
          [groupId, memberUuid],
        );
      }
    });
    const group = await this.group(actor, groupId);
    await this.realtime.sendToGroup(groupId, "group.created", group);
    return group;
  }

  async renameGroup(actor: Actor, rawGroupId: unknown, rawName: unknown): Promise<GroupSummary> {
    const groupId = parseUuid(rawGroupId, "groupId");
    const name = validateGroupName(rawName);
    await this.assertGroupMember(this.pool, groupId, actor.playerUuid);
    await this.pool.query("UPDATE chat_groups SET name = $1 WHERE group_id = $2", [name, groupId]);
    const group = await this.group(actor, groupId);
    await this.realtime.sendToGroup(groupId, "group.updated", group);
    return group;
  }

  async addGroupMember(actor: Actor, rawGroupId: unknown, rawPlayerUuid: unknown): Promise<GroupSummary> {
    const groupId = parseUuid(rawGroupId, "groupId");
    const playerUuid = parseUuid(rawPlayerUuid);
    if (playerUuid === actor.playerUuid) throw new ApiError(409, "ALREADY_GROUP_MEMBER", "You are already in this group.");
    await transaction(this.pool, async (client) => {
      const locked = await client.query(
        `SELECT g.group_id FROM chat_groups g
         JOIN group_members mine ON mine.group_id = g.group_id AND mine.player_uuid = $2
         WHERE g.group_id = $1 FOR UPDATE OF g`,
        [groupId, actor.playerUuid],
      );
      if (locked.rowCount !== 1) throw new ApiError(404, "GROUP_NOT_FOUND", "That group does not exist or you are not a member.");
      await this.assertFriends(client, actor.playerUuid, playerUuid);
      await this.assertNotBlocked(client, actor.playerUuid, playerUuid);
      await this.assertSocialAvailable(client, playerUuid);
      const existing = await client.query(
        "SELECT 1 FROM group_members WHERE group_id = $1 AND player_uuid = $2",
        [groupId, playerUuid],
      );
      if (existing.rowCount) throw new ApiError(409, "ALREADY_GROUP_MEMBER", "That player is already in this group.");
      const count = await client.query<{ member_count: string }>(
        "SELECT count(*)::text AS member_count FROM group_members WHERE group_id = $1",
        [groupId],
      );
      if (Number(first(count.rows).member_count) >= 20) {
        throw new ApiError(409, "GROUP_FULL", "Groups can contain at most 20 players.");
      }
      await client.query(
        "INSERT INTO group_members(group_id, player_uuid) VALUES ($1, $2)",
        [groupId, playerUuid],
      );
    });
    const group = await this.group(actor, groupId);
    await this.realtime.sendToGroup(groupId, "group.updated", group);
    return group;
  }

  async removeGroupMember(actor: Actor, rawGroupId: unknown, rawPlayerUuid: unknown): Promise<void> {
    const groupId = parseUuid(rawGroupId, "groupId");
    const playerUuid = parseUuid(rawPlayerUuid);
    const remainingPlayerUuid = await transaction(this.pool, async (client) => {
      const groupResult = await client.query<{ owner_uuid: string }>(
        `SELECT g.owner_uuid FROM chat_groups g
         JOIN group_members mine ON mine.group_id = g.group_id AND mine.player_uuid = $2
         WHERE g.group_id = $1 FOR UPDATE OF g`,
        [groupId, actor.playerUuid],
      );
      const group = groupResult.rows[0];
      if (!group) throw new ApiError(404, "GROUP_NOT_FOUND", "That group does not exist or you are not a member.");
      if (playerUuid !== actor.playerUuid && group.owner_uuid !== actor.playerUuid) {
        throw new ApiError(403, "GROUP_OWNER_REQUIRED", "Only the group owner can remove another member.");
      }
      const removed = await client.query(
        "DELETE FROM group_members WHERE group_id = $1 AND player_uuid = $2",
        [groupId, playerUuid],
      );
      if (removed.rowCount !== 1) throw new ApiError(404, "GROUP_MEMBER_NOT_FOUND", "That player is not in this group.");

      const remaining = await client.query<{ player_uuid: string }>(
        "SELECT player_uuid FROM group_members WHERE group_id = $1 ORDER BY joined_at, player_uuid LIMIT 1",
        [groupId],
      );
      const next = remaining.rows[0]?.player_uuid ?? null;
      if (group.owner_uuid === playerUuid) {
        if (next) await client.query("UPDATE chat_groups SET owner_uuid = $1 WHERE group_id = $2", [next, groupId]);
        else await client.query("DELETE FROM chat_groups WHERE group_id = $1", [groupId]);
      }
      return next;
    });

    this.realtime.send(playerUuid, "group.removed", { groupId });
    if (remainingPlayerUuid) {
      const group = (await this.loadGroups(remainingPlayerUuid, groupId))[0];
      if (group) await this.realtime.sendToGroup(groupId, "group.updated", group);
    }
  }

  async groupMessages(actor: Actor, rawGroupId: unknown, rawBefore: unknown): Promise<GroupChatMessage[]> {
    const groupId = parseUuid(rawGroupId, "groupId");
    await this.assertGroupMember(this.pool, groupId, actor.playerUuid);
    let before = new Date();
    if (typeof rawBefore === "string") {
      before = new Date(rawBefore);
      if (Number.isNaN(before.getTime())) throw new ApiError(400, "INVALID_CURSOR", "before must be an ISO timestamp.");
    }
    const result = await this.pool.query<GroupMessageRow>(
      `SELECT message_id, client_message_id, group_id, sender_uuid, sender_username_at_send,
              body, created_at, state, edited_at, deleted_at, reply_to_message_id,
              attachment_kind, attachment_label
       FROM group_messages WHERE group_id = $1 AND created_at < $2
       ORDER BY created_at DESC LIMIT 100`,
      [groupId, before],
    );
    return result.rows.reverse().map(groupChatMessage);
  }

  async sendGroupMessage(actor: Actor, rawGroupId: unknown, rawClientMessageId: unknown, rawBody: unknown): Promise<GroupChatMessage> {
    const groupId = parseUuid(rawGroupId, "groupId");
    const clientMessageId = parseClientMessageId(rawClientMessageId);
    const body = validateMessage(rawBody);
    await this.assertGroupMember(this.pool, groupId, actor.playerUuid);
    const result = await this.pool.query<GroupMessageRow>(
      `INSERT INTO group_messages(group_id, client_message_id, sender_uuid, sender_username_at_send, body)
       VALUES ($1, $2, $3, $4, $5)
       ON CONFLICT (sender_uuid, client_message_id) DO UPDATE SET body = group_messages.body
       WHERE group_messages.group_id = EXCLUDED.group_id AND group_messages.body = EXCLUDED.body
       RETURNING message_id, client_message_id, group_id, sender_uuid, sender_username_at_send,
                 body, created_at, state, edited_at, deleted_at, reply_to_message_id,
                 attachment_kind, attachment_label`,
      [groupId, clientMessageId, actor.playerUuid, actor.username, body],
    );
    if (result.rowCount !== 1) {
      throw new ApiError(409, "CLIENT_MESSAGE_ID_REUSED", "That client message ID was already used for different content.");
    }
    const message = groupChatMessage(first(result.rows));
    await this.realtime.sendToGroup(groupId, "group.message", message);
    return message;
  }

  async sendGroupTyping(actor: Actor, rawGroupId: unknown, rawTyping: unknown): Promise<void> {
    const groupId = parseUuid(rawGroupId, "groupId");
    if (typeof rawTyping !== "boolean") {
      throw new ApiError(400, "INVALID_TYPING_STATE", "typing must be true or false.");
    }
    await this.assertGroupMember(this.pool, groupId, actor.playerUuid);
    await this.realtime.sendToGroup(groupId, "group.typing", {
      groupId,
      playerUuid: actor.playerUuid,
      username: actor.username,
      typing: rawTyping,
    }, actor.playerUuid);
  }

  async cleanMessages(retentionDays: number): Promise<void> {
    await Promise.all([
      this.pool.query(
        `UPDATE messages SET body = '[removed]', state = 'DELETED', deleted_at = COALESCE(deleted_at, now()),
            attachment_kind = NULL, attachment_label = NULL
         WHERE created_at < now() - ($1 * interval '1 day') AND state <> 'DELETED'`,
        [retentionDays],
      ),
      this.pool.query(
        `UPDATE group_messages SET body = '[removed]', state = 'DELETED', deleted_at = COALESCE(deleted_at, now()),
            attachment_kind = NULL, attachment_label = NULL
         WHERE created_at < now() - ($1 * interval '1 day') AND state <> 'DELETED'`,
        [retentionDays],
      ),
      this.pool.query("DELETE FROM messages WHERE deleted_at < now() - interval '30 days'"),
      this.pool.query("DELETE FROM group_messages WHERE deleted_at < now() - interval '30 days'"),
    ]);
  }

  private async loadGroups(playerUuid: string, groupId: string | null): Promise<GroupSummary[]> {
    const result = await this.pool.query<GroupDetailsRow>(
      `SELECT g.group_id, g.name AS group_name, g.owner_uuid, g.created_at AS group_created_at,
              u.player_uuid, u.username, u.last_seen_at
       FROM group_members mine
       JOIN chat_groups g ON g.group_id = mine.group_id
       JOIN group_members member ON member.group_id = g.group_id
       JOIN users u ON u.player_uuid = member.player_uuid
       WHERE mine.player_uuid = $1 AND ($2::uuid IS NULL OR g.group_id = $2)
       ORDER BY g.created_at DESC, lower(u.username)`,
      [playerUuid, groupId],
    );
    const groups = new Map<string, GroupSummary>();
    for (const row of result.rows) {
      let group = groups.get(row.group_id);
      if (!group) {
        group = {
          groupId: row.group_id,
          name: row.group_name,
          ownerUuid: row.owner_uuid,
          members: [],
          createdAt: row.group_created_at.toISOString(),
        };
        groups.set(row.group_id, group);
      }
      group.members.push(publicPlayer(row, this.realtime));
    }
    return [...groups.values()];
  }

  private parseGroupMembers(actorUuid: string, rawMemberUuids: unknown): string[] {
    if (!Array.isArray(rawMemberUuids)) {
      throw new ApiError(400, "INVALID_GROUP_MEMBERS", "memberUuids must be a list.");
    }
    const members = new Set<string>();
    for (const rawUuid of rawMemberUuids) {
      const playerUuid = parseUuid(rawUuid);
      if (playerUuid === actorUuid) throw new ApiError(400, "SELF_TARGET", "You are added to groups automatically.");
      members.add(playerUuid);
    }
    if (members.size === 0) throw new ApiError(400, "GROUP_MEMBERS_REQUIRED", "Choose at least one friend for the group.");
    if (members.size > 19) throw new ApiError(400, "GROUP_FULL", "Groups can contain at most 20 players.");
    return [...members];
  }

  private async assertGroupMember(client: Pool | PoolClient, groupId: string, playerUuid: string): Promise<void> {
    if (!(await client.query(
      "SELECT 1 FROM group_members WHERE group_id = $1 AND player_uuid = $2",
      [groupId, playerUuid],
    )).rowCount) {
      throw new ApiError(403, "NOT_GROUP_MEMBER", "You are not a member of this group.");
    }
  }

  private async assertFriends(client: Pool | PoolClient, firstUuid: string, secondUuid: string): Promise<void> {
    const [low, high] = orderedPair(firstUuid, secondUuid);
    if (!(await client.query("SELECT 1 FROM friendships WHERE player_low = $1 AND player_high = $2", [low, high])).rowCount) {
      throw new ApiError(403, "NOT_FRIENDS", "You can only chat with friends.");
    }
  }

  private async assertNotBlocked(client: Pool | PoolClient, firstUuid: string, secondUuid: string): Promise<void> {
    if ((await client.query(
      `SELECT 1 FROM blocks
       WHERE (blocker_uuid = $1 AND blocked_uuid = $2) OR (blocker_uuid = $2 AND blocked_uuid = $1)`,
      [firstUuid, secondUuid],
    )).rowCount) {
      throw new ApiError(403, "BLOCKED", "This action is unavailable.");
    }
  }

  private async assertSocialAvailable(client: Pool | PoolClient, playerUuid: string): Promise<void> {
    if ((await client.query(
      `SELECT 1 FROM social_access_restrictions
       WHERE player_uuid = $1 AND restored_at IS NULL
       LIMIT 1`,
      [playerUuid],
    )).rowCount) {
      throw new ApiError(403, "PLAYER_SOCIAL_UNAVAILABLE", "This player is not available for Friends & Chat.");
    }
  }
}

function isUniqueViolation(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && error.code === "23505";
}
