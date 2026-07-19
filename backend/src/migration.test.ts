import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { PGlite } from "@electric-sql/pglite";

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
