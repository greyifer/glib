package io.github.caspe.glib.api.room;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Path-safe and atomic file helpers for room libraries.
 */
public final class GsaveFiles {
	public static final String EXTENSION = ".gsave";

	private GsaveFiles() {
	}

	public static Path pathFor(Path libraryDirectory, RoomId roomId) {
		Path directory = Objects.requireNonNull(libraryDirectory, "libraryDirectory")
				.toAbsolutePath()
				.normalize();
		Path result = directory.resolve(roomId.value() + EXTENSION).normalize();
		if (!result.getParent().equals(directory)) {
			throw new IllegalArgumentException("Room path escapes its library directory");
		}
		return result;
	}

	public static VirtualRoom load(Path file) throws IOException {
		Path normalized = requireExtension(file);
		long size = Files.size(normalized);
		if (size > GsaveCodec.MAX_ARCHIVE_BYTES) {
			throw new GsaveException("Room archive exceeds " + GsaveCodec.MAX_ARCHIVE_BYTES + " bytes");
		}
		return GsaveCodec.decode(Files.readAllBytes(normalized));
	}

	public static void saveAtomic(Path file, VirtualRoom room) throws IOException {
		Path target = requireExtension(file).toAbsolutePath().normalize();
		Path parent = target.getParent();
		if (parent == null) {
			throw new IllegalArgumentException("Room file must have a parent directory");
		}
		Files.createDirectories(parent);
		byte[] archive = GsaveCodec.encode(room);
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
				throw new IOException("Filesystem does not support atomic room saves", exception);
			}
			moved = true;
		} finally {
			if (!moved) {
				Files.deleteIfExists(temporary);
			}
		}
	}

	public static void saveAtomicIn(Path libraryDirectory, VirtualRoom room) throws IOException {
		saveAtomic(pathFor(libraryDirectory, room.id()), room);
	}

	private static Path requireExtension(Path path) {
		Objects.requireNonNull(path, "path");
		Path fileName = path.getFileName();
		if (fileName == null || !fileName.toString().endsWith(EXTENSION)) {
			throw new IllegalArgumentException("Room archive must use the " + EXTENSION + " extension");
		}
		return path;
	}
}
