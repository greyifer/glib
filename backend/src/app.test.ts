import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { PGlite } from "@electric-sql/pglite";
import type { Pool } from "pg";
import { createApp } from "./app.js";
import type { Config } from "./config.js";
import { hashToken } from "./domain.js";
import { hashAdminPassword } from "./moderation.js";

test("moderator routes require both a moderator token and its matching Minecraft-authenticated UUID", async () => {
  const database = new PGlite();
  let app: Awaited<ReturnType<typeof createApp>> | undefined;
  try {
    const migrationsDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "../migrations");
    for (const file of [
      "001_initial.sql",
      "002_groups.sql",
      "003_moderation.sql",
      "004_investigations.sql",
      "005_social_access_restrictions.sql",
    ]) {
      await database.exec(await readFile(resolve(migrationsDirectory, file), "utf8"));
    }
    const moderator = "00000000-0000-4000-8000-000000000001";
    const normalUser = "00000000-0000-4000-8000-000000000002";
    const moderatorToken = "m".repeat(43);
    const normalToken = "n".repeat(43);
    await database.query(
      `INSERT INTO users(player_uuid, username, normalized_username)
       VALUES ($1, 'Moderator', 'moderator'), ($2, 'NormalUser', 'normaluser')`,
      [moderator, normalUser],
    );
    await database.query(
      `INSERT INTO sessions(token_hash, player_uuid, expires_at)
       VALUES ($1, $2, now() + interval '1 hour'), ($3, $4, now() + interval '1 hour')`,
      [hashToken(moderatorToken), moderator, hashToken(normalToken), normalUser],
    );
    await database.query(
      `INSERT INTO admin_accounts(player_uuid, role, password_hash, must_change_password)
       VALUES ($1, 'MODERATOR', $2, false)`,
      [moderator, await hashAdminPassword("moderator test password")],
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
    };
    app = await createApp(pool, config);

    const normalAccess = await app.inject({
      method: "GET",
      url: "/v1/admin/access",
      headers: { authorization: `Bearer ${normalToken}` },
    });
    assert.equal(normalAccess.statusCode, 200);
    assert.equal(normalAccess.json().eligible, false);

    const missingAdminToken = await app.inject({
      method: "GET",
      url: "/v1/admin/users",
      headers: { authorization: `Bearer ${moderatorToken}` },
    });
    assert.equal(missingAdminToken.statusCode, 401);

    const login = await app.inject({
      method: "POST",
      url: "/v1/admin/login",
      headers: { authorization: `Bearer ${moderatorToken}` },
      payload: { password: "moderator test password" },
    });
    assert.equal(login.statusCode, 200);
    const unlockedToken = login.json().token as string;

    const stolenForDifferentUuid = await app.inject({
      method: "GET",
      url: "/v1/admin/users",
      headers: {
        authorization: `Bearer ${normalToken}`,
        "x-glib-admin-authorization": `Bearer ${unlockedToken}`,
      },
    });
    assert.equal(stolenForDifferentUuid.statusCode, 401);

    const authorized = await app.inject({
      method: "GET",
      url: "/v1/admin/users",
      headers: {
        authorization: `Bearer ${moderatorToken}`,
        "x-glib-admin-authorization": `Bearer ${unlockedToken}`,
      },
    });
    assert.equal(authorized.statusCode, 200);
    assert.deepEqual(
      (authorized.json() as Array<{ username: string }>).map((user) => user.username),
      ["Moderator", "NormalUser"],
    );

    const disabled = await app.inject({
      method: "PATCH",
      url: `/v1/admin/users/${normalUser}/social-access`,
      headers: {
        authorization: `Bearer ${moderatorToken}`,
        "x-glib-admin-authorization": `Bearer ${unlockedToken}`,
      },
      payload: { enabled: false, reason: "Temporary moderation restriction" },
    });
    assert.equal(disabled.statusCode, 200);
    assert.equal(disabled.json().enabled, false);

    const blockedSocial = await app.inject({
      method: "GET",
      url: "/v1/social",
      headers: { authorization: `Bearer ${normalToken}` },
    });
    assert.equal(blockedSocial.statusCode, 403);
    assert.equal(blockedSocial.json().error.code, "SOCIAL_ACCESS_DISABLED");

    const stillCanCheckAdminAccess = await app.inject({
      method: "GET",
      url: "/v1/admin/access",
      headers: { authorization: `Bearer ${normalToken}` },
    });
    assert.equal(stillCanCheckAdminAccess.statusCode, 200);

    const selfRestriction = await app.inject({
      method: "PATCH",
      url: `/v1/admin/users/${moderator}/social-access`,
      headers: {
        authorization: `Bearer ${moderatorToken}`,
        "x-glib-admin-authorization": `Bearer ${unlockedToken}`,
      },
      payload: { enabled: false, reason: "Should be rejected" },
    });
    assert.equal(selfRestriction.statusCode, 403);
    assert.equal(selfRestriction.json().error.code, "SOCIAL_ACCESS_SELF_TARGET");

    const restored = await app.inject({
      method: "PATCH",
      url: `/v1/admin/users/${normalUser}/social-access`,
      headers: {
        authorization: `Bearer ${moderatorToken}`,
        "x-glib-admin-authorization": `Bearer ${unlockedToken}`,
      },
      payload: { enabled: true, reason: "Restriction reviewed and cleared" },
    });
    assert.equal(restored.statusCode, 200);
    assert.equal(restored.json().enabled, true);

    const socialAfterRestore = await app.inject({
      method: "GET",
      url: "/v1/social",
      headers: { authorization: `Bearer ${normalToken}` },
    });
    assert.equal(socialAfterRestore.statusCode, 200);

    const audit = await database.query<{ action: string }>(
      `SELECT action FROM admin_audit_log
       WHERE target_id = $1 AND action LIKE 'ADMIN_SOCIAL_ACCESS_%'
       ORDER BY audit_id`,
      [normalUser],
    );
    assert.deepEqual(
      audit.rows.map((row) => row.action),
      ["ADMIN_SOCIAL_ACCESS_DISABLED", "ADMIN_SOCIAL_ACCESS_RESTORED"],
    );
  } finally {
    if (app) await app.close();
    await database.close();
  }
});
