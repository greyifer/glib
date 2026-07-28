import assert from "node:assert/strict";
import test from "node:test";
import { loadConfig } from "./config.js";

const ENVIRONMENT_KEYS = [
  "DATABASE_URL",
  "GLIB_ROOM_SYNC_TOKEN",
  "GLIB_ROOM_SYNC_OWNER_UUID",
] as const;

test("room sync configuration is optional, paired, and requires a strong token", () => {
  const previous = new Map<string, string | undefined>(
    ENVIRONMENT_KEYS.map((key) => [key, process.env[key]]),
  );
  try {
    process.env.DATABASE_URL = "postgresql://unused";
    delete process.env.GLIB_ROOM_SYNC_TOKEN;
    delete process.env.GLIB_ROOM_SYNC_OWNER_UUID;
    const disabled = loadConfig();
    assert.equal(disabled.roomSyncToken, null);
    assert.equal(disabled.roomSyncOwnerUuid, null);

    process.env.GLIB_ROOM_SYNC_TOKEN = "s".repeat(43);
    assert.throws(loadConfig, /must either both be set or both be omitted/);

    process.env.GLIB_ROOM_SYNC_OWNER_UUID = "00000000-0000-4000-8000-000000000001";
    process.env.GLIB_ROOM_SYNC_TOKEN = "too-short";
    assert.throws(loadConfig, /32-256 printable ASCII characters/);

    const strongToken = "s".repeat(43);
    process.env.GLIB_ROOM_SYNC_TOKEN = strongToken;
    process.env.GLIB_ROOM_SYNC_OWNER_UUID = "00000000-0000-4000-8000-000000000001";
    const enabled = loadConfig();
    assert.equal(enabled.roomSyncToken, strongToken);
    assert.equal(enabled.roomSyncOwnerUuid, "00000000-0000-4000-8000-000000000001");
  } finally {
    for (const [key, value] of previous) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
});
