import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import { inflateRawSync } from "node:zlib";
import type { Pool, PoolClient } from "pg";
import { ApiError, parseUuid } from "./domain.js";
import type { RealtimeHub } from "./realtime.js";
import type { Actor } from "./types.js";

export const GSAVE_MEDIA_TYPE = "application/vnd.glib.gsave";
export const GSAVE_MAX_BYTES = 8 * 1024 * 1024;
export const GSAVE_OWNER_MAX_ROOMS = 100;
export const GSAVE_OWNER_MAX_BYTES = 256 * 1024 * 1024;
export const GSAVE_MAX_INFLATED_BYTES = 64 * 1024 * 1024;
const DEFAULT_PAGE_SIZE = 50;
const MAX_PAGE_SIZE = 100;
const ROOM_NAME_CONTROL_PATTERN = /[\u0000-\u001f\u007f]/;
const ARCHIVE_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const DOS_DEVICE_ARCHIVE_ID_PATTERN = /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/;
const GSAVE_HEADER_BYTES = 4 + 4 + 1 + 4 + 32 + 4;
const GSAVE_MAGIC = Buffer.from("GSAV", "ascii");
const GSAVE_FORMAT_VERSION = 1;
const GSAVE_RAW_DEFLATE = 1;
const GSAVE_MAX_STRING_BYTES = 65_536;

interface RoomRow {
  room_id: string;
  archive_id: string;
  owner_uuid: string;
  owner_username: string;
  name: string;
  payload_sha256: string;
  size_bytes: number;
  revision: string;
  created_at: Date;
  updated_at: Date;
}

interface RoomPayloadRow extends RoomRow {
  payload: Buffer | Uint8Array;
}

export interface RoomMetadata {
  roomId: string;
  archiveId: string;
  name: string;
  owner: {
    playerUuid: string;
    username: string;
  };
  revision: string;
  etag: string;
  sha256: string;
  sizeBytes: number;
  createdAt: string;
  updatedAt: string;
}

export interface RoomDownload {
  metadata: RoomMetadata;
  payload: Buffer;
}

function roomMetadata(row: RoomRow): RoomMetadata {
  const revision = String(row.revision);
  return {
    roomId: row.room_id,
    archiveId: row.archive_id,
    name: row.name,
    owner: {
      playerUuid: row.owner_uuid,
      username: row.owner_username,
    },
    revision,
    etag: roomEtag(revision),
    sha256: row.payload_sha256,
    sizeBytes: Number(row.size_bytes),
    createdAt: row.created_at.toISOString(),
    updatedAt: row.updated_at.toISOString(),
  };
}

function validateArchiveId(value: unknown): string {
  if (
    typeof value !== "string"
    || !ARCHIVE_ID_PATTERN.test(value)
    || DOS_DEVICE_ARCHIVE_ID_PATTERN.test(value)
  ) {
    throw new ApiError(
      400,
      "INVALID_ARCHIVE_ID",
      "X-Glib-Room-Id must be a filesystem-safe lowercase room slug of 1-64 characters.",
    );
  }
  return value;
}

function validateName(value: unknown): string {
  if (typeof value !== "string") {
    throw new ApiError(400, "INVALID_ROOM_NAME", "A room name is required in the X-Glib-Room-Name header.");
  }
  const name = value.trim();
  if (name.length === 0 || name.length > 80 || ROOM_NAME_CONTROL_PATTERN.test(name)) {
    throw new ApiError(400, "INVALID_ROOM_NAME", "Room names must contain 1-80 visible characters.");
  }
  return name;
}

function validatePayload(value: unknown): Buffer {
  if (!Buffer.isBuffer(value) && !(value instanceof Uint8Array)) {
    throw new ApiError(400, "INVALID_GSAVE", "The request body must be a binary .gsave file.");
  }
  const payload = Buffer.from(value);
  if (payload.length === 0) {
    throw new ApiError(400, "EMPTY_GSAVE", "The .gsave file cannot be empty.");
  }
  if (payload.length > GSAVE_MAX_BYTES) {
    throw new ApiError(413, "GSAVE_TOO_LARGE", `A .gsave file cannot exceed ${GSAVE_MAX_BYTES} bytes.`);
  }
  return payload;
}

