export interface Actor {
  playerUuid: string;
  username: string;
}

export interface PublicPlayer {
  playerUuid: string;
  username: string;
  online: boolean;
  lastSeenAt: string | null;
}

export interface ChatMessage {
  messageId: string;
  clientMessageId: string;
  senderUuid: string;
  recipientUuid: string;
  body: string;
  createdAt: string;
  readAt: string | null;
}

declare module "fastify" {
  interface FastifyRequest {
    actor: Actor | null;
  }
}
