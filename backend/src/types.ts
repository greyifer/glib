export interface Actor {
  playerUuid: string;
  username: string;
}

export interface AdminActor extends Actor {
  role: "MODERATOR" | "OWNER";
  mustChangePassword: boolean;
}

export interface PublicPlayer {
  playerUuid: string;
  username: string;
  online: boolean;
  status: "ONLINE" | "IDLE" | "DND" | "OFFLINE";
  lastSeenAt: string | null;
}

export interface ChatMessage {
  messageId: string;
  clientMessageId: string;
  senderUuid: string;
  recipientUuid: string;
  senderUsernameAtSend: string;
  body: string;
  createdAt: string;
  readAt: string | null;
  state: "SENT" | "EDITED" | "DELETED";
  editedAt: string | null;
  deletedAt: string | null;
  replyToMessageId: string | null;
  attachmentKind: string | null;
  attachmentLabel: string | null;
}

export interface GroupSummary {
  groupId: string;
  name: string;
  ownerUuid: string;
  members: PublicPlayer[];
  createdAt: string;
}

export interface GroupChatMessage {
  messageId: string;
  clientMessageId: string;
  groupId: string;
  senderUuid: string;
  senderUsernameAtSend: string;
  body: string;
  createdAt: string;
  state: "SENT" | "EDITED" | "DELETED";
  editedAt: string | null;
  deletedAt: string | null;
  replyToMessageId: string | null;
  attachmentKind: string | null;
  attachmentLabel: string | null;
}

declare module "fastify" {
  interface FastifyRequest {
    actor: Actor | null;
    adminActor: AdminActor | null;
    roomSyncAuthenticated: boolean;
  }
}
