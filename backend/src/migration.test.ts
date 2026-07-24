import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { PGlite } from "@electric-sql/pglite";
import type { Pool } from "pg";
import type { RealtimeHub } from "./realtime.js";
import { AdminAuthService, hashAdminPassword, ModerationService, ReportService } from "./moderation.js";
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

test("typing events are transient, authenticated friend-only signals", async () => {
  const database = new PGlite();
  try {
    const migrationsDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "../migrations");
    await database.exec(await readFile(resolve(migrationsDirectory, "001_initial.sql"), "utf8"));

    const me = "00000000-0000-4000-8000-000000000001";
    const friend = "00000000-0000-4000-8000-000000000002";
    const stranger = "00000000-0000-4000-8000-000000000003";
    await database.query(
      `INSERT INTO users(player_uuid, username, normalized_username) VALUES
       ($1, 'Me', 'me'), ($2, 'Friend', 'friend'), ($3, 'Stranger', 'stranger')`,
      [me, friend, stranger],
    );
    await database.query("INSERT INTO friendships(player_low, player_high) VALUES ($1, $2)", [me, friend]);

    const events: Array<{ playerUuid: string; type: string; payload: unknown }> = [];
    const realtime = {
      send: (playerUuid: string, type: string, payload: unknown) => {
        events.push({ playerUuid, type, payload });
      },
    } as unknown as RealtimeHub;
    const pool = {
      query: async (sql: string, params?: unknown[]) => {
        const result = await database.query(sql, params);
        return { ...result, rowCount: result.rows.length };
      },
    } as unknown as Pool;
    const social = new SocialService(pool, realtime);
    await social.sendTyping({ playerUuid: me, username: "Me" }, friend, true);

    assert.deepEqual(events, [{
      playerUuid: friend,
      type: "chat.typing",
      payload: { playerUuid: me, username: "Me", typing: true },
    }]);
    await assert.rejects(social.sendTyping({ playerUuid: me, username: "Me" }, stranger, true));
    await assert.rejects(social.sendTyping({ playerUuid: me, username: "Me" }, friend, "true"));
  } finally {
    await database.close();
  }
});

test("group chats enforce membership, persistence, and the 20-player cap", async () => {
    const database = new PGlite();
  try {
    const migrationsDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "../migrations");
    for (const file of ["001_initial.sql", "002_groups.sql", "003_moderation.sql", "004_investigations.sql"]) {
      await database.exec(await readFile(resolve(migrationsDirectory, file), "utf8"));
    }

    const owner = "00000000-0000-4000-8000-000000000001";
    const friendUuids = Array.from({ length: 20 }, (_, index) =>
      `00000000-0000-4000-8000-${(index + 2).toString(16).padStart(12, "0")}`,
    );
    const stranger = "00000000-0000-4000-8000-000000000099";
    await database.query(
      "INSERT INTO users(player_uuid, username, normalized_username) VALUES ($1, 'Owner', 'owner'), ($2, 'Stranger', 'stranger')",
      [owner, stranger],
    );
    for (let index = 0; index < friendUuids.length; index++) {
      const playerUuid = friendUuids[index]!;
      await database.query(
        "INSERT INTO users(player_uuid, username, normalized_username) VALUES ($1, $2, $3)",
        [playerUuid, `Friend${index}`, `friend${index}`],
      );
      await database.query("INSERT INTO friendships(player_low, player_high) VALUES ($1, $2)", [owner, playerUuid]);
    }

    const query = async (sql: string, params?: unknown[]) => {
      const result = await database.query(sql, params);
      const affectedRows = "affectedRows" in result && typeof result.affectedRows === "number" ? result.affectedRows : 0;
      return { ...result, rowCount: result.rows.length > 0 ? result.rows.length : affectedRows };
    };
    const client = { query, release: () => undefined };
    const pool = { query, connect: async () => client } as unknown as Pool;
    const realtimeEvents: Array<{ type: string; payload: unknown }> = [];
    const realtime = {
      status: () => "ONLINE",
      isOnline: () => true,
      send: () => undefined,
      sendToGroup: async (_groupId: string, type: string, payload: unknown) => {
        realtimeEvents.push({ type, payload });
      },
    } as unknown as RealtimeHub;
    const social = new SocialService(pool, realtime);
    const actor = { playerUuid: owner, username: "Owner" };
    const group = await social.createGroup(actor, "Builders", [friendUuids[0]]);
    assert.equal(group.members.length, 2);
    assert.equal(group.name, "Builders");

    const sent = await social.sendGroupMessage(
      actor,
      group.groupId,
      "00000000-0000-4000-8000-000000000070",
      "hello group",
    );
    assert.equal(sent.body, "hello group");
    await assert.rejects(social.groupMessages({ playerUuid: stranger, username: "Stranger" }, group.groupId, undefined));

    for (const playerUuid of friendUuids.slice(1, 19)) {
      await database.query("INSERT INTO group_members(group_id, player_uuid) VALUES ($1, $2)", [group.groupId, playerUuid]);
    }
    await assert.rejects(social.addGroupMember(actor, group.groupId, friendUuids[19]));
    assert.ok(realtimeEvents.some((event) => event.type === "group.created"));
    assert.ok(realtimeEvents.some((event) => event.type === "group.message"));
  } finally {
    await database.close();
  }
});

