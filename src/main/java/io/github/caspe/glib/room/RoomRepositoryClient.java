package io.github.caspe.glib.room;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.caspe.glib.api.room.GsaveCodec;
import io.github.caspe.glib.api.room.RoomId;
import io.github.caspe.glib.api.room.VirtualRoom;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * Dedicated-server client for the remote G'Lib room repository.
 *
 * <p>Every operation is asynchronous. Callers may compose the returned futures
 * and must only apply their results to Minecraft from the server thread.</p>
 */
final class RoomRepositoryClient {
	static final int MAX_ARCHIVE_BYTES = 8 * 1024 * 1024;
	static final String GSAVE_MEDIA_TYPE = "application/vnd.glib.gsave";
	static final String TOKEN_HEADER = "X-Glib-Room-Sync-Token";

	private static final String URL_PROPERTY = "glib.rooms.repositoryUrl";
	private static final String URL_ENVIRONMENT = "GLIB_ROOM_REPOSITORY_URL";
	private static final String TOKEN_PROPERTY = "glib.rooms.repositoryToken";
	private static final String TOKEN_ENVIRONMENT = "GLIB_ROOM_REPOSITORY_TOKEN";
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
	private static final int PAGE_SIZE = 100;
	private static final int MAX_REPOSITORY_ENTRIES = 16_384;
	private static final int MAX_JSON_BYTES = 1024 * 1024;
	private static final int MAX_ERROR_BYTES = 64 * 1024;
	private static final Pattern SHA_256_PATTERN = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern ETAG_PATTERN = Pattern.compile("\"[1-9][0-9]{0,18}\"");

	private final URI baseUri;
	private final String syncToken;
	private final HttpClient http;

	RoomRepositoryClient(URI baseUri, String syncToken) {
		this(baseUri, syncToken, HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build());
	}

	RoomRepositoryClient(URI baseUri, String syncToken, HttpClient http) {
		this.baseUri = validateBaseUri(baseUri);
		this.syncToken = validateToken(syncToken);
		this.http = Objects.requireNonNull(http, "http");
	}

	/**
	 * Reads the system-property configuration first and then the equivalent
	 * environment variables. An absent URL means repository syncing is disabled.
	 */
	static Optional<RoomRepositoryClient> configured() {
		String rawUrl = configuredValue(URL_PROPERTY, URL_ENVIRONMENT);
		if (rawUrl == null) {
			return Optional.empty();
		}
		String token = configuredValue(TOKEN_PROPERTY, TOKEN_ENVIRONMENT);
		if (token == null) {
			throw new IllegalStateException(
				"A room repository URL is configured, but no room repository token is configured");
		}
		try {
			return Optional.of(new RoomRepositoryClient(URI.create(rawUrl), token));
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("Invalid G'Lib room repository configuration: " + exception.getMessage(),
				exception);
		}
	}

	CompletableFuture<RoomPage> listPage(String cursor) {
		String query = "?limit=" + PAGE_SIZE;
		if (cursor != null) {
			if (cursor.isBlank() || cursor.length() > 256) {
				return CompletableFuture.failedFuture(
					new IllegalArgumentException("Room repository cursor is invalid"));
			}
			query += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
		}
		HttpRequest request = request("/v1/rooms" + query).GET().build();
		return send(request).thenApplyAsync(response -> {
			byte[] body = requireResponseBody(response, 200, MAX_JSON_BYTES, "room-library response");
			return parsePage(body);
		});
	}

