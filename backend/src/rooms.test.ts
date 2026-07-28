import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { deflateRawSync } from "node:zlib";
import { PGlite } from "@electric-sql/pglite";
import type { Pool } from "pg";
import type WebSocket from "ws";
import { createApp } from "./app.js";
import type { Config } from "./config.js";
import { hashToken } from "./domain.js";
import {
  GSAVE_MAX_BYTES,
  GSAVE_MEDIA_TYPE,
  GSAVE_OWNER_MAX_BYTES,
  GSAVE_OWNER_MAX_ROOMS,
} from "./rooms.js";

function int32(value: number): Buffer {
  const result = Buffer.alloc(4);
  result.writeInt32BE(value);
  return result;
}

function gsaveString(value: string): Buffer {
  const encoded = Buffer.from(value, "utf8");
  return Buffer.concat([int32(encoded.length), encoded]);
}

function minimalGsave(archiveId: string, name = archiveId): Buffer {
  const roomPayload = Buffer.concat([
    gsaveString(archiveId),
    gsaveString(name),
    int32(1),
    int32(1),
    int32(1),
    int32(0),
    int32(0),
    int32(0),
  ]);
  const compressed = deflateRawSync(roomPayload, { level: 9 });
  return Buffer.concat([
    Buffer.from("GSAV", "ascii"),
    int32(1),
    Buffer.from([1]),
    int32(roomPayload.length),
    createHash("sha256").update(roomPayload).digest(),
    int32(compressed.length),
    compressed,
  ]);
}