function validateGsaveArchive(payload: Buffer, archiveId: string): void {
  if (payload.length < GSAVE_HEADER_BYTES) {
    throw invalidGsave("The .gsave envelope is truncated.");
  }
  if (!payload.subarray(0, GSAVE_MAGIC.length).equals(GSAVE_MAGIC)) {
    throw invalidGsave("The .gsave magic is invalid.");
  }
  if (payload.readInt32BE(4) !== GSAVE_FORMAT_VERSION) {
    throw invalidGsave(`Only .gsave format version ${GSAVE_FORMAT_VERSION} is supported.`);
  }
  if (payload.readUInt8(8) !== GSAVE_RAW_DEFLATE) {
    throw invalidGsave("The .gsave compression method is unsupported.");
  }
  const inflatedLength = payload.readInt32BE(9);
  if (inflatedLength < 4 || inflatedLength > GSAVE_MAX_INFLATED_BYTES) {
    throw invalidGsave("The declared .gsave payload size is invalid.");
  }
  const expectedHash = payload.subarray(13, 45);
  const compressedLength = payload.readInt32BE(45);
  if (
    compressedLength < 0
    || GSAVE_HEADER_BYTES + compressedLength !== payload.length
  ) {
    throw invalidGsave("The compressed .gsave payload length is invalid.");
  }

  let inflated: Buffer;
  try {
    const result = inflateRawSync(
      payload.subarray(GSAVE_HEADER_BYTES),
      { maxOutputLength: inflatedLength, info: true },
    ) as unknown as { buffer: Buffer; engine: { bytesWritten: number } };
    if (result.engine.bytesWritten !== compressedLength) {
      throw invalidGsave("The compressed .gsave payload contains trailing data.");
    }
    inflated = Buffer.from(result.buffer);
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw invalidGsave("The compressed .gsave payload is invalid.");
  }
  if (inflated.length !== inflatedLength) {
    throw invalidGsave("The inflated .gsave payload length does not match its declaration.");
  }
  const actualHash = createHash("sha256").update(inflated).digest();
  if (!timingSafeEqual(actualHash, expectedHash)) {
    throw invalidGsave("The .gsave payload checksum does not match.");
  }

  const idLength = inflated.readInt32BE(0);
  if (
    idLength < 0
    || idLength > GSAVE_MAX_STRING_BYTES
    || 4 + idLength > inflated.length
  ) {
    throw invalidGsave("The embedded .gsave room ID is truncated or too long.");
  }
  let embeddedArchiveId: string;
  try {
    embeddedArchiveId = new TextDecoder("utf-8", { fatal: true })
      .decode(inflated.subarray(4, 4 + idLength));
  } catch {
    throw invalidGsave("The embedded .gsave room ID is not valid UTF-8.");
  }
  if (embeddedArchiveId !== archiveId) {
    throw new ApiError(
      409,
      "ARCHIVE_ID_MISMATCH",
      "The embedded .gsave room ID does not match X-Glib-Room-Id.",
    );
  }
}

function invalidGsave(message: string): ApiError {
  return new ApiError(400, "INVALID_GSAVE", message);
}

function parsePageSize(value: unknown): number {
  if (value === undefined) return DEFAULT_PAGE_SIZE;
  if (typeof value !== "string" || !/^[1-9][0-9]{0,2}$/.test(value)) {
    throw new ApiError(400, "INVALID_LIMIT", `limit must be an integer from 1 through ${MAX_PAGE_SIZE}.`);
  }
  const limit = Number.parseInt(value, 10);
  if (limit > MAX_PAGE_SIZE) {
    throw new ApiError(400, "INVALID_LIMIT", `limit must be an integer from 1 through ${MAX_PAGE_SIZE}.`);
  }
  return limit;
}

interface PageCursor {
  updatedAt: string;
  roomId: string;
}

function encodeCursor(row: RoomRow): string {
  return Buffer.from(JSON.stringify({
    updatedAt: row.updated_at.toISOString(),
    roomId: row.room_id,
  } satisfies PageCursor), "utf8").toString("base64url");
}

