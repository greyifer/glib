package io.github.caspe.glib.api.room;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A directory-backed library of {@code .gsave} rooms.
 *
 * <p>Refreshes are incremental and keep the last valid copy of a room when an
 * edited file is temporarily incomplete or invalid. This makes the library safe
 * to poll while an external editor or repository synchronizer replaces files.</p>
 */
public final class GlibRoomLibrary {
	private final Path directory;
	private final Map<RoomId, VirtualRoom> rooms = new LinkedHashMap<>();
	private final Map<Path, FileStamp> stamps = new LinkedHashMap<>();
	private final Map<Path, RoomId> fileRooms = new LinkedHashMap<>();
	private final Map<Path, Integer> missingScans = new LinkedHashMap<>();
	private final List<Consumer<ChangeSet>> listeners = new ArrayList<>();
	private static final System.Logger LOGGER = System.getLogger(GlibRoomLibrary.class.getName());
	private static final int DELETE_CONFIRMATION_SCANS = 3;

	public GlibRoomLibrary(Path directory) {
		this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
	}

	public Path directory() {
		return directory;
	}

	public synchronized List<VirtualRoom> rooms() {
		return rooms.values().stream()
			.sorted(Comparator.comparing(VirtualRoom::id))
			.toList();
	}

	public synchronized Optional<VirtualRoom> find(RoomId id) {
		return Optional.ofNullable(rooms.get(Objects.requireNonNull(id, "id")));
	}

	public synchronized int size() {
		return rooms.size();
	}

	/**
	 * Atomically saves a room and immediately publishes the resulting library change.
	 */
	public synchronized ChangeSet save(VirtualRoom room) throws IOException {
		Objects.requireNonNull(room, "room");
		GsaveFiles.saveAtomicIn(directory, room);
		return refresh();
	}

	/**
	 * Scans for additions, replacements, and deletions.
	 */
	public synchronized ChangeSet refresh() throws IOException {
		Files.createDirectories(directory);
		List<Path> files;
		try (Stream<Path> stream = Files.list(directory)) {
			files = stream
				.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(GsaveFiles.EXTENSION))
				.map(path -> path.toAbsolutePath().normalize())
				.sorted()
				.toList();
		}

		Set<Path> present = Set.copyOf(files);
		Set<RoomId> updated = new LinkedHashSet<>();
		Set<RoomId> removed = new LinkedHashSet<>();
		Map<Path, String> errors = new LinkedHashMap<>();

		for (Path known : List.copyOf(stamps.keySet())) {
			if (present.contains(known)) {
				missingScans.remove(known);
				continue;
			}
			int misses = missingScans.merge(known, 1, Integer::sum);
			if (misses < DELETE_CONFIRMATION_SCANS) {
				continue;
			}
			missingScans.remove(known);
			stamps.remove(known);
			RoomId oldId = fileRooms.remove(known);
			if (oldId != null && !fileRooms.containsValue(oldId) && rooms.remove(oldId) != null) {
				removed.add(oldId);
			}
		}

		for (Path file : files) {
			missingScans.remove(file);
			FileStamp current = FileStamp.read(file);
			if (current.equals(stamps.get(file))) {
				continue;
			}
			stamps.put(file, current);
			try {
				VirtualRoom loaded = GsaveFiles.load(file);
				String expectedName = loaded.id().value() + GsaveFiles.EXTENSION;
				if (!expectedName.equals(file.getFileName().toString())) {
					throw new GsaveException(
						"Archive id '" + loaded.id() + "' does not match file name '" + file.getFileName() + "'");
				}

				RoomId oldId = fileRooms.put(file, loaded.id());
				if (oldId != null && !oldId.equals(loaded.id())
						&& !fileRooms.containsValue(oldId) && rooms.remove(oldId) != null) {
					removed.add(oldId);
				}
				VirtualRoom previous = rooms.put(loaded.id(), loaded);
				if (!loaded.equals(previous)) {
					updated.add(loaded.id());
				}
			} catch (IOException | RuntimeException exception) {
				errors.put(file, message(exception));
			}
		}

		ChangeSet changes = new ChangeSet(updated, removed, errors);
		if (!changes.isEmpty()) {
			for (Consumer<ChangeSet> listener : List.copyOf(listeners)) {
				try {
					listener.accept(changes);
				} catch (RuntimeException exception) {
					LOGGER.log(System.Logger.Level.ERROR, "Virtual-room library listener failed", exception);
				}
			}
		}
		return changes;
	}

	public synchronized Subscription listen(Consumer<ChangeSet> listener) {
		listeners.add(Objects.requireNonNull(listener, "listener"));
		return () -> {
			synchronized (GlibRoomLibrary.this) {
				listeners.remove(listener);
			}
		};
	}

	private static String message(Exception exception) {
		return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
	}

	public record ChangeSet(Set<RoomId> updated, Set<RoomId> removed, Map<Path, String> errors) {
		public ChangeSet {
			updated = immutableSet(updated);
			removed = immutableSet(removed);
			errors = Map.copyOf(Objects.requireNonNull(errors, "errors"));
		}

		public boolean isEmpty() {
			return updated.isEmpty() && removed.isEmpty() && errors.isEmpty();
		}

		private static <T> Set<T> immutableSet(Collection<T> values) {
			return Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(values, "values")));
		}
	}

	@FunctionalInterface
	public interface Subscription extends AutoCloseable {
		void unsubscribe();

		@Override
		default void close() {
			unsubscribe();
		}
	}

	private record FileStamp(FileTime modified, long size, byte[] digest) {
		private FileStamp {
			digest = digest.clone();
		}

		private static FileStamp read(Path file) throws IOException {
			try {
				long size = Files.size(file);
				FileTime modified = Files.getLastModifiedTime(file);
				if (size > GsaveCodec.MAX_ARCHIVE_BYTES) {
					return new FileStamp(modified, size, new byte[0]);
				}
				MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
				try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), sha256)) {
					input.transferTo(OutputStream.nullOutputStream());
				}
				return new FileStamp(modified, size, sha256.digest());
			} catch (NoSuchAlgorithmException exception) {
				throw new IllegalStateException("Java runtime does not provide SHA-256", exception);
			}
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof FileStamp other
					&& modified.equals(other.modified)
					&& size == other.size
					&& MessageDigest.isEqual(digest, other.digest);
		}

		@Override
		public int hashCode() {
			return Objects.hash(modified, size, java.util.Arrays.hashCode(digest));
		}
	}
}
