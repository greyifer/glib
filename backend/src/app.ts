import rateLimit from "@fastify/rate-limit";
import websocket from "@fastify/websocket";
import Fastify, { type FastifyInstance, type FastifyRequest } from "fastify";
import type { Pool } from "pg";
import type { RawData, WebSocket } from "ws";
import type { Config } from "./config.js";
import { ApiError, parseBearer } from "./domain.js";
import { RealtimeHub } from "./realtime.js";
import { AuthService, SocialService } from "./services.js";

type JsonObject = Record<string, unknown>;

function body(request: FastifyRequest): JsonObject {
  if (typeof request.body !== "object" || request.body === null || Array.isArray(request.body)) {
    throw new ApiError(400, "INVALID_BODY", "A JSON object is required.");
  }
  return request.body as JsonObject;
}

function param(request: FastifyRequest, name: string): unknown {
  return (request.params as Record<string, unknown> | undefined)?.[name];
}

function query(request: FastifyRequest, name: string): unknown {
  return (request.query as Record<string, unknown> | undefined)?.[name];
}

function websocketError(socket: WebSocket, error: unknown): void {
  const apiError = error instanceof ApiError ? error : new ApiError(500, "INTERNAL_ERROR", "An unexpected error occurred.");
  socket.send(JSON.stringify({ type: "error", data: { code: apiError.code, message: apiError.message } }));
}

