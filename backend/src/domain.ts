import { createHash, randomBytes } from "node:crypto";

const USERNAME_PATTERN = /^[A-Za-z0-9_]{1,16}$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const CLIENT_MESSAGE_ID_PATTERN = UUID_PATTERN;

export class ApiError extends Error {
  constructor(
    public readonly statusCode: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export function normalizeUsername(value: unknown): string {
  if (typeof value !== "string" || !USERNAME_PATTERN.test(value)) {
    throw new ApiError(400, "INVALID_USERNAME", "Minecraft usernames are 1-16 letters, numbers, or underscores.");
  }
  return value.toLowerCase();
}

export function normalizePlayerSearch(value: unknown): string {
  if (typeof value !== "string") {
    throw new ApiError(400, "INVALID_SEARCH", "Search text is required.");
  }
  const query = value.trim();
  if (query.length < 2 || query.length > 16 || !USERNAME_PATTERN.test(query)) {
    throw new ApiError(400, "INVALID_SEARCH", "Type 2-16 letters, numbers, or underscores.");
  }
  return query.toLowerCase();
}

export function parseUuid(value: unknown, field = "playerUuid"): string {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    throw new ApiError(400, "INVALID_UUID", `${field} must be a UUID.`);
  }
  return value.toLowerCase();
}

export function parseClientMessageId(value: unknown): string {
  if (typeof value !== "string" || !CLIENT_MESSAGE_ID_PATTERN.test(value)) {
    throw new ApiError(400, "INVALID_CLIENT_MESSAGE_ID", "clientMessageId must be a UUID.");
  }
  return value.toLowerCase();
}

export function validateMessage(value: unknown): string {
  if (typeof value !== "string") {
    throw new ApiError(400, "INVALID_MESSAGE", "Message body must be text.");
  }
  const body = value.trim();
  if (body.length === 0 || body.length > 500) {
    throw new ApiError(400, "INVALID_MESSAGE", "Messages must contain 1-500 characters.");
  }
  return body;
}

export function orderedPair(first: string, second: string): [string, string] {
  if (first === second) {
    throw new ApiError(400, "SELF_TARGET", "You cannot perform this action on yourself.");
  }
  return first < second ? [first, second] : [second, first];
}

export function randomToken(bytes = 32): string {
  return randomBytes(bytes).toString("base64url");
}

export function hashToken(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

export function parseBearer(header: string | undefined): string {
  if (!header?.startsWith("Bearer ")) {
    throw new ApiError(401, "UNAUTHENTICATED", "A bearer token is required.");
  }
  const token = header.slice(7).trim();
  if (!/^[A-Za-z0-9_-]{40,128}$/.test(token)) {
    throw new ApiError(401, "UNAUTHENTICATED", "The bearer token is invalid.");
  }
  return token;
}

export function mojangUuid(value: unknown): string {
  if (typeof value !== "string" || !/^[0-9a-f]{32}$/i.test(value)) {
    throw new ApiError(502, "AUTH_UPSTREAM_INVALID", "Minecraft returned an invalid profile.");
  }
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`.toLowerCase();
}
