package io.github.caspe.glib.room;

import io.github.caspe.glib.api.room.GlibRoomLibrary;
import io.github.caspe.glib.api.room.GsaveFiles;
import io.github.caspe.glib.api.room.RoomId;
import io.github.caspe.glib.api.room.VirtualRoom;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Reconciles a server's local room library with the optional Railway repository.
 *
 * <p>Network and file hashing work happens off the Minecraft thread. Installing
 * verified downloads and publishing the resulting live-library change happens
 * on the server thread.</p>
 */
final class RoomRepositorySynchronizer implements AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger("G'Lib Room Repository");
	private static final long AUTOMATIC_PULL_INTERVAL_TICKS = 30L * 20L;
	private static final String INDEX_FILE_NAME = ".repository-index.properties";
	private static final int REMOTE_DELETE_CONFIRMATION_PULLS = 3;

	private final MinecraftServer server;
	private final GlibRoomLibrary library;
	private final RoomRepositoryClient client;
	private final Path indexFile;
	private final Map<RoomId, String> mirroredHashes = new LinkedHashMap<>();
	private final SerialQueue<RoomId, RoomRepositoryClient.UpsertResult> pushes = new SerialQueue<>();
	private final Map<RoomId, Integer> remoteMissingPulls = new LinkedHashMap<>();
	private final Map<RoomId, String> rejectedRemoteHashes = new ConcurrentHashMap<>();
	private final Map<RoomId, Long> pushGenerations = new ConcurrentHashMap<>();
	private final AtomicLong pushSequence = new AtomicLong();
	private final AtomicLong pushActivity = new AtomicLong();
	private final Object pushLock = new Object();
	private volatile CompletableFuture<PullResult> currentPull;
	private volatile Status status = new Status(null, null, 0, 0, false);
	private volatile boolean closed;
	private long nextAutomaticPullTick = 20L;

	RoomRepositorySynchronizer(
			MinecraftServer server,
			GlibRoomLibrary library,
			RoomRepositoryClient client) {
		this.server = Objects.requireNonNull(server, "server");
		this.library = Objects.requireNonNull(library, "library");
		this.client = Objects.requireNonNull(client, "client");
		this.indexFile = library.directory().resolve(INDEX_FILE_NAME);
		loadIndex();
	}

	void tick(long serverTick) {
		if (!closed && serverTick >= nextAutomaticPullTick) {
			nextAutomaticPullTick = serverTick + AUTOMATIC_PULL_INTERVAL_TICKS;
			pull();
		}
	}

	synchronized CompletableFuture<PullResult> pull() {
		if (closed) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("Room repository synchronizer is stopped"));
		}
		if (currentPull != null && !currentPull.isDone()) {
			return currentPull;
		}
		status = new Status(
			status.lastSuccessfulPull(),
			status.lastError(),
			status.remoteRooms(),
			status.installedRooms(),
			true);
		long pushActivityAtStart = pushActivity.get();
		CompletableFuture<PullResult> started = client.listAll()
			.thenCompose(metadata -> downloadChanges(metadata, pushActivityAtStart))
			.thenCompose(batch -> onServer(() -> install(batch)))
			.whenComplete((result, error) -> {
				Instant completedAt = Instant.now();
				if (error == null) {
					recordPullSuccess(completedAt, result);
					if (result.installed() > 0 || result.removed() > 0 || !result.rejected().isEmpty()) {
						LOGGER.info(
							"Room repository pull: {} installed, {} removed, {} unchanged, {} rejected",
							result.installed(),
							result.removed(),
							result.unchanged(),
							result.rejected().size());
					}
					for (String rejected : result.rejected()) {
						LOGGER.warn("Repository room was not applied: {}", rejected);
					}
				} else {
					recordPullFailure(error);
					LOGGER.warn("Room repository pull failed: {}", errorMessage(error));
				}
			});
		currentPull = started;
		return started;
	}

	CompletableFuture<RoomRepositoryClient.UpsertResult> push(VirtualRoom room) {
		Objects.requireNonNull(room, "room");
		CompletableFuture<RoomRepositoryClient.UpsertResult> queued;
		synchronized (pushLock) {
			if (closed) {
				return CompletableFuture.failedFuture(
					new IllegalStateException("Room repository synchronizer is stopped"));
			}
			pushGenerations.put(room.id(), pushSequence.incrementAndGet());
			pushActivity.incrementAndGet();
			queued = pushes.submit(room.id(), () -> {
				if (closed) {
					return CompletableFuture.failedFuture(
						new IllegalStateException("Room repository synchronizer is stopped"));
				}
				return client.upsert(room)
					.whenComplete((ignored, error) -> pushActivity.incrementAndGet());
			});
		}
		queued.whenComplete((ignored, error) -> {
			recordPushCompletion(error);
		});
		return queued;
	}

	Status status() {
		return status;
	}

	private synchronized void recordPullSuccess(Instant completedAt, PullResult result) {
		status = new Status(
			completedAt, null, result.remoteRooms(), result.installed(), false);
	}

	private synchronized void recordPullFailure(Throwable error) {
		Status current = status;
		status = new Status(
			current.lastSuccessfulPull(),
			errorMessage(error),
			current.remoteRooms(),
			current.installedRooms(),
			false);
	}

	private synchronized void recordPushCompletion(Throwable error) {
		Status current = status;
		status = new Status(
			current.lastSuccessfulPull(),
			error == null ? null : errorMessage(error),
			current.remoteRooms(),
			current.installedRooms(),
			current.pulling());
	}

	private CompletableFuture<DownloadBatch> downloadChanges(
			List<RoomRepositoryClient.RoomMetadata> metadata,
			long pushActivityAtStart) {
		List<String> rejected = new ArrayList<>();
		List<PlannedDownload> plannedDownloads = new ArrayList<>();
		int unchanged = 0;
		for (RoomRepositoryClient.RoomMetadata entry : metadata) {
			PullPlan plan = planPull(entry);
			if (plan.decision() == PullDecision.UNCHANGED) {
				unchanged++;
				continue;
			}
			if (plan.decision() == PullDecision.LOCAL_CONFLICT) {
				rejected.add(entry.archiveId() + ": " + plan.message());
				continue;
			}
			plannedDownloads.add(new PlannedDownload(entry, plan));
		}

		CompletableFuture<List<PendingDownload>> chain =
			CompletableFuture.completedFuture(new ArrayList<>());
		for (PlannedDownload planned : plannedDownloads) {
			RoomRepositoryClient.RoomMetadata entry = planned.metadata();
			PullPlan plan = planned.plan();
			chain = chain.thenCompose(downloaded ->
				client.download(entry).handle((room, error) -> {
					if (error == null) {
						downloaded.add(new PendingDownload(
							room, plan.localHash(), plan.pushGeneration()));
						return downloaded;
					}
					Throwable cause = unwrap(error);
					if (isQuarantinableDownloadFailure(cause)) {
						rejectedRemoteHashes.put(entry.archiveId(), entry.sha256());
						rejected.add(entry.archiveId() + ": " + errorMessage(cause));
						return downloaded;
					}
					throw new CompletionException(cause);
				}));
		}
		int unchangedCount = unchanged;
		return chain.thenApply(downloaded ->
			new DownloadBatch(
				List.copyOf(metadata),
				unchangedCount,
				List.copyOf(downloaded),
				List.copyOf(rejected),
				pushActivityAtStart));
	}

	private PullPlan planPull(RoomRepositoryClient.RoomMetadata metadata) {
		boolean quarantined =
			metadata.sha256().equals(rejectedRemoteHashes.get(metadata.archiveId()));
		long pushGeneration = pushGenerations.getOrDefault(metadata.archiveId(), 0L);
		if (pushes.isActive(metadata.archiveId())) {
			return new PullPlan(
				PullDecision.LOCAL_CONFLICT,
				null,
				pushGeneration,
				"a repository export is in progress; the remote update was deferred");
		}

		Path file = GsaveFiles.pathFor(library.directory(), metadata.archiveId());
		String localHash = null;
		if (Files.exists(file)) {
			if (!Files.isRegularFile(file)) {
				return new PullPlan(
					PullDecision.LOCAL_CONFLICT,
					null,
					pushGeneration,
					"the local archive path is not a regular file and was preserved");
			}
			try {
				localHash = sha256(file);
			} catch (IOException exception) {
				return new PullPlan(
					PullDecision.LOCAL_CONFLICT,
					null,
					pushGeneration,
					"the local archive could not be verified and was preserved: "
						+ errorMessage(exception));
			}
		}

		long currentPushGeneration = pushGenerations.getOrDefault(metadata.archiveId(), 0L);
		if (currentPushGeneration != pushGeneration || pushes.isActive(metadata.archiveId())) {
			return new PullPlan(
				PullDecision.LOCAL_CONFLICT,
				localHash,
				currentPushGeneration,
				"a repository export started while the local archive was being checked; "
					+ "the remote update was deferred");
		}

		if (quarantined) {
			if (localHash != null && !localHash.equals(metadata.sha256())) {
				return new PullPlan(
					PullDecision.LOCAL_CONFLICT,
					localHash,
					currentPushGeneration,
					"the remote archive remains quarantined and the different local archive "
						+ "was preserved");
			}
			return new PullPlan(
				PullDecision.UNCHANGED, localHash, currentPushGeneration, null);
		}

		String mirroredHash = mirroredHashes.get(metadata.archiveId());
		PullDecision decision = decidePull(localHash, mirroredHash, metadata.sha256());
		if (decision != PullDecision.LOCAL_CONFLICT) {
			return new PullPlan(decision, localHash, currentPushGeneration, null);
		}
		String detail = mirroredHash == null
			? "it has no verified mirror baseline"
			: "it diverged from the last verified mirror";
		return new PullPlan(
			decision,
			localHash,
			currentPushGeneration,
			"the local archive differs from the repository and " + detail + "; it was preserved");
	}

	static PullDecision decidePull(String localHash, String mirroredHash, String remoteHash) {
		Objects.requireNonNull(remoteHash, "remoteHash");
		if (localHash == null) {
			return PullDecision.DOWNLOAD;
		}
		if (localHash.equals(remoteHash)) {
			return PullDecision.UNCHANGED;
		}
		if (mirroredHash != null && localHash.equals(mirroredHash)) {
			return PullDecision.DOWNLOAD;
		}
		return PullDecision.LOCAL_CONFLICT;
	}

	private boolean fileMatchesArchive(RoomRepositoryClient.RoomMetadata metadata) {
		Path file = GsaveFiles.pathFor(library.directory(), metadata.archiveId());
		try {
			return Files.isRegularFile(file)
				&& Files.size(file) == metadata.sizeBytes()
				&& MessageDigest.isEqual(
					sha256(file).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
					metadata.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		} catch (IOException exception) {
			return false;
		}
	}

	private PullResult install(DownloadBatch batch) {
		if (closed) {
			throw new IllegalStateException("Room repository synchronizer is stopped");
		}
		int installed = 0;
		int removed = 0;
		List<String> rejected = new ArrayList<>(batch.rejected());
		for (PendingDownload pending : batch.downloads()) {
			RoomRepositoryClient.DownloadedRoom downloaded = pending.downloaded();
			String validationError = RoomRuntime.validationError(downloaded.room()).orElse(null);
			if (validationError != null) {
				rejectedRemoteHashes.put(
					downloaded.room().id(), downloaded.metadata().sha256());
				rejected.add(downloaded.room().id() + ": " + validationError);
				continue;
			}
			try {
				String installConflict;
				synchronized (library) {
					synchronized (pushLock) {
						installConflict = installConflict(pending);
						if (installConflict == null) {
							installConflict = installArchiveIfUnchanged(
								GsaveFiles.pathFor(library.directory(), downloaded.room().id()),
								downloaded.archive(),
								pending.expectedLocalHash(),
								downloaded.metadata().sha256());
						}
					}
				}
				if (installConflict != null) {
					rejected.add(downloaded.room().id() + ": " + installConflict);
					continue;
				}
				rejectedRemoteHashes.remove(downloaded.room().id());
				installed++;
			} catch (IOException exception) {
				throw new CompletionException(exception);
			}
		}
		Set<RoomId> remoteIds = new LinkedHashSet<>();
		for (RoomRepositoryClient.RoomMetadata metadata : batch.metadata()) {
			remoteIds.add(metadata.archiveId());
			remoteMissingPulls.remove(metadata.archiveId());
			if (fileMatchesArchive(metadata)) {
				mirroredHashes.put(metadata.archiveId(), metadata.sha256());
			}
		}
		rejectedRemoteHashes.keySet().removeIf(roomId -> !remoteIds.contains(roomId));
		synchronized (library) {
			synchronized (pushLock) {
				if (deletionScanStable(
						batch.pushActivityAtStart(), pushActivity.get(), pushes.hasActive())) {
					removed += removeConfirmedRemoteDeletions(remoteIds, rejected);
				}
			}
		}
		try {
			saveIndex();
			if (installed > 0 || removed > 0) {
				library.refresh();
			}
		} catch (IOException exception) {
			throw new CompletionException(exception);
		}
		return new PullResult(
			batch.metadata().size(),
			installed,
			removed,
			batch.unchanged(),
			List.copyOf(rejected));
	}

	private int removeConfirmedRemoteDeletions(
			Set<RoomId> remoteIds, List<String> rejected) {
		int removed = 0;
		for (Map.Entry<RoomId, String> mirrored : List.copyOf(mirroredHashes.entrySet())) {
			if (remoteIds.contains(mirrored.getKey())) {
				continue;
			}
			if (!confirmRemoteAbsence(remoteMissingPulls, mirrored.getKey())) {
				continue;
			}
			Path file = GsaveFiles.pathFor(library.directory(), mirrored.getKey());
			try {
				MirrorRemoval removal = removeMirrorIfUnchanged(file, mirrored.getValue());
				if (removal.conflict() != null) {
					rejected.add(mirrored.getKey() + ": " + removal.conflict());
				}
				if (removal.removed()) {
					removed++;
				}
				mirroredHashes.remove(mirrored.getKey());
				remoteMissingPulls.remove(mirrored.getKey());
			} catch (IOException exception) {
				rejected.add(mirrored.getKey()
					+ ": the remote room appears deleted, but the local mirror could not "
					+ "be verified or removed: " + errorMessage(exception));
			}
		}
		return removed;
	}

	private String installConflict(PendingDownload pending) {
		RoomId roomId = pending.downloaded().room().id();
		if (pushGenerations.getOrDefault(roomId, 0L) != pending.pushGeneration()
				|| pushes.isActive(roomId)) {
			return "a repository export started during the pull; the downloaded copy "
				+ "was not installed";
		}

		Path file = GsaveFiles.pathFor(library.directory(), roomId);
		String expectedHash = pending.expectedLocalHash();
		if (expectedHash == null) {
			if (Files.exists(file)) {
				return "a local archive appeared during the pull and was preserved";
			}
		} else {
			if (!Files.isRegularFile(file)) {
				return "the local archive changed during the pull and was preserved";
			}
			try {
				if (!MessageDigest.isEqual(
						sha256(file).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
						expectedHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
					return "the local archive changed during the pull and was preserved";
				}
			} catch (IOException exception) {
				return "the local archive could not be re-verified and was preserved: "
					+ errorMessage(exception);
			}
		}

		if (pushGenerations.getOrDefault(roomId, 0L) != pending.pushGeneration()
				|| pushes.isActive(roomId)) {
			return "a repository export started during the pull; the downloaded copy "
				+ "was not installed";
		}
		return null;
	}

	static boolean confirmRemoteAbsence(Map<RoomId, Integer> missingPulls, RoomId roomId) {
		Objects.requireNonNull(missingPulls, "missingPulls");
		Objects.requireNonNull(roomId, "roomId");
		return missingPulls.merge(roomId, 1, Integer::sum) >= REMOTE_DELETE_CONFIRMATION_PULLS;
	}

	static boolean deletionScanStable(
			long pushActivityAtStart, long currentPushActivity, boolean pushActive) {
		return !pushActive && pushActivityAtStart == currentPushActivity;
	}

	static boolean isQuarantinableDownloadFailure(Throwable error) {
		Throwable cause = unwrap(error);
		if (!(cause instanceof RoomRepositoryClient.RepositoryException repositoryError)) {
			return false;
		}
		return repositoryError.code().equals("INVALID_GSAVE")
			|| repositoryError.code().equals("ARCHIVE_ID_MISMATCH");
	}

	static void writeAtomicArchive(Path file, byte[] archive) throws IOException {
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(archive, "archive");
		Path target = file.toAbsolutePath().normalize();
		Path parent = target.getParent();
		if (parent == null) {
			throw new IllegalArgumentException("Room archive must have a parent directory");
		}
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, "." + target.getFileName(), ".tmp");
		boolean moved = false;
		try {
			Files.write(
				temporary,
				archive,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE,
				StandardOpenOption.SYNC);
			try {
				Files.move(
					temporary,
					target,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				throw new IOException("Filesystem does not support atomic room downloads", exception);
			}
			moved = true;
		} finally {
			if (!moved) {
				Files.deleteIfExists(temporary);
			}
		}
	}

	static String installArchiveIfUnchanged(
			Path file,
			byte[] archive,
			String expectedLocalHash,
			String remoteHash) throws IOException {
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(archive, "archive");
		Objects.requireNonNull(remoteHash, "remoteHash");
		Path target = file.toAbsolutePath().normalize();
		Path parent = target.getParent();
		if (parent == null) {
			throw new IllegalArgumentException("Room archive must have a parent directory");
		}
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, "." + target.getFileName(), ".download");
		Path backup = null;
		boolean backupVerified = false;
		boolean installed = false;
		boolean installationVerified = false;
		Throwable primaryFailure = null;
		try {
			Files.write(
				temporary,
				archive,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE,
				StandardOpenOption.SYNC);
			requireHardLinkSupport(temporary, target);

			if (expectedLocalHash == null) {
				if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
					return "a local archive appeared during final installation and was preserved";
				}
			} else {
				if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
					return "the local archive changed during final installation and was preserved";
				}
				backup = uniqueSibling(target, "preinstall");
				moveAtomicToUniqueSibling(target, backup);
				String stagedHash = sha256(backup);
				if (!secureHashEquals(stagedHash, expectedLocalHash)) {
					Path preserved = restoreOrPreserveConflict(backup, target);
					backup = null;
					return "the local archive changed during final installation and was preserved"
						+ preservedSuffix(preserved, target);
				}
				backupVerified = true;
			}

			try {
				// Publishing a hard link to the fully synced temporary file gives
				// us atomic create-if-absent semantics. Files.move cannot promise
				// both atomicity and no replacement through the Java API.
				Files.createLink(target, temporary);
				installed = true;
			} catch (FileAlreadyExistsException exception) {
				Path preservedBackup = backup;
				backup = null;
				return "a local archive appeared during final installation and was preserved"
					+ (preservedBackup == null
						? ""
						: "; the prior mirror remains at " + preservedBackup);
			}
			if (!secureHashEquals(sha256(target), remoteHash)) {
				Path preservedBackup = backup;
				backup = null;
				return "the local archive changed immediately after installation and was preserved"
					+ (preservedBackup == null
						? ""
						: "; the prior mirror remains at " + preservedBackup);
			}
			installationVerified = true;
			return null;
		} catch (IOException | RuntimeException | Error exception) {
			primaryFailure = exception;
			throw exception;
		} finally {
			IOException cleanupFailure = null;
			if (backup != null && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
				if (!installed && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
					try {
						Path preserved = restoreOrPreserveConflict(backup, target);
						if (preserved.equals(target)) {
							backup = null;
						} else {
							cleanupFailure = appendFailure(
								cleanupFailure,
								new IOException(
									"A staged room archive remains recoverable at " + backup));
						}
					} catch (IOException exception) {
						cleanupFailure = appendFailure(
							cleanupFailure,
							new IOException(
								"Could not restore the staged room archive; it remains at " + backup,
								exception));
					}
				}
				if (backup != null && backupVerified && installationVerified) {
					try {
						Files.deleteIfExists(backup);
						backup = null;
					} catch (IOException exception) {
						cleanupFailure = appendFailure(cleanupFailure, exception);
					}
				} else if (backup != null && cleanupFailure == null) {
					cleanupFailure = new IOException(
						"A staged room archive remains recoverable at " + backup);
				}
			}
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException exception) {
				cleanupFailure = appendFailure(cleanupFailure, exception);
			}
			if (cleanupFailure != null) {
				if (primaryFailure != null) {
					primaryFailure.addSuppressed(cleanupFailure);
				} else {
					throw cleanupFailure;
				}
			}
		}
	}

	static MirrorRemoval removeMirrorIfUnchanged(Path file, String expectedHash) throws IOException {
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(expectedHash, "expectedHash");
		Path target = file.toAbsolutePath().normalize();
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			return new MirrorRemoval(false, null);
		}
		if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
			return new MirrorRemoval(
				false,
				"the remote room was deleted, but the local archive path is not a regular file "
					+ "and was preserved");
		}
		requireHardLinkSupport(target, target);
		Path backup = uniqueSibling(target, "predelete");
		try {
			moveAtomicToUniqueSibling(target, backup);
		} catch (NoSuchFileException exception) {
			return new MirrorRemoval(false, null);
		}
		String stagedHash;
		try {
			stagedHash = sha256(backup);
		} catch (IOException exception) {
			Path preserved;
			try {
				preserved = restoreOrPreserveConflict(backup, target);
			} catch (IOException restoreFailure) {
				exception.addSuppressed(restoreFailure);
				preserved = backup;
			}
			throw new IOException(
				"Could not verify the staged local mirror"
					+ preservedSuffix(preserved, target),
				exception);
		}
		if (!secureHashEquals(stagedHash, expectedHash)) {
			Path preserved;
			try {
				preserved = restoreOrPreserveConflict(backup, target);
			} catch (IOException exception) {
				preserved = backup;
			}
			return new MirrorRemoval(
				false,
				"the remote room was deleted, but the locally modified archive was preserved"
					+ preservedSuffix(preserved, target));
		}
		Files.deleteIfExists(backup);
		return new MirrorRemoval(true, null);
	}

	private static void moveAtomicToUniqueSibling(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			throw new IOException("Filesystem does not support atomic room synchronization", exception);
		}
	}

	private static void requireHardLinkSupport(Path existing, Path target) throws IOException {
		Path probe = uniqueSibling(target, "link-check");
		boolean linked = false;
		try {
			Files.createLink(probe, existing);
			linked = true;
		} catch (UnsupportedOperationException exception) {
			throw new IOException(
				"Filesystem does not support no-clobber room synchronization", exception);
		} finally {
			if (linked) {
				Files.deleteIfExists(probe);
			}
		}
	}

	private static Path restoreOrPreserveConflict(Path backup, Path target) throws IOException {
		try {
			Files.createLink(target, backup);
		} catch (FileAlreadyExistsException exception) {
			return backup;
		}
		Files.delete(backup);
		return target;
	}

	private static Path uniqueSibling(Path target, String purpose) {
		return target.resolveSibling(
			"." + target.getFileName() + ".glib-" + purpose + "-" + UUID.randomUUID());
	}

	private static String preservedSuffix(Path preserved, Path target) {
		return preserved.equals(target) ? "" : " at " + preserved;
	}

	private static boolean secureHashEquals(String first, String second) {
		return MessageDigest.isEqual(
			first.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
			second.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
	}

	private static IOException appendFailure(IOException existing, IOException additional) {
		if (existing == null) {
			return additional;
		}
		existing.addSuppressed(additional);
		return existing;
	}

	private void loadIndex() {
		if (!Files.isRegularFile(indexFile)) {
			return;
		}
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(indexFile)) {
			properties.load(input);
			for (String key : properties.stringPropertyNames()) {
				String sha256 = properties.getProperty(key, "");
				if (!sha256.matches("[0-9a-f]{64}")) {
					continue;
				}
				try {
					mirroredHashes.put(RoomId.of(key), sha256);
				} catch (IllegalArgumentException ignored) {
				}
			}
		} catch (IOException exception) {
			LOGGER.warn("Could not read room repository mirror index: {}", exception.getMessage());
			mirroredHashes.clear();
		}
	}

	private void saveIndex() throws IOException {
		Files.createDirectories(library.directory());
		Properties properties = new Properties();
		for (Map.Entry<RoomId, String> entry : mirroredHashes.entrySet()) {
			properties.setProperty(entry.getKey().value(), entry.getValue());
		}
		Path temporary = Files.createTempFile(
			library.directory(), ".repository-index-", ".tmp");
		try {
			try (OutputStream output = Files.newOutputStream(temporary)) {
				properties.store(output, "G'Lib repository mirror checksums");
			}
			try {
				Files.move(
					temporary,
					indexFile,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, indexFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private <T> CompletableFuture<T> onServer(Supplier<T> work) {
		CompletableFuture<T> result = new CompletableFuture<>();
		if (closed) {
			result.completeExceptionally(
				new IllegalStateException("Room repository synchronizer is stopped"));
			return result;
		}
		server.execute(() -> {
			if (closed) {
				result.completeExceptionally(
					new IllegalStateException("Room repository synchronizer is stopped"));
				return;
			}
			try {
				result.complete(work.get());
			} catch (Throwable throwable) {
				result.completeExceptionally(throwable);
			}
		});
		return result;
	}

	private static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream raw = Files.newInputStream(file);
					DigestInputStream input = new DigestInputStream(raw, digest)) {
				input.transferTo(java.io.OutputStream.nullOutputStream());
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Java runtime does not provide SHA-256", exception);
		}
	}

	private static String errorMessage(Throwable error) {
		Throwable current = unwrap(error);
		String message = current.getMessage();
		return message == null ? current.getClass().getSimpleName() : message;
	}

	private static Throwable unwrap(Throwable error) {
		Throwable current = Objects.requireNonNull(error, "error");
		while ((current instanceof CompletionException
				|| current instanceof java.util.concurrent.ExecutionException)
				&& current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	@Override
	public void close() {
		closed = true;
		synchronized (pushLock) {
			pushes.clear();
		}
	}

	record MirrorRemoval(boolean removed, String conflict) {
	}

	record PullResult(
			int remoteRooms,
			int installed,
			int removed,
			int unchanged,
			List<String> rejected) {
		PullResult {
			rejected = List.copyOf(rejected);
		}
	}

	record Status(
			Instant lastSuccessfulPull,
			String lastError,
			int remoteRooms,
			int installedRooms,
			boolean pulling) {
	}

	private record DownloadBatch(
			List<RoomRepositoryClient.RoomMetadata> metadata,
			int unchanged,
			List<PendingDownload> downloads,
			List<String> rejected,
			long pushActivityAtStart) {
	}

	private record PendingDownload(
			RoomRepositoryClient.DownloadedRoom downloaded,
			String expectedLocalHash,
			long pushGeneration) {
	}

	private record PlannedDownload(
			RoomRepositoryClient.RoomMetadata metadata,
			PullPlan plan) {
	}

	private record PullPlan(
			PullDecision decision,
			String localHash,
			long pushGeneration,
			String message) {
	}

	enum PullDecision {
		UNCHANGED,
		DOWNLOAD,
		LOCAL_CONFLICT
	}

	static final class SerialQueue<K, V> {
		private final Map<K, CompletableFuture<V>> tails = new LinkedHashMap<>();

		synchronized CompletableFuture<V> submit(
				K key, Supplier<CompletableFuture<V>> operation) {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(operation, "operation");
			CompletableFuture<V> previous = tails.get(key);
			CompletableFuture<V> next;
			if (previous == null || previous.isDone()) {
				next = invoke(operation);
			} else {
				next = previous.handle((ignored, error) -> null)
					.thenCompose(ignored -> invoke(operation));
			}
			tails.put(key, next);
			next.whenComplete((ignored, error) -> {
				synchronized (SerialQueue.this) {
					tails.remove(key, next);
				}
			});
			return next;
		}

		synchronized boolean isActive(K key) {
			CompletableFuture<V> current = tails.get(key);
			return current != null && !current.isDone();
		}

		synchronized boolean hasActive() {
			return tails.values().stream().anyMatch(future -> !future.isDone());
		}

		synchronized void clear() {
			tails.clear();
		}

		private static <V> CompletableFuture<V> invoke(
				Supplier<CompletableFuture<V>> operation) {
			try {
				return Objects.requireNonNull(operation.get(), "operation future");
			} catch (Throwable throwable) {
				return CompletableFuture.failedFuture(throwable);
			}
		}
	}
}
