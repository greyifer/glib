package io.github.caspe.glib.client.social;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.github.caspe.glib.client.social.SocialModels.*;

@Environment(EnvType.CLIENT)
public final class GlibSocialClient implements AutoCloseable {
	public static final GlibSocialClient INSTANCE = new GlibSocialClient();
	private static final Gson GSON = new Gson();

	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "glib-network");
		thread.setDaemon(true);
		return thread;
	});
	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(8))
		.executor(executor)
		.build();
	private final CopyOnWriteArrayList<Consumer<NetworkEvent>> listeners = new CopyOnWriteArrayList<>();
	private final AtomicReference<CompletableFuture<String>> authenticating = new AtomicReference<>();
	private volatile String token;
	private volatile WebSocket socket;

	private GlibSocialClient() {
	}

	public CompletableFuture<Snapshot> snapshot() {
		return authenticatedRequest("GET", "/v1/social", null, Snapshot.class);
	}

	public CompletableFuture<List<PlayerSearchResult>> searchPlayers(String query) {
		String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
		return authenticatedRequest("GET", "/v1/players/search?q=" + encoded, null, PlayerSearchResult[].class)
			.thenApply(array -> List.of(array));
	}

	public CompletableFuture<Void> sendFriendRequest(String username) {
		JsonObject data = new JsonObject();
		data.addProperty("username", username);
		return authenticatedRequest("POST", "/v1/friend-requests", data, JsonObject.class).thenApply(ignored -> null);
	}

	public CompletableFuture<Void> acceptRequest(UUID requestId) {
		return authenticatedRequest("POST", "/v1/friend-requests/" + requestId + "/accept", new JsonObject(), JsonObject.class).thenApply(ignored -> null);
	}

	public CompletableFuture<Void> deleteRequest(UUID requestId) {
		return authenticatedRequest("DELETE", "/v1/friend-requests/" + requestId, null, Void.class);
	}

	public CompletableFuture<Void> removeFriend(UUID playerUuid) {
		return authenticatedRequest("DELETE", "/v1/friends/" + playerUuid, null, Void.class);
	}

	public CompletableFuture<Void> block(UUID playerUuid) {
		JsonObject data = new JsonObject();
		data.addProperty("playerUuid", playerUuid.toString());
		return authenticatedRequest("POST", "/v1/blocks", data, Void.class);
	}

	public CompletableFuture<Void> unblock(UUID playerUuid) {
		return authenticatedRequest("DELETE", "/v1/blocks/" + playerUuid, null, Void.class);
	}

	public CompletableFuture<Void> deleteAccount() {
		return authenticatedRequest("DELETE", "/v1/me", null, Void.class);
	}

	public CompletableFuture<List<ChatMessage>> messages(UUID playerUuid) {
		return authenticatedRequest("GET", "/v1/messages/" + playerUuid, null, ChatMessage[].class)
			.thenApply(array -> List.of(array));
	}

	public CompletableFuture<ChatMessage> sendMessage(UUID playerUuid, String message) {
		JsonObject data = new JsonObject();
		data.addProperty("clientMessageId", UUID.randomUUID().toString());
		data.addProperty("body", message);
		return authenticatedRequest("POST", "/v1/messages/" + playerUuid, data, ChatMessage.class);
	}

	public AutoCloseable listen(Consumer<NetworkEvent> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	public synchronized void reset() {
		token = null;
		authenticating.set(null);
		if (socket != null) socket.abort();
		socket = null;
	}

	@Override
	public void close() {
		reset();
		executor.shutdownNow();
	}

	private <T> CompletableFuture<T> authenticatedRequest(String method, String path, JsonElement data, Class<T> responseType) {
		return ensureAuthenticated().thenCompose(sessionToken -> request(method, path, data, responseType, sessionToken))
			.handle((value, error) -> {
				Throwable cause = unwrap(error);
				if (cause instanceof GlibNetworkException exception && exception.code().equals("UNAUTHENTICATED")) {
					reset();
					return ensureAuthenticated().thenCompose(sessionToken -> request(method, path, data, responseType, sessionToken));
				}
				if (cause != null) return CompletableFuture.<T>failedFuture(cause);
				return CompletableFuture.completedFuture(value);
			}).thenCompose(future -> future);
	}

	private synchronized CompletableFuture<String> ensureAuthenticated() {
		if (!GlibNetworkConfig.configured()) {
			return CompletableFuture.failedFuture(new GlibNetworkException("NOT_CONFIGURED", "G'Network is not configured in this build."));
		}
		String existingToken = token;
		if (existingToken != null) {
			if (socket == null) connectWebSocket(existingToken);
			return CompletableFuture.completedFuture(existingToken);
		}

		CompletableFuture<String> existing = authenticating.get();
		if (existing != null) return existing;
		CompletableFuture<String> created = authenticate();
		authenticating.set(created);
		created.whenComplete((ignored, error) -> authenticating.compareAndSet(created, null));
		return created;
	}

	private CompletableFuture<String> authenticate() {
		MinecraftClient client = MinecraftClient.getInstance();
		GameProfile profile = client.getGameProfile();
		String accessToken = client.getSession().getAccessToken();
		JsonObject body = new JsonObject();
		body.addProperty("username", profile.getName());

		return request("POST", "/v1/auth/challenge", body, AuthChallenge.class, null)
			.thenCompose(challenge -> CompletableFuture.supplyAsync(() -> {
				try {
					client.getSessionService().joinServer(profile.getId(), accessToken, challenge.serverId());
					return challenge;
				} catch (AuthenticationException exception) {
					throw new CompletionException(new GlibNetworkException("MINECRAFT_AUTH_FAILED", "Minecraft could not verify this account session.", exception));
				}
			}, executor))
			.thenCompose(challenge -> {
				JsonObject complete = new JsonObject();
				complete.addProperty("challengeId", challenge.challengeId());
				return request("POST", "/v1/auth/complete", complete, AuthResult.class, null);
			})
			.thenApply(result -> {
				token = result.token();
				connectWebSocket(result.token());
				return result.token();
			});
	}

	private <T> CompletableFuture<T> request(String method, String path, JsonElement data, Class<T> responseType, String sessionToken) {
		URI uri = GlibNetworkConfig.get().apiUri().resolve(path);
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
			.timeout(Duration.ofSeconds(12))
			.header("accept", "application/json");
		if (sessionToken != null) builder.header("authorization", "Bearer " + sessionToken);
		if (data == null) {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		} else {
			builder.header("content-type", "application/json");
			builder.method(method, HttpRequest.BodyPublishers.ofString(GSON.toJson(data), StandardCharsets.UTF_8));
		}
		return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
			.handle((response, error) -> {
				if (error != null) throw networkFailure(error);
				if (response.statusCode() < 200 || response.statusCode() >= 300) throw responseFailure(response);
				if (responseType == Void.class || response.body().isBlank()) return null;
				try {
					return GSON.fromJson(response.body(), responseType);
				} catch (JsonParseException exception) {
					throw new GlibNetworkException("INVALID_RESPONSE", "The G'Lib server returned an invalid response.", exception);
				}
			});
	}

	private void connectWebSocket(String sessionToken) {
		if (socket != null) socket.abort();
		URI httpUri = GlibNetworkConfig.get().apiUri();
		String scheme = httpUri.getScheme().equalsIgnoreCase("https") ? "wss" : "ws";
		URI websocketUri = URI.create(scheme + "://" + httpUri.getAuthority() + "/v1/ws");
		http.newWebSocketBuilder()
			.header("authorization", "Bearer " + sessionToken)
			.connectTimeout(Duration.ofSeconds(8))
			.buildAsync(websocketUri, new SocketListener())
			.whenComplete((connected, error) -> {
				if (error == null) socket = connected;
			});
	}

	private GlibNetworkException responseFailure(HttpResponse<String> response) {
		try {
			JsonObject root = GSON.fromJson(response.body(), JsonObject.class);
			JsonObject error = root == null ? null : root.getAsJsonObject("error");
			if (error != null) {
				return new GlibNetworkException(error.get("code").getAsString(), error.get("message").getAsString());
			}
		} catch (RuntimeException ignored) {
		}
		return new GlibNetworkException("HTTP_" + response.statusCode(), "The G'Lib server rejected the request (HTTP " + response.statusCode() + ").");
	}

	private GlibNetworkException networkFailure(Throwable error) {
		Throwable cause = unwrap(error);
		String message = cause instanceof ConnectException
			? "Could not connect to the G'Lib server."
			: "The G'Lib server request failed.";
		return new GlibNetworkException("NETWORK_ERROR", message, cause);
	}

	private static Throwable unwrap(Throwable error) {
		if (error == null) return null;
		while ((error instanceof CompletionException || error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) {
			error = error.getCause();
		}
		return error;
	}

	private void fireEvent(NetworkEvent event) {
		MinecraftClient.getInstance().execute(() -> {
			for (Consumer<NetworkEvent> listener : listeners) listener.accept(event);
		});
	}

	private final class SocketListener implements WebSocket.Listener {
		private final StringBuilder text = new StringBuilder();

		@Override
		public void onOpen(WebSocket webSocket) {
			webSocket.request(1);
		}

		@Override
		public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			text.append(data);
			if (last) {
				try {
					JsonObject root = GSON.fromJson(text.toString(), JsonObject.class);
					String type = root.get("type").getAsString();
					JsonObject eventData = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : new JsonObject();
					fireEvent(new NetworkEvent(type, eventData));
				} catch (RuntimeException ignored) {
				} finally {
					text.setLength(0);
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			if (socket == webSocket) socket = null;
			fireEvent(new NetworkEvent("connection.closed", new JsonObject()));
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			if (socket == webSocket) socket = null;
		}
	}

	private record AuthChallenge(String challengeId, String serverId, String expiresAt) {
	}

	private record AuthResult(String token, String expiresAt, Identity player) {
	}
}