	CompletableFuture<List<RoomMetadata>> listAll() {
		return collectPages(
			null, new ArrayList<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
	}

	CompletableFuture<DownloadedRoom> download(RoomMetadata metadata) {
		Objects.requireNonNull(metadata, "metadata");
		HttpRequest request = request("/v1/rooms/" + metadata.repositoryId()).GET().build();
		return send(request).thenApplyAsync(response -> {
			byte[] archive = requireResponseBody(response, 200, MAX_ARCHIVE_BYTES, ".gsave archive");
			requireMediaType(response, GSAVE_MEDIA_TYPE);
			requireHeaderEquals(response, "ETag", metadata.etag(), "room ETag");
			requireHeaderEquals(
				response,
				"Content-Disposition",
				"attachment; filename=\"" + metadata.archiveId().value() + ".gsave\"",
				"room filename");

			String actualSha256 = sha256(archive);
			if (!MessageDigest.isEqual(
					actualSha256.getBytes(StandardCharsets.US_ASCII),
					metadata.sha256().getBytes(StandardCharsets.US_ASCII))) {
				throw invalidResponse("Downloaded room checksum does not match its repository metadata");
			}
			requireHeaderEquals(response, "X-Content-SHA256", actualSha256, "room checksum");
			if (archive.length != metadata.sizeBytes()) {
				throw invalidResponse("Downloaded room size does not match its repository metadata");
			}

			VirtualRoom room;
			try {
				room = GsaveCodec.decode(archive);
			} catch (IOException exception) {
				throw new RepositoryException(
					0, "INVALID_GSAVE", "The repository returned an invalid .gsave archive", exception);
			}
			if (!room.id().equals(metadata.archiveId())) {
				throw new RepositoryException(
					0,
					"ARCHIVE_ID_MISMATCH",
					"The downloaded archive contains room ID '" + room.id()
						+ "', but the repository metadata identifies it as '" + metadata.archiveId() + "'");
			}
			return new DownloadedRoom(metadata, room, archive);
		});
	}

	CompletableFuture<RoomMetadata> create(VirtualRoom room) {
		EncodedRoom encoded = encode(room);
		return create(encoded);
	}

	CompletableFuture<RoomMetadata> update(RoomMetadata existing, VirtualRoom room) {
		Objects.requireNonNull(existing, "existing");
		EncodedRoom encoded = encode(room);
		if (!existing.archiveId().equals(encoded.room().id())) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(
				"Cannot update repository archive '" + existing.archiveId()
					+ "' with room '" + encoded.room().id() + "'"));
		}
		return update(existing, encoded);
	}

	/**
	 * Creates the room when its archive ID is absent, otherwise replaces the
	 * matching repository item using its current ETag.
	 */
	CompletableFuture<UpsertResult> upsert(VirtualRoom room) {
		EncodedRoom encoded = encode(room);
		return listAll().thenCompose(entries -> upsert(encoded, entries));
	}

	CompletableFuture<UpsertResult> upsert(VirtualRoom room, List<RoomMetadata> repositoryEntries) {
		return upsert(encode(room), repositoryEntries);
	}

	private CompletableFuture<UpsertResult> upsert(
			EncodedRoom encoded,
			List<RoomMetadata> repositoryEntries) {
		Objects.requireNonNull(repositoryEntries, "repositoryEntries");
		List<RoomMetadata> matches = repositoryEntries.stream()
			.filter(entry -> entry.archiveId().equals(encoded.room().id()))
			.toList();
		if (matches.size() > 1) {
			return CompletableFuture.failedFuture(invalidResponse(
				"Repository returned duplicate metadata for archive ID '" + encoded.room().id() + "'"));
		}
		if (matches.isEmpty()) {
			return create(encoded).thenApply(metadata -> new UpsertResult(metadata, UpsertOperation.CREATED));
		}
		return update(matches.getFirst(), encoded)
			.thenApply(metadata -> new UpsertResult(metadata, UpsertOperation.UPDATED));
	}

	private CompletableFuture<RoomMetadata> create(EncodedRoom encoded) {
		HttpRequest request = uploadRequest("/v1/rooms", encoded)
			.POST(HttpRequest.BodyPublishers.ofByteArray(encoded.archive()))
			.build();
		return send(request).thenApplyAsync(response ->
			parseUploadResponse(response, 201, encoded));
	}

	private CompletableFuture<RoomMetadata> update(RoomMetadata existing, EncodedRoom encoded) {
		HttpRequest request = uploadRequest("/v1/rooms/" + existing.repositoryId(), encoded)
			.header("If-Match", existing.etag())
			.PUT(HttpRequest.BodyPublishers.ofByteArray(encoded.archive()))
			.build();
		return send(request).thenApplyAsync(response ->
			parseUploadResponse(response, 200, encoded));
	}

	private RoomMetadata parseUploadResponse(
			HttpResponse<InputStream> response,
			int expectedStatus,
			EncodedRoom encoded) {
		byte[] body = requireResponseBody(response, expectedStatus, MAX_JSON_BYTES, "room metadata");
		RoomMetadata metadata = parseMetadata(parseObject(body, "room metadata"));
		if (!metadata.archiveId().equals(encoded.room().id())) {
			throw invalidResponse("Repository response changed the uploaded archive ID");
		}
		if (!metadata.sha256().equals(encoded.sha256()) || metadata.sizeBytes() != encoded.archive().length) {
			throw invalidResponse("Repository response does not describe the uploaded .gsave bytes");
		}
		requireHeaderEquals(response, "ETag", metadata.etag(), "room ETag");
		requireHeaderEquals(response, "X-Content-SHA256", encoded.sha256(), "room checksum");
		return metadata;
	}

	private CompletableFuture<List<RoomMetadata>> collectPages(
			String cursor,
			ArrayList<RoomMetadata> entries,
			Set<UUID> repositoryIds,
			Set<RoomId> archiveIds,
			Set<String> cursors) {
		return listPage(cursor).thenCompose(page -> {
			for (RoomMetadata entry : page.rooms()) {
				if (!repositoryIds.add(entry.repositoryId())) {
					return CompletableFuture.failedFuture(
						invalidResponse("Repository listed the same room more than once"));
				}
				if (!archiveIds.add(entry.archiveId())) {
					return CompletableFuture.failedFuture(
						invalidResponse("Repository listed the same archive ID more than once"));
				}
				entries.add(entry);
				if (entries.size() > MAX_REPOSITORY_ENTRIES) {
					return CompletableFuture.failedFuture(
						invalidResponse("Repository contains more than "
							+ MAX_REPOSITORY_ENTRIES + " rooms"));
				}
			}
			if (page.nextCursor() == null) {
				return CompletableFuture.completedFuture(List.copyOf(entries));
			}
			if (!cursors.add(page.nextCursor())) {
				return CompletableFuture.failedFuture(
					invalidResponse("Repository pagination repeated a cursor"));
			}
			return collectPages(page.nextCursor(), entries, repositoryIds, archiveIds, cursors);
		});
	}

	private HttpRequest.Builder uploadRequest(String path, EncodedRoom encoded) {
		return request(path)
			.header("Content-Type", GSAVE_MEDIA_TYPE)
			.header("X-Glib-Room-Id", encoded.room().id().value())
			.header("X-Glib-Room-Name", encodeHeaderValue(encoded.repositoryName()));
	}

	private HttpRequest.Builder request(String pathAndQuery) {
		if (!pathAndQuery.startsWith("/")) {
			throw new IllegalArgumentException("Repository path must start with '/'");
		}
		return HttpRequest.newBuilder(endpoint(pathAndQuery))
			.timeout(REQUEST_TIMEOUT)
			.header(TOKEN_HEADER, syncToken)
			.header("Accept", "application/json, " + GSAVE_MEDIA_TYPE);
	}

	private CompletableFuture<HttpResponse<InputStream>> send(HttpRequest request) {
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
			.handle((response, error) -> {
				if (error == null) {
					return response;
				}
				Throwable cause = unwrap(error);
				throw new RepositoryException(
					0, "NETWORK_ERROR", "Could not contact the G'Lib room repository", cause);
			});
	}

	private URI endpoint(String pathAndQuery) {
		return URI.create(baseUri.toASCIIString() + pathAndQuery);
	}

	private static RoomPage parsePage(byte[] body) {
		JsonObject object = parseObject(body, "room-library response");
		JsonElement roomsElement = object.get("rooms");
		if (roomsElement == null || !roomsElement.isJsonArray()) {
			throw invalidResponse("Room-library response is missing its rooms array");
		}
		JsonArray array = roomsElement.getAsJsonArray();
		if (array.size() > PAGE_SIZE) {
			throw invalidResponse("Room-library page exceeds the requested page size");
		}
		ArrayList<RoomMetadata> rooms = new ArrayList<>(array.size());
		Set<UUID> repositoryIds = new HashSet<>();
		Set<RoomId> archiveIds = new HashSet<>();
		for (JsonElement element : array) {
			if (!element.isJsonObject()) {
				throw invalidResponse("Room-library entry is not an object");
			}
			RoomMetadata metadata = parseMetadata(element.getAsJsonObject());
			if (!repositoryIds.add(metadata.repositoryId()) || !archiveIds.add(metadata.archiveId())) {
				throw invalidResponse("Room-library page contains duplicate room metadata");
			}
			rooms.add(metadata);
		}

		JsonElement cursorElement = object.get("nextCursor");
		String nextCursor;
		if (cursorElement == null || cursorElement.isJsonNull()) {
			nextCursor = null;
		} else if (cursorElement.isJsonPrimitive() && cursorElement.getAsJsonPrimitive().isString()) {
			nextCursor = cursorElement.getAsString();
			if (nextCursor.isBlank() || nextCursor.length() > 256) {
				throw invalidResponse("Room-library response contains an invalid cursor");
			}
		} else {
			throw invalidResponse("Room-library response contains an invalid cursor");
		}
		return new RoomPage(rooms, nextCursor);
	}

	private static RoomMetadata parseMetadata(JsonObject object) {
		try {
			UUID repositoryId = UUID.fromString(requiredString(object, "roomId", 64));
			RoomId archiveId = RoomId.of(requiredString(object, "archiveId", 64));
			String name = requiredString(object, "name", 80);
			JsonElement ownerElement = object.get("owner");
			if (ownerElement == null || !ownerElement.isJsonObject()) {
				throw invalidResponse("Room metadata is missing its owner");
			}
			JsonObject ownerObject = ownerElement.getAsJsonObject();
			Owner owner = new Owner(
				UUID.fromString(requiredString(ownerObject, "playerUuid", 64)),
				requiredString(ownerObject, "username", 128));
			long revision = parsePositiveLong(requiredString(object, "revision", 19), "revision");
			String etag = requiredString(object, "etag", 24);
			String sha256 = requiredString(object, "sha256", 64);
			int sizeBytes = requiredInt(object, "sizeBytes");
			Instant createdAt = Instant.parse(requiredString(object, "createdAt", 64));
			Instant updatedAt = Instant.parse(requiredString(object, "updatedAt", 64));
			return new RoomMetadata(
				repositoryId,
				archiveId,
				name,
				owner,
				revision,
				etag,
				sha256,
				sizeBytes,
				createdAt,
				updatedAt);
		} catch (IllegalArgumentException | DateTimeParseException exception) {
			throw new RepositoryException(
				0, "INVALID_RESPONSE", "Repository returned invalid room metadata", exception);
		}
	}

	private static JsonObject parseObject(byte[] body, String label) {
		try {
			JsonElement element = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
			if (!element.isJsonObject()) {
				throw invalidResponse("Repository " + label + " is not a JSON object");
			}
			return element.getAsJsonObject();
		} catch (JsonParseException exception) {
			throw new RepositoryException(
				0, "INVALID_RESPONSE", "Repository returned malformed " + label, exception);
		}
	}

	private static String requiredString(JsonObject object, String property, int maximumLength) {
		JsonElement element = object.get(property);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw invalidResponse("Room metadata is missing '" + property + "'");
		}
		String value = element.getAsString();
		if (value.isBlank() || value.length() > maximumLength) {
			throw invalidResponse("Room metadata contains invalid '" + property + "'");
		}
		return value;
	}

	private static int requiredInt(JsonObject object, String property) {
		JsonElement element = object.get(property);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw invalidResponse("Room metadata is missing '" + property + "'");
		}
		try {
			return element.getAsInt();
		} catch (NumberFormatException exception) {
			throw invalidResponse("Room metadata contains invalid '" + property + "'");
		}
	}

	private static long parsePositiveLong(String value, String label) {
		try {
			long parsed = Long.parseLong(value);
			if (parsed < 1) {
				throw new NumberFormatException();
			}
			return parsed;
		} catch (NumberFormatException exception) {
			throw invalidResponse("Room metadata contains invalid " + label);
		}
	}

	private static byte[] requireResponseBody(
			HttpResponse<InputStream> response,
			int expectedStatus,
			int maximumBytes,
			String label) {
		if (response.statusCode() != expectedStatus) {
			byte[] errorBody = readBody(response, MAX_ERROR_BYTES, "error response");
			throw httpError(response.statusCode(), errorBody);
		}
		long declaredLength;
		try {
			declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
		} catch (NumberFormatException exception) {
			closeQuietly(response.body());
			throw invalidResponse("Repository response has an invalid Content-Length");
		}
		if (declaredLength > maximumBytes) {
			closeQuietly(response.body());
			throw new RepositoryException(
				0,
				"RESPONSE_TOO_LARGE",
				"Repository " + label + " exceeds " + maximumBytes + " bytes");
		}
		return readBody(response, maximumBytes, label);
	}

	private static byte[] readBody(HttpResponse<InputStream> response, int maximumBytes, String label) {
		try (InputStream input = response.body();
				ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192))) {
			byte[] buffer = new byte[8192];
			int total = 0;
			int count;
			while ((count = input.read(buffer)) != -1) {
				if (total > maximumBytes - count) {
					throw new RepositoryException(
						0,
						"RESPONSE_TOO_LARGE",
						"Repository " + label + " exceeds " + maximumBytes + " bytes");
				}
				output.write(buffer, 0, count);
				total += count;
			}
			return output.toByteArray();
		} catch (IOException exception) {
			throw new RepositoryException(
				0, "RESPONSE_READ_FAILED", "Could not read the repository " + label, exception);
		}
	}

	private static void requireMediaType(HttpResponse<?> response, String expected) {
		String contentType = response.headers().firstValue("Content-Type")
			.orElseThrow(() -> invalidResponse("Repository response has no Content-Type"));
		String mediaType = contentType.split(";", 2)[0].trim();
		if (!mediaType.equalsIgnoreCase(expected)) {
			throw invalidResponse("Repository returned unexpected Content-Type '" + mediaType + "'");
		}
	}

	private static void requireHeaderEquals(
			HttpResponse<?> response,
			String header,
			String expected,
			String label) {
		String actual = response.headers().firstValue(header)
			.orElseThrow(() -> invalidResponse("Repository response has no " + label));
		if (!MessageDigest.isEqual(
				actual.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8))) {
			throw invalidResponse("Repository response contains an unexpected " + label);
		}
	}

	private static RepositoryException httpError(int status, byte[] body) {
		String code = "HTTP_" + status;
		String message = "Room repository request failed with HTTP " + status;
		try {
			JsonObject object = parseObject(body, "error response");
			JsonElement errorElement = object.get("error");
			if (errorElement != null && errorElement.isJsonObject()) {
				JsonObject error = errorElement.getAsJsonObject();
				JsonElement codeElement = error.get("code");
				JsonElement messageElement = error.get("message");
				if (codeElement != null && codeElement.isJsonPrimitive()
						&& codeElement.getAsJsonPrimitive().isString()) {
					String candidate = codeElement.getAsString();
					if (!candidate.isBlank() && candidate.length() <= 64) {
						code = candidate;
					}
				}
				if (messageElement != null && messageElement.isJsonPrimitive()
						&& messageElement.getAsJsonPrimitive().isString()) {
					String candidate = messageElement.getAsString();
					if (!candidate.isBlank() && candidate.length() <= 512) {
						message = candidate;
					}
				}
			}
		} catch (RuntimeException ignored) {
			// The status is still useful when an upstream proxy returned non-JSON.
		}
		return new RepositoryException(status, code, message);
	}

	private static EncodedRoom encode(VirtualRoom room) {
		Objects.requireNonNull(room, "room");
		String repositoryName = validateRepositoryName(room.displayName());
		try {
			byte[] archive = GsaveCodec.encode(room);
			if (archive.length == 0 || archive.length > MAX_ARCHIVE_BYTES) {
				throw new RepositoryException(
					0,
					"GSAVE_TOO_LARGE",
					"A repository .gsave archive cannot exceed " + MAX_ARCHIVE_BYTES + " bytes");
			}
			return new EncodedRoom(room, repositoryName, archive, sha256(archive));
		} catch (IOException exception) {
			throw new RepositoryException(
				0, "GSAVE_ENCODE_FAILED", "Could not encode room '" + room.id() + "'", exception);
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static URI validateBaseUri(URI input) {
		Objects.requireNonNull(input, "baseUri");
		if (!input.isAbsolute() || input.isOpaque() || input.getHost() == null) {
			throw new IllegalArgumentException("Room repository URL must be an absolute HTTP(S) URL");
		}
		if (input.getRawUserInfo() != null || input.getRawQuery() != null || input.getRawFragment() != null) {
			throw new IllegalArgumentException("Room repository URL cannot contain credentials, a query, or a fragment");
		}
		String scheme = input.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !(scheme.equals("http") && isLocalhost(input.getHost()))) {
			throw new IllegalArgumentException("Room repository URL must use HTTPS except on localhost");
		}
		String normalized = input.normalize().toASCIIString();
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return URI.create(normalized);
	}

	private static boolean isLocalhost(String host) {
		String normalized = host.toLowerCase(Locale.ROOT);
		if (normalized.matches("127(?:\\.[0-9]{1,3}){3}")) {
			String[] parts = normalized.split("\\.");
			for (String part : parts) {
				if (Integer.parseInt(part) > 255) {
					return false;
				}
			}
			return true;
		}
		return normalized.equals("localhost")
			|| normalized.endsWith(".localhost")
			|| normalized.equals("::1")
			|| normalized.equals("0:0:0:0:0:0:0:1");
	}

	private static String validateRepositoryName(String name) {
		if (name.isBlank() || name.length() > 80) {
			throw new RepositoryException(
				0, "INVALID_ROOM_NAME", "Repository room names must contain 1-80 visible characters");
		}
		for (int index = 0; index < name.length(); index++) {
			char character = name.charAt(index);
			if (character < 0x20 || character == 0x7f) {
				throw new RepositoryException(
					0, "INVALID_ROOM_NAME", "Repository room names cannot contain control characters");
			}
		}
		return name.trim();
	}

	private static String validateToken(String token) {
		Objects.requireNonNull(token, "syncToken");
		if (token.length() < 32 || token.length() > 256) {
			throw new IllegalArgumentException("Room repository token is invalid");
		}
		for (int index = 0; index < token.length(); index++) {
			char character = token.charAt(index);
			if (character < 0x21 || character > 0x7e) {
				throw new IllegalArgumentException("Room repository token is invalid");
			}
		}
		return token;
	}

	private static String encodeHeaderValue(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String configuredValue(String property, String environment) {
		String value = System.getProperty(property);
		if (value == null || value.isBlank()) {
			value = System.getenv(environment);
		}
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}

	private static RepositoryException invalidResponse(String message) {
		return new RepositoryException(0, "INVALID_RESPONSE", message);
	}

	private static Throwable unwrap(Throwable error) {
		Throwable current = error;
		while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
				&& current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private static void closeQuietly(InputStream input) {
		try {
			input.close();
		} catch (IOException ignored) {
		}
	}

	record Owner(UUID playerUuid, String username) {
		Owner {
			Objects.requireNonNull(playerUuid, "playerUuid");
			Objects.requireNonNull(username, "username");
			if (username.isBlank() || username.length() > 128) {
				throw new IllegalArgumentException("Repository owner username is invalid");
			}
		}
	}

	record RoomMetadata(
			UUID repositoryId,
			RoomId archiveId,
			String name,
			Owner owner,
			long revision,
			String etag,
			String sha256,
			int sizeBytes,
			Instant createdAt,
			Instant updatedAt) {
		RoomMetadata {
			Objects.requireNonNull(repositoryId, "repositoryId");
			Objects.requireNonNull(archiveId, "archiveId");
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(owner, "owner");
			Objects.requireNonNull(etag, "etag");
			Objects.requireNonNull(sha256, "sha256");
			Objects.requireNonNull(createdAt, "createdAt");
			Objects.requireNonNull(updatedAt, "updatedAt");
			if (name.isBlank() || name.length() > 80) {
				throw new IllegalArgumentException("Repository room name is invalid");
			}
			if (revision < 1 || !ETAG_PATTERN.matcher(etag).matches()
					|| !etag.equals("\"" + revision + "\"")) {
				throw new IllegalArgumentException("Repository room revision or ETag is invalid");
			}
			if (!SHA_256_PATTERN.matcher(sha256).matches()) {
				throw new IllegalArgumentException("Repository room checksum is invalid");
			}
			if (sizeBytes < 1 || sizeBytes > MAX_ARCHIVE_BYTES) {
				throw new IllegalArgumentException("Repository room size is invalid");
			}
			if (updatedAt.isBefore(createdAt)) {
				throw new IllegalArgumentException("Repository room timestamps are invalid");
			}
		}
	}

	record RoomPage(List<RoomMetadata> rooms, String nextCursor) {
		RoomPage {
			rooms = List.copyOf(rooms);
		}
	}

	record DownloadedRoom(RoomMetadata metadata, VirtualRoom room, byte[] archive) {
		DownloadedRoom {
			Objects.requireNonNull(metadata, "metadata");
			Objects.requireNonNull(room, "room");
			archive = archive.clone();
		}

		@Override
		public byte[] archive() {
			return archive.clone();
		}
	}

	enum UpsertOperation {
		CREATED,
		UPDATED
	}

	record UpsertResult(RoomMetadata metadata, UpsertOperation operation) {
		UpsertResult {
			Objects.requireNonNull(metadata, "metadata");
			Objects.requireNonNull(operation, "operation");
		}
	}

	static final class RepositoryException extends RuntimeException {
		private final int statusCode;
		private final String code;

		RepositoryException(int statusCode, String code, String message) {
			super(message);
			this.statusCode = statusCode;
			this.code = Objects.requireNonNull(code, "code");
		}

		RepositoryException(int statusCode, String code, String message, Throwable cause) {
			super(message, cause);
			this.statusCode = statusCode;
			this.code = Objects.requireNonNull(code, "code");
		}

		int statusCode() {
			return statusCode;
		}

		String code() {
			return code;
		}
	}

	private record EncodedRoom(
			VirtualRoom room,
			String repositoryName,
			byte[] archive,
			String sha256) {
	}
}
