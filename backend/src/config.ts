export interface Config {
  databaseUrl: string;
  databaseSsl: boolean;
  host: string;
  port: number;
  logLevel: string;
  sessionTtlHours: number;
  adminSessionTtlMinutes: number;
  messageRetentionDays: number;
  trustProxy: boolean;
}

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}

function integer(name: string, fallback: number, minimum: number, maximum: number): number {
  const raw = process.env[name];
  const value = raw === undefined ? fallback : Number.parseInt(raw, 10);
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be an integer from ${minimum} through ${maximum}`);
  }
  return value;
}

function boolean(name: string, fallback: boolean): boolean {
  const raw = process.env[name]?.trim().toLowerCase();
  if (raw === undefined || raw === "") return fallback;
  if (raw === "true") return true;
  if (raw === "false") return false;
  throw new Error(`${name} must be true or false`);
}

export function loadConfig(): Config {
  return {
    databaseUrl: required("DATABASE_URL"),
    databaseSsl: boolean("DATABASE_SSL", false),
    host: process.env.HOST?.trim() || "0.0.0.0",
    port: integer("PORT", 8080, 1, 65535),
    logLevel: process.env.LOG_LEVEL?.trim() || "info",
    sessionTtlHours: integer("SESSION_TTL_HOURS", 12, 1, 168),
    adminSessionTtlMinutes: integer("ADMIN_SESSION_TTL_MINUTES", 30, 5, 120),
    messageRetentionDays: integer("MESSAGE_RETENTION_DAYS", 90, 1, 3650),
    trustProxy: boolean("TRUST_PROXY", false),
  };
}