test("authenticated room routes preserve one-file payloads and enforce owner revisions", async () => {
  const database = new PGlite();
  let app: Awaited<ReturnType<typeof createApp>> | undefined;
  let socket: WebSocket | undefined;
  const socketMessages: string[] = [];
  const socketWaiters: Array<(message: string) => void> = [];
  const captureSocketMessage = (message: unknown): void => {
    const waiter = socketWaiters.shift();
    if (waiter) waiter(String(message));
    else socketMessages.push(String(message));
  };
  const nextSocketMessage = (): Promise<string> => {
    const queued = socketMessages.shift();
    return queued === undefined
      ? new Promise((resolveMessage) => socketWaiters.push(resolveMessage))
      : Promise.resolve(queued);
  };
  try {
    const migrationsDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "../migrations");
    for (const file of [
      "001_initial.sql",
      "002_groups.sql",
      "003_moderation.sql",
      "004_investigations.sql",
      "005_social_access_restrictions.sql",
      "006_gsave_rooms.sql",
    ]) {
      await database.exec(await readFile(resolve(migrationsDirectory, file), "utf8"));
    }
    const owner = "00000000-0000-4000-8000-000000000001";
    const other = "00000000-0000-4000-8000-000000000002";
    const ownerToken = "o".repeat(43);
    const otherToken = "x".repeat(43);
    await database.query(
      `INSERT INTO users(player_uuid, username, normalized_username)
       VALUES ($1, 'Owner', 'owner'), ($2, 'Other', 'other')`,
      [owner, other],
    );
    await database.query(
      `INSERT INTO sessions(token_hash, player_uuid, expires_at)
       VALUES ($1, $2, now() + interval '1 hour'), ($3, $4, now() + interval '1 hour')`,
      [hashToken(ownerToken), owner, hashToken(otherToken), other],
    );
    const query = async (sql: string, params?: unknown[]) => {
      const result = await database.query(sql, params);
      const affectedRows = "affectedRows" in result && typeof result.affectedRows === "number" ? result.affectedRows : 0;
      return { ...result, rowCount: result.rows.length > 0 ? result.rows.length : affectedRows };
    };
    const client = { query, release: () => undefined };
    const pool = { query, connect: async () => client } as unknown as Pool;
    const config: Config = {
      databaseUrl: "postgresql://unused",
      databaseSsl: false,
      host: "127.0.0.1",
      port: 8080,
      logLevel: "silent",
      sessionTtlHours: 12,
      adminSessionTtlMinutes: 30,
      messageRetentionDays: 90,
      trustProxy: false,
      roomSyncToken: null,
      roomSyncOwnerUuid: null,
    };
    app = await createApp(pool, config);

    const unauthenticated = await app.inject({
      method: "GET",
      url: "/v1/rooms",
    });
    assert.equal(unauthenticated.statusCode, 401);

    const disabledServiceAuth = await app.inject({
      method: "GET",
      url: "/v1/rooms",
      headers: { "x-glib-room-sync-token": "d".repeat(43) },
    });
    assert.equal(disabledServiceAuth.statusCode, 401);

    await app.close();
    app = undefined;
    const roomSyncToken = "s".repeat(43);
    await assert.rejects(
      createApp(pool, {
        ...config,
        roomSyncToken,
        roomSyncOwnerUuid: "00000000-0000-4000-8000-000000000099",
      }),
      /must reference an existing G'Network user/,
    );
    app = await createApp(pool, {
      ...config,
      roomSyncToken,
      roomSyncOwnerUuid: owner,
    });

    const invalidServiceAuth = await app.inject({
      method: "GET",
      url: "/v1/rooms",
      headers: { "x-glib-room-sync-token": "i".repeat(43) },
    });
    assert.equal(invalidServiceAuth.statusCode, 401);
    assert.equal(invalidServiceAuth.body.includes("i".repeat(43)), false);

    const playerBearerWinsOverInvalidServiceToken = await app.inject({
      method: "GET",
      url: "/v1/rooms",
      headers: {
        authorization: `Bearer ${otherToken}`,
        "x-glib-room-sync-token": "i".repeat(43),
      },
    });
    assert.equal(playerBearerWinsOverInvalidServiceToken.statusCode, 200);

    const validServiceAuth = await app.inject({
      method: "GET",
      url: "/v1/rooms",
      headers: { "x-glib-room-sync-token": roomSyncToken },
    });
    assert.equal(validServiceAuth.statusCode, 200);
    assert.deepEqual(validServiceAuth.json().rooms, []);

    const serviceCannotAccessSocialRoutes = await app.inject({
      method: "GET",
      url: "/v1/social",
      headers: { "x-glib-room-sync-token": roomSyncToken },
    });
    assert.equal(serviceCannotAccessSocialRoutes.statusCode, 401);

    const originalPayload = minimalGsave("portal-hall", "Portal Hall");
    const missingArchiveId = await app.inject({
      method: "POST",
      url: "/v1/rooms",
      headers: {
        authorization: `Bearer ${ownerToken}`,
        "content-type": GSAVE_MEDIA_TYPE,
        "x-glib-room-name": "Missing ID",
      },
      payload: originalPayload,
    });
    assert.equal(missingArchiveId.statusCode, 400);
    assert.equal(missingArchiveId.json().error.code, "INVALID_ARCHIVE_ID");

    const invalidArchiveId = await app.inject({
      method: "POST",
      url: "/v1/rooms",
      headers: {
        authorization: `Bearer ${ownerToken}`,
        "content-type": GSAVE_MEDIA_TYPE,
        "x-glib-room-id": "../NOT-SAFE",
        "x-glib-room-name": "Invalid ID",
      },
      payload: originalPayload,
    });
    assert.equal(invalidArchiveId.statusCode, 400);
    assert.equal(invalidArchiveId.json().error.code, "INVALID_ARCHIVE_ID");

    const dosDeviceArchiveId = await app.inject({
      method: "POST",
      url: "/v1/rooms",
      headers: {
        authorization: `Bearer ${ownerToken}`,
        "content-type": GSAVE_MEDIA_TYPE,
        "x-glib-room-id": "con.room",
        "x-glib-room-name": "Unsafe on Windows",
      },
      payload: minimalGsave("con.room"),
    });
    assert.equal(dosDeviceArchiveId.statusCode, 400);
    assert.equal(dosDeviceArchiveId.json().error.code, "INVALID_ARCHIVE_ID");

    const corruptedPayload = minimalGsave("corrupted-room");
    corruptedPayload[13] = corruptedPayload[13]! ^ 0xff;
    const corruptedArchive = await app.inject({
      method: "POST",
      url: "/v1/rooms",
      headers: {
        authorization: `Bearer ${ownerToken}`,
        "content-type": GSAVE_MEDIA_TYPE,
        "x-glib-room-id": "corrupted-room",
        "x-glib-room-name": "Corrupted",
      },
      payload: corruptedPayload,
    });
    assert.equal(corruptedArchive.statusCode, 400);
    assert.equal(corruptedArchive.json().error.code, "INVALID_GSAVE");

    const created = await app.inject({
      method: "POST",
      url: "/v1/rooms",
      headers: {
        "x-glib-room-sync-token": roomSyncToken,
        "content-type": GSAVE_MEDIA_TYPE,
        "x-glib-room-id": "portal-hall",
        "x-glib-room-name": "Portal%20Caf%C3%A9%20%F0%9F%9A%82",
      },
      payload: originalPayload,
    });
    assert.equal(created.statusCode, 201);
    assert.equal(created.headers.etag, "\"1\"");
    const roomId = created.json().roomId as string;
    assert.equal(created.json().archiveId, "portal-hall");
    assert.equal(created.json().name, "Portal Café 🚂");
    assert.equal(created.json().owner.playerUuid, owner);
    assert.equal(created.json().sizeBytes, originalPayload.length);

    const listed = await app.inject({
      method: "GET",
      url: "/v1/rooms",
      headers: { authorization: `Bearer ${otherToken}` },
    });
    assert.equal(listed.statusCode, 200);
    assert.equal(listed.json().rooms[0].archiveId, "portal-hall");
    assert.equal(listed.json().rooms[0].name, "Portal Café 🚂");
    assert.equal("payload" in listed.json().rooms[0], false);

    const otherCreated = await app.inject({
      method: "POST",
      url: "/v1/rooms",
      headers: {
        authorization: `Bearer ${otherToken}`,
        "content-type": GSAVE_MEDIA_TYPE,
        "x-glib-room-id": "other-room",
        "x-glib-room-name": "Other room",
      },
      payload: minimalGsave("other-room"),
    });
    assert.equal(otherCreated.statusCode, 201);

    const serviceOwnerLibrary = await app.inject({
      method: "GET",
      url: "/v1/rooms",
      headers: { "x-glib-room-sync-token": roomSyncToken },
    });
    assert.deepEqual(
      serviceOwnerLibrary.json().rooms.map((room: { archiveId: string }) => room.archiveId),
      ["portal-hall"],
    );

    const downloaded = await app.inject({
      method: "GET",
      url: `/v1/rooms/${roomId}`,
      headers: { authorization: `Bearer ${otherToken}` },
    });
    assert.equal(downloaded.statusCode, 200);
    assert.equal(downloaded.headers.etag, "\"1\"");
    assert.equal(downloaded.headers["content-disposition"], "attachment; filename=\"portal-hall.gsave\"");
    assert.equal(downloaded.headers["x-glib-room-name"], "Portal%20Caf%C3%A9%20%F0%9F%9A%82");
    assert.deepEqual(downloaded.rawPayload, originalPayload);

    const duplicateArchiveId = await app.inject({
      method: "POST",
      url: "/v1/rooms",
      headers: {
        authorization: `Bearer ${otherToken}`,
        "content-type": GSAVE_MEDIA_TYPE,
        "x-glib-room-id": "portal-hall",
        "x-glib-room-name": "Duplicate",
      },
      payload: originalPayload,
    });
    assert.equal(duplicateArchiveId.statusCode, 409);
    assert.equal(duplicateArchiveId.json().error.code, "ARCHIVE_ID_EXISTS");

    const notModified = await app.inject({
      method: "GET",
      url: `/v1/rooms/${roomId}`,
      headers: { authorization: `Bearer ${otherToken}`, "if-none-match": "\"1\"" },
    });
    assert.equal(notModified.statusCode, 304);

    const forbidden = await app.inject({
      method: "PUT",
      url: `/v1/rooms/${roomId}`,
      headers: {
        authorization: `Bearer ${otherToken}`,
        "content-type": GSAVE_MEDIA_TYPE,
        "x-glib-room-id": "portal-hall",
        "if-match": "\"1\"",
      },
        payload: minimalGsave("portal-hall", "Forbidden update"),
    });
    assert.equal(forbidden.statusCode, 403);

    const mismatchedArchiveId = await app.inject({
      method: "PUT",
      url: `/v1/rooms/${roomId}`,
      headers: {
        authorization: `Bearer ${ownerToken}`,
        "content-type": GSAVE_MEDIA_TYPE,
        "x-glib-room-id": "some-other-room",
        "if-match": "\"1\"",
      },
      payload: originalPayload,
    });
    assert.equal(mismatchedArchiveId.statusCode, 409);
    assert.equal(mismatchedArchiveId.json().error.code, "ARCHIVE_ID_MISMATCH");

    socket = await app.injectWS(
      "/v1/ws",
      { headers: { authorization: `Bearer ${ownerToken}` } },
      { onInit: (initialSocket) => initialSocket.on("message", captureSocketMessage) },
    );
    assert.equal(JSON.parse(await nextSocketMessage()).type, "ready");

    const replacementPayload = minimalGsave("portal-hall", "Portal Hall v2");
    const updateEventPromise = nextSocketMessage();
    const updated = await app.inject({
      method: "PUT",
      url: `/v1/rooms/${roomId}`,
      headers: {
        authorization: `Bearer ${ownerToken}`,
        "content-type": GSAVE_MEDIA_TYPE,
        "x-glib-room-id": "portal-hall",
        "if-match": "\"1\"",
        "x-glib-room-name": "Portal Hall v2",
      },
      payload: replacementPayload,
    });
    assert.equal(updated.statusCode, 200);
    assert.equal(updated.headers.etag, "\"2\"");
    assert.equal(updated.json().revision, "2");
    const updateEvent = JSON.parse(await updateEventPromise);
    assert.equal(updateEvent.type, "room.updated");
    assert.equal(updateEvent.data.roomId, roomId);
    assert.equal(updateEvent.data.revision, "2");

    const staleUpdate = await app.inject({
      method: "PUT",
      url: `/v1/rooms/${roomId}`,
      headers: {
        authorization: `Bearer ${ownerToken}`,
        "content-type": GSAVE_MEDIA_TYPE,
        "x-glib-room-id": "portal-hall",
        "if-match": "\"1\"",
      },
      payload: minimalGsave("portal-hall", "Stale"),
    });
    assert.equal(staleUpdate.statusCode, 412);

    const finalDownload = await app.inject({
      method: "GET",
      url: `/v1/rooms/${roomId}`,
      headers: { authorization: `Bearer ${ownerToken}` },
    });
    assert.deepEqual(finalDownload.rawPayload, replacementPayload);

    const forbiddenDelete = await app.inject({
      method: "DELETE",
      url: `/v1/rooms/${roomId}`,
      headers: { authorization: `Bearer ${otherToken}` },
    });
    assert.equal(forbiddenDelete.statusCode, 403);

    const deleteEventPromise = nextSocketMessage();
    const deleted = await app.inject({
      method: "DELETE",
      url: `/v1/rooms/${roomId}`,
      headers: { authorization: `Bearer ${ownerToken}` },
    });
    assert.equal(deleted.statusCode, 204);
    const deleteEvent = JSON.parse(await deleteEventPromise);
    assert.equal(deleteEvent.type, "room.deleted");
    assert.equal(deleteEvent.data.roomId, roomId);
    assert.equal(deleteEvent.data.archiveId, "portal-hall");

    const missingAfterDelete = await app.inject({
      method: "GET",
      url: `/v1/rooms/${roomId}`,
      headers: { authorization: `Bearer ${ownerToken}` },
    });
    assert.equal(missingAfterDelete.statusCode, 404);

    const oversized = await app.inject({
      method: "POST",
      url: "/v1/rooms",
      headers: {
        authorization: `Bearer ${ownerToken}`,
        "content-type": GSAVE_MEDIA_TYPE,
        "x-glib-room-id": "too-large",
        "x-glib-room-name": "Too Large",
      },
      payload: Buffer.alloc(GSAVE_MAX_BYTES + 1),
    });
    assert.equal(oversized.statusCode, 413);
    assert.equal(oversized.json().error.code, "GSAVE_TOO_LARGE");

    for (let index = 0; index < GSAVE_OWNER_MAX_ROOMS; index++) {
      const payload = Buffer.from([index]);
      await database.query(
        `INSERT INTO gsave_rooms(
           room_id, archive_id, owner_uuid, name, payload, payload_sha256, size_bytes
         ) VALUES ($1, $2, $3, $4, $5, $6, $7)`,
        [
          randomUUID(),
          `quota-${index}`,
          owner,
          `Quota ${index}`,
          payload,
          "0".repeat(64),
          payload.length,
        ],
      );
    }
    const overRoomCountQuota = await app.inject({
      method: "POST",
      url: "/v1/rooms",
      headers: {
        authorization: `Bearer ${ownerToken}`,
        "content-type": GSAVE_MEDIA_TYPE,
        "x-glib-room-id": "quota-overflow",
        "x-glib-room-name": "Quota overflow",
      },
      payload: minimalGsave("quota-overflow"),
    });
    assert.equal(overRoomCountQuota.statusCode, 409);
    assert.equal(overRoomCountQuota.json().error.code, "ROOM_COUNT_QUOTA_EXCEEDED");
  } finally {
    socket?.terminate();
    if (app) await app.close();
    await database.close();
  }
});

test("room storage migration and service size cap agree", async () => {
  assert.equal(GSAVE_MAX_BYTES, 8_388_608);
  assert.equal(GSAVE_OWNER_MAX_ROOMS, 100);
  assert.equal(GSAVE_OWNER_MAX_BYTES, 268_435_456);
  const sql = await readFile(
    resolve(dirname(fileURLToPath(import.meta.url)), "../migrations/006_gsave_rooms.sql"),
    "utf8",
  );
  assert.match(sql, /size_bytes <= 8388608/);
});
