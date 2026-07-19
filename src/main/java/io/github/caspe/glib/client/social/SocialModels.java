package io.github.caspe.glib.client.social;

import java.util.List;
import java.util.UUID;

public final class SocialModels {
	private SocialModels() {
	}

	public record Identity(UUID playerUuid, String username) {
	}

	public record Player(UUID playerUuid, String username, boolean online, String lastSeenAt, String friendsSince) {
	}

	public record FriendRequest(UUID requestId, Player player, String createdAt) {
	}

	public record PlayerSearchResult(UUID playerUuid, String username, String relationship, UUID requestId) {
	}

	public record Snapshot(
		Identity me,
		List<Player> friends,
		List<FriendRequest> incoming,
		List<FriendRequest> outgoing,
		List<Player> blocked
	) {
		public Snapshot {
			friends = friends == null ? List.of() : List.copyOf(friends);
			incoming = incoming == null ? List.of() : List.copyOf(incoming);
			outgoing = outgoing == null ? List.of() : List.copyOf(outgoing);
			blocked = blocked == null ? List.of() : List.copyOf(blocked);
		}
	}

	public record ChatMessage(
		String messageId,
		UUID clientMessageId,
		UUID senderUuid,
		UUID recipientUuid,
		String body,
		String createdAt,
		String readAt
	) {
	}

	public record NetworkEvent(String type, com.google.gson.JsonObject data) {
	}
}