function parseCursor(value: unknown): PageCursor | null {
  if (value === undefined) return null;
  if (typeof value !== "string" || value.length > 256) {
    throw new ApiError(400, "INVALID_CURSOR", "The room-library cursor is invalid.");
  }
  try {
    const decoded = JSON.parse(Buffer.from(value, "base64url").toString("utf8")) as Partial<PageCursor>;
    const updatedAt = new Date(decoded.updatedAt ?? "");
    return {
      updatedAt: updatedAt.toISOString(),
      roomId: parseUuid(decoded.roomId, "cursor roomId"),
    };
  } catch {
    throw new ApiError(400, "INVALID_CURSOR", "The room-library cursor is invalid.");
  }
}

export function roomEtag(revision: string | number): string {
  return `"${revision}"`;
}

export function parseRoomEtag(value: unknown): string {
  if (typeof value !== "string") {
    throw new ApiError(428, "ROOM_REVISION_REQUIRED", "Send the room's current ETag in the If-Match header.");
  }
  const match = /^"([1-9][0-9]{0,18})"$/.exec(value.trim());
  if (!match?.[1]) {
    throw new ApiError(400, "INVALID_ROOM_ETAG", "If-Match must contain one current room ETag.");
  }
  return match[1];
}

export class RoomRepository {
  constructor(
    private readonly pool: Pool,
    private readonly realtime: RealtimeHub,
  ) {}

  async list(
    rawLimit: unknown,
    rawCursor: unknown,
    ownerUuid: string | null = null,
  ): Promise<{ rooms: RoomMetadata[]; nextCursor: string | null }> {
    const limit = parsePageSize(rawLimit);
    const cursor = parseCursor(rawCursor);
    const result = await this.pool.query<RoomRow>(
      `SELECT r.room_id, r.archive_id, r.owner_uuid, u.username AS owner_username, r.name,
              r.payload_sha256, r.size_bytes, r.revision, r.created_at, r.updated_at
       FROM gsave_rooms r
       JOIN users u ON u.player_uuid = r.owner_uuid
       WHERE ($1::uuid IS NULL OR r.owner_uuid = $1)
         AND ($2::timestamptz IS NULL OR (r.updated_at, r.room_id) < ($2::timestamptz, $3::uuid))
       ORDER BY r.updated_at DESC, r.room_id DESC
       LIMIT $4`,
      [ownerUuid, cursor?.updatedAt ?? null, cursor?.roomId ?? null, limit + 1],
    );
    const pageRows = result.rows.slice(0, limit);
    return {
      rooms: pageRows.map(roomMetadata),
      nextCursor: result.rows.length > limit && pageRows.length > 0
        ? encodeCursor(pageRows[pageRows.length - 1]!)
        : null,
    };
  }

  async create(
    actor: Actor,
    rawArchiveId: unknown,
    rawName: unknown,
    rawPayload: unknown,
  ): Promise<RoomMetadata> {
    const archiveId = validateArchiveId(rawArchiveId);
    const name = validateName(rawName);
    const payload = validatePayload(rawPayload);
    validateGsaveArchive(payload, archiveId);
    const roomId = randomUUID();
    const sha256 = createHash("sha256").update(payload).digest("hex");
    const client = await this.pool.connect();
    let metadata: RoomMetadata;
    try {
      await client.query("BEGIN");
      await lockOwner(client, actor.playerUuid);
      await requireCreateQuota(client, actor.playerUuid, payload.length);
      const result = await client.query<RoomRow>(
        `WITH inserted AS (
           INSERT INTO gsave_rooms(
             room_id, archive_id, owner_uuid, name, payload, payload_sha256, size_bytes
           )
           VALUES ($1, $2, $3, $4, $5, $6, $7)
           RETURNING *
         )
         SELECT r.room_id, r.archive_id, r.owner_uuid, u.username AS owner_username, r.name,
                r.payload_sha256, r.size_bytes, r.revision, r.created_at, r.updated_at
         FROM inserted r JOIN users u ON u.player_uuid = r.owner_uuid`,
        [roomId, archiveId, actor.playerUuid, name, payload, sha256, payload.length],
      );
      metadata = roomMetadata(result.rows[0]!);
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK");
      if (isUniqueViolation(error)) {
        throw new ApiError(
          409,
          "ARCHIVE_ID_EXISTS",
          `A repository room already uses archive ID '${archiveId}'.`,
        );
      }
      throw error;
    } finally {
      client.release();
    }
    this.realtime.broadcast("room.created", metadata);
    return metadata;
  }