export async function createApp(pool: Pool, config: Config): Promise<FastifyInstance> {
  const app = Fastify({
    logger: { level: config.logLevel },
    bodyLimit: 16 * 1024,
    trustProxy: config.trustProxy,
    requestTimeout: 15_000,
    connectionTimeout: 10_000,
  });
  app.decorateRequest("actor", null);
  await app.register(rateLimit, { global: true, max: 180, timeWindow: "1 minute" });
  await app.register(websocket, { options: { maxPayload: 8 * 1024 } });

  const auth = new AuthService(pool, config.sessionTtlHours);
  const realtime = new RealtimeHub(pool);
  const social = new SocialService(pool, realtime);
  realtime.start();

  const authenticate = async (request: FastifyRequest): Promise<void> => {
    const token = parseBearer(request.headers.authorization);
    request.actor = await auth.actorForToken(token);
  };

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof ApiError) {
      return reply.status(error.statusCode).send({ error: { code: error.code, message: error.message } });
    }
    if (typeof error === "object" && error !== null && "statusCode" in error && error.statusCode === 429) {
      return reply.status(429).send({ error: { code: "RATE_LIMITED", message: "Too many requests. Please wait and try again." } });
    }
    request.log.error({ err: error }, "request failed");
    return reply.status(500).send({ error: { code: "INTERNAL_ERROR", message: "An unexpected error occurred." } });
  });

  app.get("/health", { config: { rateLimit: false } }, async (_request, reply) => {
    try {
      await pool.query("SELECT 1");
      return { status: "ok" };
    } catch {
      return reply.status(503).send({ status: "unavailable" });
    }
  });

  app.post(
    "/v1/auth/challenge",
    { config: { rateLimit: { max: 8, timeWindow: "1 minute" } } },
    async (request, reply) => reply.status(201).send(await auth.createChallenge(body(request).username)),
  );
  app.post(
    "/v1/auth/complete",
    { config: { rateLimit: { max: 8, timeWindow: "1 minute" } } },
    async (request) => auth.completeChallenge(body(request).challengeId),
  );
  app.post("/v1/auth/logout", { preHandler: authenticate }, async (request, reply) => {
    await auth.revoke(parseBearer(request.headers.authorization));
    return reply.status(204).send();
  });

  app.get("/v1/social", { preHandler: authenticate }, async (request) => social.snapshot(request.actor!));
  app.get(
    "/v1/players/search",
    { preHandler: authenticate, config: { rateLimit: { max: 90, timeWindow: "1 minute" } } },
    async (request) => social.searchPlayers(request.actor!, query(request, "q")),
  );
  app.delete("/v1/me", { preHandler: authenticate }, async (request, reply) => {
    await social.deleteAccount(request.actor!);
    return reply.status(204).send();
  });
  app.post(
    "/v1/friend-requests",
    { preHandler: authenticate, config: { rateLimit: { max: 20, timeWindow: "1 hour" } } },
    async (request, reply) => reply.status(201).send(await social.sendRequest(request.actor!, body(request).username)),
  );
  app.post("/v1/friend-requests/:requestId/accept", { preHandler: authenticate }, async (request) =>
    social.acceptRequest(request.actor!, param(request, "requestId")),
  );
  app.delete("/v1/friend-requests/:requestId", { preHandler: authenticate }, async (request, reply) => {
    await social.deleteRequest(request.actor!, param(request, "requestId"));
    return reply.status(204).send();
  });
  app.delete("/v1/friends/:playerUuid", { preHandler: authenticate }, async (request, reply) => {
    await social.removeFriend(request.actor!, param(request, "playerUuid"));
    return reply.status(204).send();
  });
  app.post("/v1/blocks", { preHandler: authenticate }, async (request, reply) => {
    await social.block(request.actor!, body(request).playerUuid);
    return reply.status(204).send();
  });
  app.delete("/v1/blocks/:playerUuid", { preHandler: authenticate }, async (request, reply) => {
    await social.unblock(request.actor!, param(request, "playerUuid"));
    return reply.status(204).send();
  });
  app.get("/v1/messages/:playerUuid", { preHandler: authenticate }, async (request) =>
    social.messages(request.actor!, param(request, "playerUuid"), query(request, "before")),
  );
  app.post(
    "/v1/messages/:playerUuid",
    { preHandler: authenticate, config: { rateLimit: { max: 120, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const data = body(request);
      const message = await social.sendMessage(
        request.actor!,
        param(request, "playerUuid"),
        data.clientMessageId,
        data.body,
      );
      return reply.status(201).send(message);
    },
  );

  app.get(
    "/v1/ws",
    { websocket: true, preValidation: authenticate },
    (socket, request) => {
      const actor = request.actor!;
      const sentAt: number[] = [];
      const sessionTimeout = setTimeout(
        () => socket.close(4001, "Session expired"),
        config.sessionTtlHours * 60 * 60 * 1000,
      );
      sessionTimeout.unref();
      socket.once("close", () => clearTimeout(sessionTimeout));

      socket.on("message", (raw: RawData) => {
        void handleSocketMessage(socket, raw, actor, sentAt, social);
      });
      void realtime.add(actor, socket).then(
        () => socket.send(JSON.stringify({ type: "ready", data: { playerUuid: actor.playerUuid } })),
        () => socket.close(1011, "Unable to initialize connection"),
      );
    },
  );

  app.addHook("onClose", async () => {
    realtime.stop();
  });

  const cleanup = setInterval(() => {
    void Promise.all([auth.cleanExpired(), social.cleanMessages(config.messageRetentionDays)]).catch((error) => {
      app.log.error({ err: error }, "scheduled cleanup failed");
    });
  }, 60 * 60 * 1000);
  cleanup.unref();
  app.addHook("onClose", async () => clearInterval(cleanup));
  return app;
}

async function handleSocketMessage(
  socket: WebSocket,
  raw: RawData,
  actor: NonNullable<FastifyRequest["actor"]>,
  sentAt: number[],
  social: SocialService,
): Promise<void> {
  try {
    const now = Date.now();
    while (sentAt[0] !== undefined && sentAt[0] < now - 10_000) sentAt.shift();
    if (sentAt.length >= 20) throw new ApiError(429, "RATE_LIMITED", "You are sending messages too quickly.");
    sentAt.push(now);

    const parsed = JSON.parse(raw.toString()) as unknown;
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      throw new ApiError(400, "INVALID_EVENT", "A JSON event object is required.");
    }
    const event = parsed as JsonObject;
    if (event.type === "ping") {
      socket.send(JSON.stringify({ type: "pong", data: {} }));
      return;
    }
    if (event.type !== "chat.send" || typeof event.data !== "object" || event.data === null || Array.isArray(event.data)) {
      throw new ApiError(400, "INVALID_EVENT", "Unsupported WebSocket event.");
    }
    const data = event.data as JsonObject;
    await social.sendMessage(actor, data.toPlayerUuid, data.clientMessageId, data.body);
  } catch (error) {
    websocketError(socket, error);
  }
}
