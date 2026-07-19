import type { Pool } from "pg";
import type WebSocket from "ws";
import type { Actor } from "./types.js";

interface LiveSocket {
  socket: WebSocket;
  alive: boolean;
}

export class RealtimeHub {
  private readonly connections = new Map<string, Set<LiveSocket>>();
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
  }

  async add(actor: Actor, socket: WebSocket): Promise<void> {
    const connection: LiveSocket = { socket, alive: true };
    const existing = this.connections.get(actor.playerUuid);
    const firstConnection = !existing || existing.size === 0;
    const sockets = existing ?? new Set<LiveSocket>();
    sockets.add(connection);
    this.connections.set(actor.playerUuid, sockets);

    socket.on("pong", () => {
      connection.alive = true;
    });
    socket.on("close", () => void this.remove(actor.playerUuid, connection));
    socket.on("error", () => undefined);

    if (firstConnection) {
      await this.notifyFriends(actor.playerUuid, "presence", {
        playerUuid: actor.playerUuid,
        online: true,
        lastSeenAt: null,
      });
    }
  }

  isOnline(playerUuid: string): boolean {
    return (this.connections.get(playerUuid)?.size ?? 0) > 0;
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

  private async remove(playerUuid: string, connection: LiveSocket): Promise<void> {
    const sockets = this.connections.get(playerUuid);
    if (!sockets) return;
    sockets.delete(connection);
    if (sockets.size > 0) return;
    this.connections.delete(playerUuid);

    const result = await this.pool.query<{ last_seen_at: Date }>(
      "UPDATE users SET last_seen_at = now() WHERE player_uuid = $1 RETURNING last_seen_at",
      [playerUuid],
    );
    const lastSeenAt = result.rows[0]?.last_seen_at?.toISOString() ?? new Date().toISOString();
    await this.notifyFriends(playerUuid, "presence", { playerUuid, online: false, lastSeenAt });
  }
}
