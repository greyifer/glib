package io.github.caspe.glib.room;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.caspe.glib.api.room.GsaveCodec;
import io.github.caspe.glib.api.room.RoomDimensions;
import io.github.caspe.glib.api.room.RoomId;
import io.github.caspe.glib.api.room.VirtualRoom;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RoomRepositoryClientTest {
	private static final String TOKEN = "dedicated-server-secret-0000000000";
	private static final UUID FIRST_REPOSITORY_ID =
		UUID.fromString("10000000-0000-4000-8000-000000000001");
	private static final UUID SECOND_REPOSITORY_ID =
		UUID.fromString("10000000-0000-4000-8000-000000000002");

	@Test
	void paginatesAndValidatesDownloadedArchiveWithoutBearerAuthentication() throws Exception {
		VirtualRoom firstRoom = room("portal-hall", "Portal Hall");
		VirtualRoom secondRoom = room("gallery", "Gallery");
		byte[] firstArchive = GsaveCodec.encode(firstRoom);
		byte[] secondArchive = GsaveCodec.encode(secondRoom);
		AtomicInteger pageRequests = new AtomicInteger();
		List<String> rawQueries = new ArrayList<>();
		List<String> syncTokens = new ArrayList<>();
		List<String> authorizationHeaders = new ArrayList<>();

		try (LocalRepository server = new LocalRepository(exchange -> {
			syncTokens.add(exchange.getRequestHeaders().getFirst(RoomRepositoryClient.TOKEN_HEADER));
			authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
			String path = exchange.getRequestURI().getPath();
			if (path.equals("/v1/rooms")) {
				rawQueries.add(exchange.getRequestURI().getRawQuery());
				if (pageRequests.getAndIncrement() == 0) {
					sendJson(exchange, 200, page(
						metadata(FIRST_REPOSITORY_ID, firstRoom, firstArchive, 1),
						"cursor/2"));
				} else {
					sendJson(exchange, 200, page(
						metadata(SECOND_REPOSITORY_ID, secondRoom, secondArchive, 1),
						null));
				}
				return;
			}
			if (path.equals("/v1/rooms/" + FIRST_REPOSITORY_ID)) {
				sendArchive(exchange, firstRoom.id(), firstArchive, "\"1\"");
				return;
			}
			sendJson(exchange, 404, error("ROOM_NOT_FOUND", "missing"));
		})) {
			RoomRepositoryClient client = new RoomRepositoryClient(server.uri(), TOKEN);
			List<RoomRepositoryClient.RoomMetadata> listed = client.listAll().join();

			assertEquals(List.of(firstRoom.id(), secondRoom.id()),
				listed.stream().map(RoomRepositoryClient.RoomMetadata::archiveId).toList());
			assertEquals(2, pageRequests.get());
			assertTrue(rawQueries.get(0).contains("limit=100"));
			assertTrue(rawQueries.get(1).contains("cursor=cursor%2F2"));

			RoomRepositoryClient.DownloadedRoom downloaded = client.download(listed.getFirst()).join();
			assertEquals(firstRoom, downloaded.room());
			assertArrayEquals(firstArchive, downloaded.archive());
			byte[] callerCopy = downloaded.archive();
			callerCopy[0] ^= 1;
			assertArrayEquals(firstArchive, downloaded.archive());

			assertEquals(List.of(TOKEN, TOKEN, TOKEN), syncTokens);
			assertEquals(3, authorizationHeaders.size());
			assertTrue(authorizationHeaders.stream().allMatch(value -> value == null));
		}
	}

	@Test
	void upsertUpdatesMatchingArchiveWithEtagAndGsaveHeaders() throws Exception {
		VirtualRoom previous = room("portal-hall", "Portal Hall");
		VirtualRoom replacement = room("portal-hall", "Portal Café 🚂");
		byte[] previousArchive = GsaveCodec.encode(previous);
		byte[] replacementArchive = GsaveCodec.encode(replacement);
		AtomicReference<byte[]> uploaded = new AtomicReference<>();
		AtomicReference<String> ifMatch = new AtomicReference<>();
		AtomicReference<String> archiveId = new AtomicReference<>();
		AtomicReference<String> roomName = new AtomicReference<>();
		AtomicReference<String> contentType = new AtomicReference<>();

		try (LocalRepository server = new LocalRepository(exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (exchange.getRequestMethod().equals("GET") && path.equals("/v1/rooms")) {
				sendJson(exchange, 200, page(
					metadata(FIRST_REPOSITORY_ID, previous, previousArchive, 1),
					null));
				return;
			}
			if (exchange.getRequestMethod().equals("PUT")
					&& path.equals("/v1/rooms/" + FIRST_REPOSITORY_ID)) {
				uploaded.set(exchange.getRequestBody().readAllBytes());
				ifMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
				archiveId.set(exchange.getRequestHeaders().getFirst("X-Glib-Room-Id"));
				roomName.set(exchange.getRequestHeaders().getFirst("X-Glib-Room-Name"));
				contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
				String body = metadata(FIRST_REPOSITORY_ID, replacement, replacementArchive, 2);
				exchange.getResponseHeaders().set("ETag", "\"2\"");
				exchange.getResponseHeaders().set("X-Content-SHA256", sha256(replacementArchive));
				sendJson(exchange, 200, body);
				return;
			}
			sendJson(exchange, 500, error("UNEXPECTED", "unexpected request"));
		})) {
			RoomRepositoryClient client = new RoomRepositoryClient(server.uri(), TOKEN);
			RoomRepositoryClient.UpsertResult result = client.upsert(replacement).join();

			assertEquals(RoomRepositoryClient.UpsertOperation.UPDATED, result.operation());
			assertEquals(2, result.metadata().revision());
			assertArrayEquals(replacementArchive, uploaded.get());
			assertEquals("\"1\"", ifMatch.get());
			assertEquals("portal-hall", archiveId.get());
			assertEquals("Portal%20Caf%C3%A9%20%F0%9F%9A%82", roomName.get());
			assertEquals(RoomRepositoryClient.GSAVE_MEDIA_TYPE, contentType.get());
		}
	}

	@Test
	void upsertCreatesAnAbsentArchiveAndSurfacesStructuredHttpErrors() throws Exception {
		VirtualRoom room = room("new-room", "New Room");
		byte[] archive = GsaveCodec.encode(room);
		AtomicInteger createAttempts = new AtomicInteger();

		try (LocalRepository server = new LocalRepository(exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (exchange.getRequestMethod().equals("GET") && path.equals("/v1/rooms")) {
				sendJson(exchange, 200, page(null, null));
				return;
			}
			if (exchange.getRequestMethod().equals("POST") && path.equals("/v1/rooms")) {
				if (createAttempts.getAndIncrement() == 0) {
					String body = metadata(FIRST_REPOSITORY_ID, room, archive, 1);
					exchange.getResponseHeaders().set("ETag", "\"1\"");
					exchange.getResponseHeaders().set("X-Content-SHA256", sha256(archive));
					sendJson(exchange, 201, body);
				} else {
					sendJson(exchange, 409,
						error("ARCHIVE_ID_EXISTS", "That archive ID already exists."));
				}
				return;
			}
			sendJson(exchange, 500, error("UNEXPECTED", "unexpected request"));
		})) {
			RoomRepositoryClient client = new RoomRepositoryClient(server.uri(), TOKEN);
			RoomRepositoryClient.UpsertResult created = client.upsert(room).join();
			assertEquals(RoomRepositoryClient.UpsertOperation.CREATED, created.operation());

			CompletionException failure = assertThrows(CompletionException.class, () -> client.create(room).join());
			RoomRepositoryClient.RepositoryException repositoryFailure =
				(RoomRepositoryClient.RepositoryException) failure.getCause();
			assertEquals(409, repositoryFailure.statusCode());
			assertEquals("ARCHIVE_ID_EXISTS", repositoryFailure.code());
			assertEquals("That archive ID already exists.", repositoryFailure.getMessage());
		}
	}

	@Test
	void rejectsMismatchedArchiveIdentityOversizedResponsesAndUnsafeTransport() throws Exception {
		assertThrows(IllegalArgumentException.class,
			() -> new RoomRepositoryClient(URI.create("http://rooms.example.test"), TOKEN));
		assertThrows(IllegalArgumentException.class,
			() -> new RoomRepositoryClient(URI.create("http://127.evil.example.test"), TOKEN));
		assertThrows(IllegalArgumentException.class,
			() -> new RoomRepositoryClient(URI.create("https://rooms.example.test"), "bad\ntoken"));
		assertThrows(IllegalArgumentException.class,
			() -> new RoomRepositoryClient(URI.create("https://rooms.example.test"), "too-short"));

		VirtualRoom expected = room("expected", "Expected");
		VirtualRoom wrong = room("wrong", "Wrong");
		byte[] wrongArchive = GsaveCodec.encode(wrong);

		try (LocalRepository mismatchServer = new LocalRepository(exchange ->
			sendArchive(exchange, expected.id(), wrongArchive, "\"1\""))) {
			RoomRepositoryClient client = new RoomRepositoryClient(mismatchServer.uri(), TOKEN);
			RoomRepositoryClient.RoomMetadata metadata =
				metadataRecord(FIRST_REPOSITORY_ID, expected.id(), wrong.displayName(), wrongArchive, 1);
			CompletionException failure =
				assertThrows(CompletionException.class, () -> client.download(metadata).join());
			assertEquals("ARCHIVE_ID_MISMATCH",
				((RoomRepositoryClient.RepositoryException) failure.getCause()).code());
		}

		try (LocalRepository oversizedServer = new LocalRepository(exchange -> {
			exchange.getResponseHeaders().set("Content-Type", RoomRepositoryClient.GSAVE_MEDIA_TYPE);
			exchange.sendResponseHeaders(200, RoomRepositoryClient.MAX_ARCHIVE_BYTES + 1L);
			exchange.close();
		})) {
			RoomRepositoryClient client = new RoomRepositoryClient(oversizedServer.uri(), TOKEN);
			byte[] expectedArchive = GsaveCodec.encode(expected);
			RoomRepositoryClient.RoomMetadata metadata =
				metadataRecord(FIRST_REPOSITORY_ID, expected.id(), expected.displayName(), expectedArchive, 1);
			CompletionException failure =
				assertThrows(CompletionException.class, () -> client.download(metadata).join());
			assertEquals("RESPONSE_TOO_LARGE",
				((RoomRepositoryClient.RepositoryException) failure.getCause()).code());
		}
	}

	private static VirtualRoom room(String id, String name) {
		return VirtualRoom.builder(RoomId.of(id), name, new RoomDimensions(2, 2, 2)).build();
	}

	private static String page(String roomMetadata, String cursor) {
		return "{\"rooms\":[" + (roomMetadata == null ? "" : roomMetadata) + "],\"nextCursor\":"
			+ (cursor == null ? "null" : "\"" + cursor + "\"") + "}";
	}

	private static String metadata(UUID repositoryId, VirtualRoom room, byte[] archive, long revision) {
		return metadata(
			repositoryId, room.id(), room.displayName(), archive, revision);
	}

	private static String metadata(
			UUID repositoryId,
			RoomId archiveId,
			String name,
			byte[] archive,
			long revision) {
		return "{"
			+ "\"roomId\":\"" + repositoryId + "\","
			+ "\"archiveId\":\"" + archiveId.value() + "\","
			+ "\"name\":\"" + name + "\","
			+ "\"owner\":{\"playerUuid\":\"20000000-0000-4000-8000-000000000001\","
			+ "\"username\":\"Builder\"},"
			+ "\"revision\":\"" + revision + "\","
			+ "\"etag\":\"\\\"" + revision + "\\\"\","
			+ "\"sha256\":\"" + sha256(archive) + "\","
			+ "\"sizeBytes\":" + archive.length + ","
			+ "\"createdAt\":\"2026-07-28T12:00:00Z\","
			+ "\"updatedAt\":\"2026-07-28T12:00:00Z\""
			+ "}";
	}

	private static RoomRepositoryClient.RoomMetadata metadataRecord(
			UUID repositoryId,
			RoomId archiveId,
			String name,
			byte[] archive,
			long revision) {
		return new RoomRepositoryClient.RoomMetadata(
			repositoryId,
			archiveId,
			name,
			new RoomRepositoryClient.Owner(
				UUID.fromString("20000000-0000-4000-8000-000000000001"), "Builder"),
			revision,
			"\"" + revision + "\"",
			sha256(archive),
			archive.length,
			Instant.parse("2026-07-28T12:00:00Z"),
			Instant.parse("2026-07-28T12:00:00Z"));
	}

	private static String error(String code, String message) {
		return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}";
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}

	private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private static void sendArchive(
			HttpExchange exchange,
			RoomId archiveId,
			byte[] archive,
			String etag) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", RoomRepositoryClient.GSAVE_MEDIA_TYPE);
		exchange.getResponseHeaders().set(
			"Content-Disposition", "attachment; filename=\"" + archiveId.value() + ".gsave\"");
		exchange.getResponseHeaders().set("ETag", etag);
		exchange.getResponseHeaders().set("X-Content-SHA256", sha256(archive));
		exchange.sendResponseHeaders(200, archive.length);
		exchange.getResponseBody().write(archive);
		exchange.close();
	}

	private static final class LocalRepository implements AutoCloseable {
		private final HttpServer server;
		private final ExecutorService executor;

		private LocalRepository(HttpHandler handler) throws IOException {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			executor = Executors.newCachedThreadPool(runnable -> {
				Thread thread = new Thread(runnable, "glib-room-repository-test");
				thread.setDaemon(true);
				return thread;
			});
			server.setExecutor(executor);
			server.createContext("/v1/rooms", handler);
			server.start();
		}

		private URI uri() {
			return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
		}

		@Override
		public void close() {
			server.stop(0);
			executor.shutdownNow();
		}
	}
}
