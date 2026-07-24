import type { Pool } from "pg";
import type WebSocket from "ws";
import type { PresenceStatus } from "./domain.js";
import type { Actor } from "./types.js";

interface LiveSocket {
  socket: WebSocket;
  alive: boolean;
}

export class RealtimeHub {
  private readonly connections = new Map<string, Set<LiveSocket>>();
  private readonly statuses = new Map<string, PresenceStatus>();
  private heartbeat: NodeJS.Timeout | null = null;

  constructor(private readonly pool: Pool) {}

  start(): void {
    if (this.heartbeat) return;
    this.heartbeat = setInterval(() => {
      for (const sockets of this.connections.values()) {
        for (const connection of sockets) {
          if (!connection.alive) {
            connection.socket.terminate();
            continue;
          }
          connection.alive = false;
          connection.socket.ping();
        }
      }
    }, 30_000);
    this.heartbeat.unref();
  }

  stop(): void {
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.heartbeat = null;
    for (const sockets of this.connections.values()) {
      for (const connection of sockets) connection.socket.close(1001, "Server shutting down");
    }
    this.connections.clear();
    this.statuses.clear();
  }

  async add(actor: Actor, socket: WebSocket): Promise<void> {
    const connection: LiveSocket = { socket, alive: true };
    const existing = this.connections.get(actor.playerUuid);
    const firstConnection = !existing || existing.size === 0;
    const sockets = existing ?? new Set<LiveSocket>();
    sockets.add(connection);
    this.connections.set(actor.playerUuid, sockets);
    if (firstConnection) this.statuses.set(actor.playerUuid, "ONLINE");

    socket.on("pong", () => {
      connection.alive = true;
    });
    socket.on("close", () => void this.remove(actor.playerUuid, connection));
    socket.on("error", () => undefined);

    if (firstConnection) {
      await this.notifyContacts(actor.playerUuid, "presence", {
        playerUuid: actor.playerUuid,
        online: true,
        status: "ONLINE",
        lastSeenAt: null,
      });
    }
  }

  isOnline(playerUuid: string): boolean {
    return this.status(playerUuid) !== "OFFLINE";
  }

  status(playerUuid: string): PresenceStatus {
    if ((this.connections.get(playerUuid)?.size ?? 0) === 0) return "OFFLINE";
    return this.statuses.get(playerUuid) ?? "ONLINE";
  }

  async setStatus(actor: Actor, status: PresenceStatus): Promise<void> {
    if ((this.connections.get(actor.playerUuid)?.size ?? 0) === 0) return;
    this.statuses.set(actor.playerUuid, status);
    const payload = {
      playerUuid: actor.playerUuid,
      online: status !== "OFFLINE",
      status,
      lastSeenAt: status === "OFFLINE" ? new Date().toISOString() : null,
    };
    this.send(actor.playerUuid, "presence.self", payload);
    await this.notifyContacts(actor.playerUuid, "presence", payload);
  }

  send(playerUuid: string, type: string, payload: unknown): void {
    const data = JSON.stringify({ type, data: payload });
    for (const connection of this.connections.get(playerUuid) ?? []) {
      if (connection.socket.readyState === connection.socket.OPEN) {
        connection.socket.send(data);
      }
    }
  }

  async notifyFriends(playerUuid: string, type: string, payload: unknown): Promise<void> {
    const result = await this.pool.query<{ friend_uuid: string }>(
      `SELECT CASE WHEN player_low = $1 THEN player_high ELSE player_low END AS friend_uuid
       FROM friendships WHERE player_low = $1 OR player_high = $1`,
      [playerUuid],
    );
    for (const row of result.rows) this.send(row.friend_uuid, type, payload);
  }

  async notifyContacts(playerUuid: string, type: string, payload: unknown): Promise<void> {
    const result = await this.pool.query<{ contact_uuid: string }>(
      `SELECT CASE WHEN player_low = $1 THEN player_high ELSE player_low END AS contact_uuid
       FROM friendships WHERE player_low = $1 OR player_high = $1
       UNION
       SELECT other.player_uuid AS contact_uuid
       FROM group_members mine
       JOIN group_members other ON other.group_id = mine.group_id AND other.player_uuid <> $1
       WHERE mine.player_uuid = $1`,
      [playerUuid],
    );
    for (const row of result.rows) this.send(row.contact_uuid, type, payload);
  }

  async groupMembers(groupId: string): Promise<string[]> {
    const result = await this.pool.query<{ player_uuid: string }>(
      "SELECT player_uuid FROM group_members WHERE group_id = $1",
      [groupId],
    );
    return result.rows.map((row) => row.player_uuid);
  }

  async sendToGroup(groupId: string, type: string, payload: unknown, excludePlayerUuid?: string): Promise<void> {
    for (const playerUuid of await this.groupMembers(groupId)) {
      if (playerUuid !== excludePlayerUuid) this.send(playerUuid, type, payload);
    }
  }

  private async remove(playerUuid: string, connection: LiveSocket): Promise<void> {
    const sockets = this.connections.get(playerUuid);
    if (!sockets) return;
    sockets.delete(connection);
    if (sockets.size > 0) return;
    this.connections.delete(playerUuid);
    this.statuses.delete(playerUuid);

    const result = await this.pool.query<{ last_seen_at: Date }>(
      "UPDATE users SET last_seen_at = now() WHERE player_uuid = $1 RETURNING last_seen_at",
      [playerUuid],
    );
    const lastSeenAt = result.rows[0]?.last_seen_at?.toISOString() ?? new Date().toISOString();
    await this.notifyContacts(playerUuid, "presence", { playerUuid, online: false, status: "OFFLINE", lastSeenAt });
  }
}