test("message reports snapshot the reported sender boundaries and all messages between them", async () => {
  const database = new PGlite();
  try {
    const migrationsDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "../migrations");
    for (const file of ["001_initial.sql", "002_groups.sql", "003_moderation.sql", "004_investigations.sql"]) {
      await database.exec(await readFile(resolve(migrationsDirectory, file), "utf8"));
    }
    const reporter = "00000000-0000-4000-8000-000000000001";
    const reported = "00000000-0000-4000-8000-000000000002";
    await database.query(
      `INSERT INTO users(player_uuid, username, normalized_username)
       VALUES ($1, 'Reporter', 'reporter'), ($2, 'Reported', 'reported')`,
      [reporter, reported],
    );
    await database.query("INSERT INTO friendships(player_low, player_high) VALUES ($1, $2)", [reporter, reported]);
    const bodies = ["boundary before", "reply one", "reported message", "reply two", "boundary after"];
    for (let index = 0; index < bodies.length; index++) {
      const sender = index % 2 === 0 ? reported : reporter;
      const recipient = sender === reported ? reporter : reported;
      await database.query(
        `INSERT INTO messages(client_message_id, sender_uuid, recipient_uuid, body)
         VALUES ($1, $2, $3, $4)`,
        [`00000000-0000-4000-8000-${(index + 10).toString().padStart(12, "0")}`, sender, recipient, bodies[index]],
      );
    }
    const selected = await database.query<{ message_id: number }>(
      "SELECT message_id FROM messages WHERE body = 'reported message'",
    );
    const query = async (sql: string, params?: unknown[]) => {
      const result = await database.query(sql, params);
      const affectedRows = "affectedRows" in result && typeof result.affectedRows === "number" ? result.affectedRows : 0;
      return { ...result, rowCount: result.rows.length > 0 ? result.rows.length : affectedRows };
    };
    const client = { query, release: () => undefined };
    const pool = { query, connect: async () => client } as unknown as Pool;
    const reports = new ReportService(pool);
    const created = await reports.submit(
      { playerUuid: reporter, username: "Reporter" },
      "DIRECT",
      String(selected.rows[0]!.message_id),
      "Harassment",
    ) as { reportId: string; contextMessageCount: number };
    assert.equal(created.contextMessageCount, 5);
    const context = await database.query<{ body: string }>(
      "SELECT body FROM message_report_context WHERE report_id = $1 ORDER BY sequence_number",
      [created.reportId],
    );
    assert.deepEqual(context.rows.map((row) => row.body), bodies);
    await assert.rejects(
      reports.submit({ playerUuid: reporter, username: "Reporter" }, "DIRECT", "2", ""),
    );
    await assert.rejects(
      reports.submit(
        { playerUuid: reporter, username: "Reporter" },
        "DIRECT",
        String(selected.rows[0]!.message_id),
        "duplicate",
      ),
    );
  } finally {
    await database.close();
  }
});

