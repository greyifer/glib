import { loadConfig } from "./config.js";
import { createPool, migrate } from "./db.js";
import { createApp } from "./app.js";

const config = loadConfig();
const pool = createPool(config);

async function migrateWithRetry(): Promise<void> {
  let lastError: unknown;
  for (let attempt = 1; attempt <= 10; attempt++) {
    try {
      await migrate(pool);
      return;
    } catch (error) {
      lastError = error;
      if (attempt < 10) await new Promise((resolve) => setTimeout(resolve, 2_000));
    }
  }
  throw lastError;
}

await migrateWithRetry();
const app = await createApp(pool, config);

const shutdown = async (signal: string): Promise<void> => {
  app.log.info({ signal }, "shutting down");
  await app.close();
  await pool.end();
  process.exit(0);
};

process.once("SIGTERM", () => void shutdown("SIGTERM"));
process.once("SIGINT", () => void shutdown("SIGINT"));

try {
  await app.listen({ host: config.host, port: config.port });
} catch (error) {
  app.log.error(error);
  await pool.end();
  process.exit(1);
}
