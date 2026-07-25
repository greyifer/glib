import rateLimit from "@fastify/rate-limit";
import websocket from "@fastify/websocket";
import Fastify, { type FastifyInstance, type FastifyRequest } from "fastify";
import type { Pool } from "pg";
import type { RawData, WebSocket } from "ws";
import type { Config } from "./config.js";
import { ApiError, parseBearer } from "./domain.js";
import { AdminAuthService, ModerationService, ReportService } from "./moderation.js";
import { RealtimeHub } from "./realtime.js";
import { AuthService, SocialService } from "./services.js";
import { requireSocialAccess } from "./social-access.js";

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

function adminBearer(request: FastifyRequest): string {
  const header = request.headers["x-glib-admin-authorization"];
  if (Array.isArray(header)) throw new ApiError(401, "ADMIN_UNAUTHENTICATED", "A moderator token is required.");
  try {
    return parseBearer(header);
  } catch {
    throw new ApiError(401, "ADMIN_UNAUTHENTICATED", "A moderator token is required.");
  }
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
  app.decorateRequest("adminActor", null);
  await app.register(rateLimit, { global: true, max: 180, timeWindow: "1 minute" });
  await app.register(websocket, { options: { maxPayload: 8 * 1024 } });

  const auth = new AuthService(pool, config.sessionTtlHours);
  const realtime = new RealtimeHub(pool);
  const social = new SocialService(pool, realtime);
  const adminAuth = new AdminAuthService(pool, config.adminSessionTtlMinutes);
  const moderation = new ModerationService(pool, adminAuth, realtime);
  const reports = new ReportService(pool);
  realtime.start();

  const authenticate = async (request: FastifyRequest): Promise<void> => {
    const token = parseBearer(request.headers.authorization);
    request.actor = await auth.actorForToken(token);
  };
  const authenticateSocial = async (request: FastifyRequest): Promise<void> => {
    await authenticate(request);
    await requireSocialAccess(pool, request.actor!.playerUuid);
  };
  const authenticateAdmin = async (request: FastifyRequest): Promise<void> => {
    await authenticate(request);
    request.adminActor = await adminAuth.actorForToken(request.actor!, adminBearer(request));
  };
  const requireReadyAdmin = async (request: FastifyRequest): Promise<void> => {
    await authenticateAdmin(request);
    if (request.adminActor!.mustChangePassword) {
      throw new ApiError(403, "ADMIN_PASSWORD_CHANGE_REQUIRED", "Change the initial moderator password before continuing.");
    }
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

  app.get("/v1/admin/access", { preHandler: authenticate }, async (request) => adminAuth.access(request.actor!));
  app.post(
    "/v1/admin/login",
    {
      preHandler: authenticate,
      config: { rateLimit: { max: 5, timeWindow: "15 minutes" } },
    },
    async (request) => adminAuth.login(request.actor!, body(request).password, request.ip),
  );
  app.post("/v1/admin/logout", { preHandler: authenticateAdmin }, async (request, reply) => {
    await adminAuth.revoke(adminBearer(request));
    return reply.status(204).send();
  });
  app.post("/v1/admin/password", { preHandler: authenticateAdmin }, async (request, reply) => {
    const data = body(request);
    await adminAuth.changePassword(
      request.adminActor!,
      adminBearer(request),
      data.currentPassword,
      data.newPassword,
      request.ip,
    );
    return reply.status(204).send();
  });
  app.get("/v1/admin/users", { preHandler: requireReadyAdmin }, async (request) =>
    moderation.users(request.adminActor!, query(request, "q"), request.ip),
  );
  app.get("/v1/admin/users/:playerUuid", { preHandler: requireReadyAdmin }, async (request) =>
    moderation.user(request.adminActor!, param(request, "playerUuid"), request.ip),
  );
  app.patch("/v1/admin/users/:playerUuid/social-access", { preHandler: requireReadyAdmin }, async (request) => {
    const data = body(request);
    return moderation.setSocialAccess(
      request.adminActor!,
      param(request, "playerUuid"),
      data.enabled,
      data.reason,
      request.ip,
    );
  });
  app.get("/v1/admin/direct/:firstUuid/:secondUuid", { preHandler: requireReadyAdmin }, async (request) =>
    moderation.directMessages(
      request.adminActor!,
      param(request, "firstUuid"),
      param(request, "secondUuid"),
      query(request, "before"),
      query(request, "q"),
      query(request, "sender"),
      query(request, "filter"),
      query(request, "around"),
      request.ip,
    ),
  );
  app.get("/v1/admin/groups/:groupId/messages", { preHandler: requireReadyAdmin }, async (request) =>
    moderation.groupMessages(
      request.adminActor!,
      param(request, "groupId"),
      query(request, "before"),
      query(request, "q"),
      query(request, "sender"),
      query(request, "filter"),
      query(request, "around"),
      request.ip,
    ),
  );
  app.get("/v1/admin/reports", { preHandler: requireReadyAdmin }, async (request) =>
    moderation.reports(request.adminActor!, query(request, "status"), request.ip),
  );
  app.get("/v1/admin/reports/:reportId", { preHandler: requireReadyAdmin }, async (request) =>
    moderation.report(request.adminActor!, param(request, "reportId"), request.ip),
  );
  app.patch("/v1/admin/reports/:reportId", { preHandler: requireReadyAdmin }, async (request) =>
    moderation.updateReport(
      request.adminActor!,
      param(request, "reportId"),
      body(request).status,
      request.ip,
    ),
  );
  app.get("/v1/admin/reports/:reportId/export", { preHandler: requireReadyAdmin }, async (request) =>
    moderation.reportExport(request.adminActor!, param(request, "reportId"), request.ip),
  );
  app.post("/v1/admin/investigations/bookmark", { preHandler: requireReadyAdmin }, async (request) => {
    const data = body(request);
    return moderation.setBookmark(request.adminActor!, data.kind, data.targetId, data.bookmarked, request.ip);
  });
  app.post("/v1/admin/investigations/notes", { preHandler: requireReadyAdmin }, async (request, reply) => {
    const data = body(request);
    return reply.status(201).send(
      await moderation.addCaseNote(request.adminActor!, data.targetType, data.targetId, data.body, request.ip),
    );
  });
  app.delete("/v1/admin/investigations/notes/:noteId", { preHandler: requireReadyAdmin }, async (request, reply) => {
    await moderation.deleteCaseNote(request.adminActor!, param(request, "noteId"), request.ip);
    return reply.status(204).send();
  });
  app.post("/v1/admin/investigations/star", { preHandler: requireReadyAdmin }, async (request) => {
    const data = body(request);
    return moderation.setMessageStar(request.adminActor!, data.kind, data.messageId, data.starred, request.ip);
  });

  app.get("/v1/social", { preHandler: authenticateSocial }, async (request) => social.snapshot(request.actor!));
  app.get(
    "/v1/players/search",
    { preHandler: authenticateSocial, config: { rateLimit: { max: 90, timeWindow: "1 minute" } } },
    async (request) => social.searchPlayers(request.actor!, query(request, "q")),
  );
  app.delete("/v1/me", { preHandler: authenticate }, async (request, reply) => {
    await social.deleteAccount(request.actor!);
    return reply.status(204).send();
  });
  app.post(
    "/v1/friend-requests",
    { preHandler: authenticateSocial, config: { rateLimit: { max: 20, timeWindow: "1 hour" } } },
    async (request, reply) => reply.status(201).send(await social.sendRequest(request.actor!, body(request).username)),
  );
  app.post("/v1/friend-requests/:requestId/accept", { preHandler: authenticateSocial }, async (request) =>
    social.acceptRequest(request.actor!, param(request, "requestId")),
  );
  app.delete("/v1/friend-requests/:requestId", { preHandler: authenticateSocial }, async (request, reply) => {
    await social.deleteRequest(request.actor!, param(request, "requestId"));
    return reply.status(204).send();
  });
  app.delete("/v1/friends/:playerUuid", { preHandler: authenticateSocial }, async (request, reply) => {
    await social.removeFriend(request.actor!, param(request, "playerUuid"));
    return reply.status(204).send();
  });
  app.post("/v1/blocks", { preHandler: authenticateSocial }, async (request, reply) => {
    await social.block(request.actor!, body(request).playerUuid);
    return reply.status(204).send();
  });
  app.delete("/v1/blocks/:playerUuid", { preHandler: authenticateSocial }, async (request, reply) => {
    await social.unblock(request.actor!, param(request, "playerUuid"));
    return reply.status(204).send();
  });
  app.get("/v1/messages/:playerUuid", { preHandler: authenticateSocial }, async (request) =>
    social.messages(request.actor!, param(request, "playerUuid"), query(request, "before")),
  );
  app.post(
    "/v1/messages/:playerUuid",
    { preHandler: authenticateSocial, config: { rateLimit: { max: 120, timeWindow: "1 minute" } } },
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
  app.post(
    "/v1/reports",
    { preHandler: authenticateSocial, config: { rateLimit: { max: 10, timeWindow: "1 hour" } } },
    async (request, reply) => {
      const data = body(request);
      return reply.status(201).send(await reports.submit(request.actor!, data.kind, data.messageId, data.reason));
    },
  );
  app.get("/v1/groups", { preHandler: authenticateSocial }, async (request) => social.groups(request.actor!));
  app.post(
    "/v1/groups",
    { preHandler: authenticateSocial, config: { rateLimit: { max: 10, timeWindow: "1 hour" } } },
    async (request, reply) => {
      const data = body(request);
      return reply.status(201).send(await social.createGroup(request.actor!, data.name, data.memberUuids));
    },
  );
  app.patch("/v1/groups/:groupId", { preHandler: authenticateSocial }, async (request) =>
    social.renameGroup(request.actor!, param(request, "groupId"), body(request).name),
  );
  app.post("/v1/groups/:groupId/members", { preHandler: authenticateSocial }, async (request) =>
    social.addGroupMember(request.actor!, param(request, "groupId"), body(request).playerUuid),
  );
  app.delete("/v1/groups/:groupId/members/:playerUuid", { preHandler: authenticateSocial }, async (request, reply) => {
    await social.removeGroupMember(request.actor!, param(request, "groupId"), param(request, "playerUuid"));
    return reply.status(204).send();
  });
  app.get("/v1/group-messages/:groupId", { preHandler: authenticateSocial }, async (request) =>
    social.groupMessages(request.actor!, param(request, "groupId"), query(request, "before")),
  );
  app.post(
    "/v1/group-messages/:groupId",
    { preHandler: authenticateSocial, config: { rateLimit: { max: 120, timeWindow: "1 minute" } } },
    async (request, reply) => {
      const data = body(request);
      return reply.status(201).send(await social.sendGroupMessage(
        request.actor!,
        param(request, "groupId"),
        data.clientMessageId,
        data.body,
      ));
    },
  );

  app.get(
    "/v1/ws",
    { websocket: true, preValidation: authenticateSocial },
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
        void handleSocketMessage(socket, raw, actor, sentAt, social, pool);
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
    void Promise.all([
      auth.cleanExpired(),
      adminAuth.cleanExpired(),
      social.cleanMessages(config.messageRetentionDays),
    ]).catch((error) => {
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
  pool: Pool,
): Promise<void> {
  try {
    const now = Date.now();
    while (sentAt[0] !== undefined && sentAt[0] < now - 10_000) sentAt.shift();
    if (sentAt.length >= 20) throw new ApiError(429, "RATE_LIMITED", "You are sending messages too quickly.");
    sentAt.push(now);
    await requireSocialAccess(pool, actor.playerUuid);

    const parsed = JSON.parse(raw.toString()) as unknown;
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      throw new ApiError(400, "INVALID_EVENT", "A JSON event object is required.");
    }
    const event = parsed as JsonObject;
    if (event.type === "ping") {
      socket.send(JSON.stringify({ type: "pong", data: {} }));
      return;
    }
    if (typeof event.data !== "object" || event.data === null || Array.isArray(event.data)) {
      throw new ApiError(400, "INVALID_EVENT", "Unsupported WebSocket event.");
    }
    const data = event.data as JsonObject;
    if (event.type === "chat.send") {
      await social.sendMessage(actor, data.toPlayerUuid, data.clientMessageId, data.body);
      return;
    }
    if (event.type === "chat.typing") {
      await social.sendTyping(actor, data.toPlayerUuid, data.typing);
      return;
    }
    if (event.type === "presence.set") {
      await social.setPresence(actor, data.status);
      return;
    }
    if (event.type === "group.typing") {
      await social.sendGroupTyping(actor, data.groupId, data.typing);
      return;
    }
    throw new ApiError(400, "INVALID_EVENT", "Unsupported WebSocket event.");
  } catch (error) {
    websocketError(socket, error);
    if (error instanceof ApiError && error.code === "SOCIAL_ACCESS_DISABLED") {
      socket.close(4003, "Friends & Chat access disabled");
    }
  }
}
