import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { PGlite } from "@electric-sql/pglite";
import type { Pool } from "pg";
import type { RealtimeHub } from "./realtime.js";
import { SocialService } from "./services.js";

test("initial migration creates usable social tables and constraints", async () => {
  const database = new PGlite();
  try {
    const migrationPath = resolve(dirname(fileURLToPath(import.meta.url)), "../migrations/001_initial.sql");
    await database.exec(await readFile(migrationPath, "utf8"));

    const tables = await database.query<{ table_name: string }>(
      `SELECT table_name FROM information_schema.tables
       WHERE table_schema = 'public' ORDER BY table_name`,
    );
    assert.deepEqual(
      tables.rows.map((row) => row.table_name),
      ["auth_challenges", "blocks", "friend_requests", "friendships", "messages", "schema_migrations", "sessions", "users"],
    );

    const first = "00000000-0000-4000-8000-000000000001";
    const second = "00000000-0000-4000-8000-000000000002";
    await database.query(
      `INSERT INTO users(player_uuid, username, normalized_username)
       VALUES ($1, 'PlayerOne', 'playerone'), ($2, 'PlayerTwo', 'playertwo')`,
      [first, second],
    );
    await database.query("INSERT INTO friendships(player_low, player_high) VALUES ($1, $2)", [first, second]);
    await database.query(
      `INSERT INTO messages(client_message_id, sender_uuid, recipient_uuid, body)
       VALUES ('00000000-0000-4000-8000-000000000003', $1, $2, 'hello')`,
      [first, second],
    );
    const messages = await database.query<{ body: string }>("SELECT body FROM messages");
    assert.equal(messages.rows[0]?.body, "hello");

    await assert.rejects(
      database.query("INSERT INTO friendships(player_low, player_high) VALUES ($1, $2)", [second, first]),
    );
  } finally {
    await database.close();
  }
});

test("player search orders matches and reports relationships without blocked players", async () => {
  const database = new PGlite();
  try {
    const migrationsDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "../migrations");
    await database.exec(await readFile(resolve(migrationsDirectory, "001_initial.sql"), "utf8"));

    const me = "00000000-0000-4000-8000-000000000001";
    const friend = "00000000-0000-4000-8000-000000000002";
    const incoming = "00000000-0000-4000-8000-000000000003";
    const blocked = "00000000-0000-4000-8000-000000000004";
    const underscores = "00000000-0000-4000-8000-000000000006";
    await database.query(
      `INSERT INTO users(player_uuid, username, normalized_username) VALUES
       ($1, 'Me', 'me'), ($2, 'Alpha', 'alpha'), ($3, 'MyAlpha', 'myalpha'),
       ($4, 'AlphaBlocked', 'alphablocked'), ($5, 'Only__Me', 'only__me')`,
      [me, friend, incoming, blocked, underscores],
    );
    await database.query("INSERT INTO friendships(player_low, player_high) VALUES ($1, $2)", [me, friend]);
    await database.query(
      "INSERT INTO friend_requests(request_id, sender_uuid, recipient_uuid) VALUES ('00000000-0000-4000-8000-000000000005', $1, $2)",
      [incoming, me],
    );
    await database.query("INSERT INTO blocks(blocker_uuid, blocked_uuid) VALUES ($1, $2)", [me, blocked]);

    const social = new SocialService(database as unknown as Pool, {} as RealtimeHub);
    const results = await social.searchPlayers({ playerUuid: me, username: "Me" }, "alpha") as Array<Record<string, unknown>>;
    assert.deepEqual(results.map((result) => [result.username, result.relationship]), [
      ["Alpha", "FRIEND"],
      ["MyAlpha", "INCOMING"],
    ]);
    assert.equal(results[1]?.requestId, "00000000-0000-4000-8000-000000000005");
    const literalUnderscoreResults = await social.searchPlayers({ playerUuid: me, username: "Me" }, "__") as Array<Record<string, unknown>>;
    assert.deepEqual(literalUnderscoreResults.map((result) => result.username), ["Only__Me"]);
  } finally {
    await database.close();
  }
});