  async download(rawRoomId: unknown): Promise<RoomDownload> {
    const roomId = parseUuid(rawRoomId, "roomId");
    const result = await this.pool.query<RoomPayloadRow>(
      `SELECT r.room_id, r.archive_id, r.owner_uuid, u.username AS owner_username, r.name, r.payload,
              r.payload_sha256, r.size_bytes, r.revision, r.created_at, r.updated_at
       FROM gsave_rooms r
       JOIN users u ON u.player_uuid = r.owner_uuid
       WHERE r.room_id = $1`,
      [roomId],
    );
    const row = result.rows[0];
    if (!row) throw new ApiError(404, "ROOM_NOT_FOUND", "That saved room does not exist.");
    return { metadata: roomMetadata(row), payload: Buffer.from(row.payload) };
  }

  async update(
    actor: Actor,
    rawRoomId: unknown,
    rawArchiveId: unknown,
    rawEtag: unknown,
    rawName: unknown,
    rawPayload: unknown,
  ): Promise<RoomMetadata> {
    const roomId = parseUuid(rawRoomId, "roomId");
    const archiveId = validateArchiveId(rawArchiveId);
    const expectedRevision = parseRoomEtag(rawEtag);
    const name = rawName === undefined ? null : validateName(rawName);
    const payload = validatePayload(rawPayload);
    validateGsaveArchive(payload, archiveId);
    const sha256 = createHash("sha256").update(payload).digest("hex");
    const client = await this.pool.connect();
    let metadata: RoomMetadata;
    try {
      await client.query("BEGIN");
      await lockOwner(client, actor.playerUuid);
      const current = await this.requireOwnedRoom(
        client, actor, roomId, expectedRevision, archiveId);
      await requireUpdateQuota(client, actor.playerUuid, Number(current.size_bytes), payload.length);
      const result = await client.query<RoomRow>(
        `WITH updated AS (
           UPDATE gsave_rooms
           SET name = COALESCE($4, name), payload = $5, payload_sha256 = $6,
               size_bytes = $7, revision = revision + 1, updated_at = now()
           WHERE room_id = $1 AND owner_uuid = $2 AND revision = $3
           RETURNING *
         )
         SELECT r.room_id, r.archive_id, r.owner_uuid, u.username AS owner_username, r.name,
                r.payload_sha256, r.size_bytes, r.revision, r.created_at, r.updated_at
         FROM updated r JOIN users u ON u.player_uuid = r.owner_uuid`,
        [roomId, actor.playerUuid, expectedRevision, name, payload, sha256, payload.length],
      );
      metadata = roomMetadata(result.rows[0]!);
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
    this.realtime.broadcast("room.updated", metadata);
    return metadata;
  }

  async delete(actor: Actor, rawRoomId: unknown): Promise<RoomMetadata> {
    const roomId = parseUuid(rawRoomId, "roomId");
    const result = await this.pool.query<RoomRow>(
      `WITH deleted AS (
         DELETE FROM gsave_rooms
         WHERE room_id = $1 AND owner_uuid = $2
         RETURNING *
       )
       SELECT r.room_id, r.archive_id, r.owner_uuid, u.username AS owner_username, r.name,
              r.payload_sha256, r.size_bytes, r.revision, r.created_at, r.updated_at
       FROM deleted r JOIN users u ON u.player_uuid = r.owner_uuid`,
      [roomId, actor.playerUuid],
    );
    const row = result.rows[0];
    if (!row) await this.explainMissingOrForbidden(actor, roomId);
    const metadata = roomMetadata(row!);
    this.realtime.broadcast("room.deleted", metadata);
    return metadata;
  }

  private async requireOwnedRoom(
    client: PoolClient,
    actor: Actor,
    roomId: string,
    expectedRevision: string,
    archiveId: string,
  ): Promise<{ owner_uuid: string; revision: string; archive_id: string; size_bytes: number }> {
    const result = await client.query<{
      owner_uuid: string;
      revision: string;
      archive_id: string;
      size_bytes: number;
    }>(
      `SELECT owner_uuid, revision, archive_id, size_bytes
       FROM gsave_rooms WHERE room_id = $1 FOR UPDATE`,
      [roomId],
    );
    const current = result.rows[0];
    if (!current) throw new ApiError(404, "ROOM_NOT_FOUND", "That saved room does not exist.");
    if (current.owner_uuid !== actor.playerUuid) {
      throw new ApiError(403, "ROOM_UPDATE_FORBIDDEN", "Only the room's owner can replace its .gsave file.");
    }
    if (current.archive_id !== archiveId) {
      throw new ApiError(
        409,
        "ARCHIVE_ID_MISMATCH",
        `This repository room contains archive ID '${current.archive_id}', not '${archiveId}'.`,
      );
    }
    if (String(current.revision) === expectedRevision) return current;
    throw new ApiError(
      412,
      "ROOM_REVISION_CONFLICT",
      `The room changed after it was downloaded. Fetch revision ${current.revision} and retry.`,
    );
  }

  private async explainMissingOrForbidden(actor: Actor, roomId: string): Promise<never> {
    const result = await this.pool.query<{ owner_uuid: string }>(
      "SELECT owner_uuid FROM gsave_rooms WHERE room_id = $1",
      [roomId],
    );
    if (!result.rows[0]) throw new ApiError(404, "ROOM_NOT_FOUND", "That saved room does not exist.");
    throw new ApiError(403, "ROOM_DELETE_FORBIDDEN", "Only the room's owner can delete it.");
  }
}

async function lockOwner(client: PoolClient, playerUuid: string): Promise<void> {
  await client.query("SELECT player_uuid FROM users WHERE player_uuid = $1 FOR UPDATE", [playerUuid]);
}

async function ownerUsage(
  client: PoolClient,
  playerUuid: string,
): Promise<{ roomCount: number; totalBytes: number }> {
  const result = await client.query<{ room_count: string; total_bytes: string }>(
    `SELECT count(*) AS room_count, COALESCE(sum(size_bytes), 0) AS total_bytes
     FROM gsave_rooms WHERE owner_uuid = $1`,
    [playerUuid],
  );
  return {
    roomCount: Number(result.rows[0]?.room_count ?? 0),
    totalBytes: Number(result.rows[0]?.total_bytes ?? 0),
  };
}

async function requireCreateQuota(
  client: PoolClient,
  playerUuid: string,
  payloadBytes: number,
): Promise<void> {
  const usage = await ownerUsage(client, playerUuid);
  if (usage.roomCount >= GSAVE_OWNER_MAX_ROOMS) {
    throw new ApiError(
      409,
      "ROOM_COUNT_QUOTA_EXCEEDED",
      `Each owner may store at most ${GSAVE_OWNER_MAX_ROOMS} rooms.`,
    );
  }
  requireByteQuota(usage.totalBytes + payloadBytes);
}

async function requireUpdateQuota(
  client: PoolClient,
  playerUuid: string,
  previousBytes: number,
  payloadBytes: number,
): Promise<void> {
  const usage = await ownerUsage(client, playerUuid);
  requireByteQuota(usage.totalBytes - previousBytes + payloadBytes);
}

function requireByteQuota(totalBytes: number): void {
  if (totalBytes > GSAVE_OWNER_MAX_BYTES) {
    throw new ApiError(
      413,
      "ROOM_BYTES_QUOTA_EXCEEDED",
      `Each owner may store at most ${GSAVE_OWNER_MAX_BYTES} room bytes.`,
    );
  }
}

function isUniqueViolation(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && error.code === "23505";
}
