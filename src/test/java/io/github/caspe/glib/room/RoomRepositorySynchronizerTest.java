package io.github.caspe.glib.room;

import io.github.caspe.glib.api.room.RoomId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RoomRepositorySynchronizerTest {
	@Test
	void threeWayDecisionNeverOverwritesAnUntrackedOrLocallyDivergedArchive() {
		assertEquals(
			RoomRepositorySynchronizer.PullDecision.DOWNLOAD,
			RoomRepositorySynchronizer.decidePull(null, null, "remote"));
		assertEquals(
			RoomRepositorySynchronizer.PullDecision.UNCHANGED,
			RoomRepositorySynchronizer.decidePull("remote", null, "remote"));
		assertEquals(
			RoomRepositorySynchronizer.PullDecision.DOWNLOAD,
			RoomRepositorySynchronizer.decidePull("baseline", "baseline", "remote"));
		assertEquals(
			RoomRepositorySynchronizer.PullDecision.LOCAL_CONFLICT,
			RoomRepositorySynchronizer.decidePull("local", null, "remote"));
		assertEquals(
			RoomRepositorySynchronizer.PullDecision.LOCAL_CONFLICT,
			RoomRepositorySynchronizer.decidePull("local", "baseline", "remote"));
		assertEquals(
			RoomRepositorySynchronizer.PullDecision.LOCAL_CONFLICT,
			RoomRepositorySynchronizer.decidePull("local", "remote", "remote"));
	}

	@Test
	void sameRoomPushesRunInOrderInsteadOfDroppingTheLatestSnapshot() {
		RoomRepositorySynchronizer.SerialQueue<String, Integer> queue =
			new RoomRepositorySynchronizer.SerialQueue<>();
		CompletableFuture<Integer> firstGate = new CompletableFuture<>();
		AtomicInteger invocations = new AtomicInteger();

		CompletableFuture<Integer> first = queue.submit("room", () -> {
			invocations.incrementAndGet();
			return firstGate;
		});
		CompletableFuture<Integer> latest = queue.submit("room", () -> {
			invocations.incrementAndGet();
			return CompletableFuture.completedFuture(2);
		});

		assertNotSame(first, latest);
		assertEquals(1, invocations.get());
		assertFalse(latest.isDone());
		assertTrue(queue.isActive("room"));
		assertTrue(queue.hasActive());

		firstGate.complete(1);

		assertEquals(1, first.join());
		assertEquals(2, latest.join());
		assertEquals(2, invocations.get());
		assertFalse(queue.isActive("room"));
		assertFalse(queue.hasActive());
	}

	@Test
	void aFailedPushDoesNotStarveTheQueuedLatestSnapshot() {
		RoomRepositorySynchronizer.SerialQueue<String, Integer> queue =
			new RoomRepositorySynchronizer.SerialQueue<>();
		CompletableFuture<Integer> firstGate = new CompletableFuture<>();

		CompletableFuture<Integer> first = queue.submit("room", () -> firstGate);
		CompletableFuture<Integer> latest = queue.submit(
			"room", () -> CompletableFuture.completedFuture(2));

		firstGate.completeExceptionally(new IOException("first upload failed"));

		assertThrows(CompletionException.class, first::join);
		assertEquals(2, latest.join());
	}

	@Test
	void remoteDeletionRequiresThreeConsecutiveCompletePulls() {
		Map<RoomId, Integer> missingPulls = new LinkedHashMap<>();
		RoomId roomId = RoomId.of("dining-car");

		assertFalse(RoomRepositorySynchronizer.confirmRemoteAbsence(missingPulls, roomId));
		assertFalse(RoomRepositorySynchronizer.confirmRemoteAbsence(missingPulls, roomId));
		assertTrue(RoomRepositorySynchronizer.confirmRemoteAbsence(missingPulls, roomId));

		missingPulls.remove(roomId);
		assertFalse(RoomRepositorySynchronizer.confirmRemoteAbsence(missingPulls, roomId));
	}

	@Test
	void remoteDeletionIsDeferredWhenAnyPushOverlapsTheListSnapshot() {
		assertTrue(RoomRepositorySynchronizer.deletionScanStable(7L, 7L, false));
		assertFalse(RoomRepositorySynchronizer.deletionScanStable(7L, 8L, false));
		assertFalse(RoomRepositorySynchronizer.deletionScanStable(7L, 7L, true));
	}

	@Test
	void installsTheValidatedArchiveBytesWithoutReencoding(@TempDir Path directory)
			throws IOException {
		Path target = directory.resolve("dining-car.gsave");
		byte[] first = {0, 1, 2, 3, 4, 5, (byte) 0xff};
		byte[] replacement = {9, 8, 7, 6, 5};

		RoomRepositorySynchronizer.writeAtomicArchive(target, first);
		assertArrayEquals(first, Files.readAllBytes(target));

		RoomRepositorySynchronizer.writeAtomicArchive(target, replacement);
		assertArrayEquals(replacement, Files.readAllBytes(target));
		try (var files = Files.list(directory)) {
			assertEquals(1L, files.count());
		}
	}

	@Test
	void finalInstallReplacesOnlyTheExactExpectedBaseline(@TempDir Path directory)
			throws IOException {
		Path target = directory.resolve("dining-car.gsave");
		byte[] baseline = {1, 2, 3};
		byte[] remote = {4, 5, 6, 7};
		Files.write(target, baseline);

		String conflict = RoomRepositorySynchronizer.installArchiveIfUnchanged(
			target, remote, sha256(baseline), sha256(remote));

		assertNull(conflict);
		assertArrayEquals(remote, Files.readAllBytes(target));
		try (var files = Files.list(directory)) {
			assertEquals(1L, files.count());
		}
	}

	@Test
	void finalInstallPreservesAChangedLocalArchive(@TempDir Path directory)
			throws IOException {
		Path target = directory.resolve("dining-car.gsave");
		byte[] expectedBaseline = {1, 2, 3};
		byte[] localEdit = {8, 8, 8};
		byte[] remote = {4, 5, 6};
		Files.write(target, localEdit);

		String conflict = RoomRepositorySynchronizer.installArchiveIfUnchanged(
			target, remote, sha256(expectedBaseline), sha256(remote));

		assertNotNull(conflict);
		assertTrue(conflict.contains("local archive changed"));
		assertArrayEquals(localEdit, Files.readAllBytes(target));
		try (var files = Files.list(directory)) {
			assertEquals(1L, files.count());
		}
	}

	@Test
	void finalInstallPreservesAnArchiveThatAppearedWithoutABaseline(
			@TempDir Path directory) throws IOException {
		Path target = directory.resolve("dining-car.gsave");
		byte[] localEdit = {8, 8, 8};
		byte[] remote = {4, 5, 6};
		Files.write(target, localEdit);

		String conflict = RoomRepositorySynchronizer.installArchiveIfUnchanged(
			target, remote, null, sha256(remote));

		assertNotNull(conflict);
		assertTrue(conflict.contains("appeared"));
		assertArrayEquals(localEdit, Files.readAllBytes(target));
	}

	@Test
	void finalDeleteRemovesOnlyTheExactMirroredArchive(@TempDir Path directory)
			throws IOException {
		Path target = directory.resolve("dining-car.gsave");
		byte[] mirrored = {1, 2, 3};
		Files.write(target, mirrored);

		RoomRepositorySynchronizer.MirrorRemoval removal =
			RoomRepositorySynchronizer.removeMirrorIfUnchanged(
				target, sha256(mirrored));

		assertTrue(removal.removed());
		assertNull(removal.conflict());
		assertFalse(Files.exists(target));
		try (var files = Files.list(directory)) {
			assertEquals(0L, files.count());
		}
	}

	@Test
	void finalDeletePreservesALocallyChangedArchive(@TempDir Path directory)
			throws IOException {
		Path target = directory.resolve("dining-car.gsave");
		byte[] expectedMirror = {1, 2, 3};
		byte[] localEdit = {8, 8, 8};
		Files.write(target, localEdit);

		RoomRepositorySynchronizer.MirrorRemoval removal =
			RoomRepositorySynchronizer.removeMirrorIfUnchanged(
				target, sha256(expectedMirror));

		assertFalse(removal.removed());
		assertNotNull(removal.conflict());
		assertArrayEquals(localEdit, Files.readAllBytes(target));
		try (var files = Files.list(directory)) {
			assertEquals(1L, files.count());
		}
	}

	@Test
	void quarantinesOnlyArchiveDecodeAndIdentityFailures() {
		assertTrue(RoomRepositorySynchronizer.isQuarantinableDownloadFailure(
			new RoomRepositoryClient.RepositoryException(
				0, "INVALID_GSAVE", "bad archive")));
		assertTrue(RoomRepositorySynchronizer.isQuarantinableDownloadFailure(
			new CompletionException(new RoomRepositoryClient.RepositoryException(
				0, "ARCHIVE_ID_MISMATCH", "wrong room"))));
		assertFalse(RoomRepositorySynchronizer.isQuarantinableDownloadFailure(
			new RoomRepositoryClient.RepositoryException(
				0, "NETWORK_ERROR", "temporary failure")));
		assertFalse(RoomRepositorySynchronizer.isQuarantinableDownloadFailure(
			new IOException("disk failed")));
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}
}
