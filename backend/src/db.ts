import { readdir, readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import pg, { type Pool, type PoolClient, type QueryResultRow } from "pg";
import type { Config } from "./config.js";

const { types } = pg;
types.setTypeParser(20, (value) => value);

export function createPool(config: Config): Pool {
  return new pg.Pool({
    connectionString: config.databaseUrl,
    max: 10,
    idleTimeoutMillis: 30_000,
    connectionTimeoutMillis: 10_000,
    ssl: config.databaseSsl ? { rejectUnauthorized: true } : false,
  });
}

export async function migrate(pool: Pool): Promise<void> {
  const migrationsDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "../migrations");
  const files = (await readdir(migrationsDirectory))
    .filter((file) => /^\d+_.*\.sql$/.test(file))
    .sort();

  const client = await pool.connect();
  try {
    await client.query("SELECT pg_advisory_lock(711642091)");
    await client.query(`
      CREATE TABLE IF NOT EXISTS schema_migrations (
        version TEXT PRIMARY KEY,
        applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
      )
    `);
    for (const file of files) {
      const exists = await client.query("SELECT 1 FROM schema_migrations WHERE version = $1", [file]);
      if (exists.rowCount !== 0) continue;

      const sql = await readFile(resolve(migrationsDirectory, file), "utf8");
      await client.query("BEGIN");
      try {
        await client.query(sql);
        await client.query("INSERT INTO schema_migrations(version) VALUES ($1)", [file]);
        await client.query("COMMIT");
      } catch (error) {
        await client.query("ROLLBACK");
        throw error;
      }
    }
  } finally {
    await client.query("SELECT pg_advisory_unlock(711642091)").catch(() => undefined);
    client.release();
  }
}

export async function transaction<T>(pool: Pool, work: (client: PoolClient) => Promise<T>): Promise<T> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const result = await work(client);
    await client.query("COMMIT");
    return result;
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

export function first<T extends QueryResultRow>(rows: T[], message = "Expected a database row"): T {
  const value = rows[0];
  if (!value) throw new Error(message);
  return value;
}