test("moderation investigations persist search metadata, bookmarks, notes, and stars", async () => {
  const database = new PGlite();
  try {
    const migrationsDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "../migrations");
    for (const file of ["001_initial.sql", "002_groups.sql", "003_moderation.sql", "004_investigations.sql"]) {
      await database.exec(await readFile(resolve(migrationsDirectory, file), "utf8"));
    }
    const firstPlayer = "00000000-0000-4000-8000-000000000001";
    const secondPlayer = "00000000-0000-4000-8000-000000000002";
    await database.query(
      `INSERT INTO users(player_uuid, username, normalized_username) VALUES
       ($1, 'FirstNow', 'firstnow'), ($2, 'SecondNow', 'secondnow')`,
      [firstPlayer, secondPlayer],
    );
    await database.query(
      `INSERT INTO admin_accounts(player_uuid, role, password_hash, must_change_password)
       VALUES ($1, 'MODERATOR', 'unused-test-hash', false)`,
      [firstPlayer],
    );
    await database.query(
      `INSERT INTO messages(client_message_id, sender_uuid, recipient_uuid, sender_username_at_send, body)
       VALUES ('00000000-0000-4000-8000-000000000010', $1, $2, 'FirstOldName', 'snapshot keyword')`,
      [firstPlayer, secondPlayer],
    );

    const query = async (sql: string, params?: unknown[]) => {
      const result = await database.query(sql, params);
      const affectedRows = "affectedRows" in result && typeof result.affectedRows === "number" ? result.affectedRows : 0;
      return { ...result, rowCount: result.rows.length > 0 ? result.rows.length : affectedRows };
    };
    const client = { query, release: () => undefined };
    const pool = { query, connect: async () => client } as unknown as Pool;
    const auth = new AdminAuthService(pool, 30);
    const moderation = new ModerationService(pool, auth);
    const actor = {
      playerUuid: firstPlayer,
      username: "FirstNow",
      role: "MODERATOR" as const,
      mustChangePassword: false,
    };
    const history = await moderation.directMessages(
      actor,
      firstPlayer,
      secondPlayer,
      undefined,
      "keyword",
      undefined,
      "ALL",
      undefined,
      "127.0.0.1",
    ) as {
      conversationId: string;
      messages: Array<{ messageId: string; senderUsername: string }>;
      bookmarked: boolean;
      notes: unknown[];
      starredMessageIds: string[];
    };
    assert.equal(history.messages[0]?.senderUsername, "FirstOldName");
    assert.equal(history.bookmarked, false);

    await moderation.setBookmark(actor, "DIRECT", history.conversationId, true, "127.0.0.1");
    const note = await moderation.addCaseNote(
      actor, "DIRECT", history.conversationId, "Private factual note", "127.0.0.1",
    ) as { body: string };
    assert.equal(note.body, "Private factual note");
    await moderation.setMessageStar(
      actor, "DIRECT", String(history.messages[0]!.messageId), true, "127.0.0.1",
    );
    const reloaded = await moderation.directMessages(
      actor,
      firstPlayer,
      secondPlayer,
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      "127.0.0.1",
    ) as {
      bookmarked: boolean;
      notes: Array<{ body: string }>;
      starredMessageIds: string[];
    };
    assert.equal(reloaded.bookmarked, true);
    assert.equal(reloaded.notes[0]?.body, "Private factual note");
    assert.equal(reloaded.starredMessageIds.length, 1);

    await database.query("UPDATE messages SET created_at = now() - interval '40 days'");
    const social = new SocialService(pool, {} as RealtimeHub);
    await social.cleanMessages(30);
    const tombstone = await database.query<{ body: string; state: string }>(
      "SELECT body, state FROM messages LIMIT 1",
    );
    assert.deepEqual(tombstone.rows[0], { body: "[removed]", state: "DELETED" });
  } finally {
    await database.close();
  }
});

test("moderator login is UUID-bound, forces an initial password change, and stores only hashes", async () => {
  const database = new PGlite();
  try {
    const migrationsDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "../migrations");
    for (const file of ["001_initial.sql", "002_groups.sql", "003_moderation.sql"]) {
      await database.exec(await readFile(resolve(migrationsDirectory, file), "utf8"));
    }
    const moderator = "00000000-0000-4000-8000-000000000001";
    const other = "00000000-0000-4000-8000-000000000002";
    await database.query(
      `INSERT INTO users(player_uuid, username, normalized_username)
       VALUES ($1, 'Moderator', 'moderator'), ($2, 'Other', 'other')`,
      [moderator, other],
    );
    const initialPassword = "initial moderator password";
    const initialHash = await hashAdminPassword(initialPassword);
    assert.equal(initialHash.includes(initialPassword), false);
    await database.query(
      "INSERT INTO admin_accounts(player_uuid, role, password_hash) VALUES ($1, 'MODERATOR', $2)",
      [moderator, initialHash],
    );
    const query = async (sql: string, params?: unknown[]) => {
      const result = await database.query(sql, params);
      const affectedRows = "affectedRows" in result && typeof result.affectedRows === "number" ? result.affectedRows : 0;
      return { ...result, rowCount: result.rows.length > 0 ? result.rows.length : affectedRows };
    };
    const client = { query, release: () => undefined };
    const pool = { query, connect: async () => client } as unknown as Pool;
    const auth = new AdminAuthService(pool, 30);
    const actor = { playerUuid: moderator, username: "Moderator" };
    const session = await auth.login(actor, initialPassword, "127.0.0.1");
    assert.equal(session.mustChangePassword, true);
    assert.equal((await auth.actorForToken(actor, session.token)).playerUuid, moderator);
    await assert.rejects(auth.actorForToken({ playerUuid: other, username: "Other" }, session.token));

    await auth.changePassword(
      await auth.actorForToken(actor, session.token),
      session.token,
      initialPassword,
      "a replacement moderator password",
      "127.0.0.1",
    );
    const access = await auth.access(actor);
    assert.equal(access.mustChangePassword, false);
    await assert.rejects(auth.login(actor, initialPassword, "127.0.0.1"));
    assert.equal((await auth.login(actor, "a replacement moderator password", "127.0.0.1")).role, "MODERATOR");
  } finally {
    await database.close();
  }
});
