package io.github.caspe.glib.api.room;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlibRoomLibraryTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void atomicallySavedRoomsAppearAndCanBeRemoved() throws Exception {
		GlibRoomLibrary library = new GlibRoomLibrary(temporaryDirectory.resolve("rooms"));
		VirtualRoom room = room("library-test", "First");

		GlibRoomLibrary.ChangeSet saved = library.save(room);
		assertEquals(List.of(room), library.rooms());
		assertTrue(saved.updated().contains(room.id()));

		Path file = GsaveFiles.pathFor(library.directory(), room.id());
		Files.delete(file);
		assertTrue(library.refresh().removed().isEmpty());
		assertTrue(library.refresh().removed().isEmpty());
		GlibRoomLibrary.ChangeSet removed = library.refresh();
		assertTrue(removed.removed().contains(room.id()));
		assertTrue(library.rooms().isEmpty());
	}

	@Test
	void detectsSameSizeReplacementWithPreservedTimestamp() throws Exception {
		GlibRoomLibrary library = new GlibRoomLibrary(temporaryDirectory.resolve("rooms"));
		Map<Integer, VirtualRoom> roomsByArchiveSize = new HashMap<>();
		VirtualRoom original = null;
		VirtualRoom replacement = null;
		byte[] replacementBytes = null;
		for (int index = 1; index < 10_000; index++) {
			VirtualRoom candidate = room(
				"digest-room", String.format("Room%08x", index * 0x9E3779B9));
			byte[] encoded = GsaveCodec.encode(candidate);
			VirtualRoom sameSize = roomsByArchiveSize.putIfAbsent(encoded.length, candidate);
			if (sameSize != null && !sameSize.equals(candidate)) {
				original = sameSize;
				replacement = candidate;
				replacementBytes = encoded;
				break;
			}
		}
		assertTrue(replacement != null, "Expected to find equal-size encoded rooms");
		library.save(original);
		Path file = GsaveFiles.pathFor(library.directory(), original.id());
		FileTime originalTime = Files.getLastModifiedTime(file);
		long originalSize = Files.size(file);
		assertEquals(originalSize, replacementBytes.length);
		Files.write(file, replacementBytes, StandardOpenOption.TRUNCATE_EXISTING);
		Files.setLastModifiedTime(file, originalTime);

		GlibRoomLibrary.ChangeSet changes = library.refresh();
		assertTrue(changes.updated().contains(original.id()));
		assertEquals(replacement, library.find(original.id()).orElseThrow());
	}

	@Test
	void oneFailingListenerDoesNotBlockOtherListenersOrSave() throws Exception {
		GlibRoomLibrary library = new GlibRoomLibrary(temporaryDirectory.resolve("rooms"));
		AtomicBoolean secondListenerCalled = new AtomicBoolean();
		library.listen(changes -> {
			throw new IllegalStateException("listener failed");
		});
		library.listen(changes -> secondListenerCalled.set(true));

		GlibRoomLibrary.ChangeSet changes = library.save(room("listeners", "Listeners"));

		assertTrue(changes.updated().contains(RoomId.of("listeners")));
		assertTrue(secondListenerCalled.get());
	}

	@Test
	void invalidPartialEditKeepsLastValidRoomUntilReplacementArrives() throws Exception {
		GlibRoomLibrary library = new GlibRoomLibrary(temporaryDirectory.resolve("rooms"));
		VirtualRoom original = room("live-room", "Original");
		library.save(original);
		Path file = GsaveFiles.pathFor(library.directory(), original.id());

		Files.write(
			file,
			new byte[] {'B', 'A', 'D'},
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE);
		GlibRoomLibrary.ChangeSet invalid = library.refresh();
		assertFalse(invalid.errors().isEmpty());
		assertEquals("Original", library.find(original.id()).orElseThrow().displayName());

		VirtualRoom replacement = room("live-room", "Replacement");
		GsaveFiles.saveAtomic(file, replacement);
		GlibRoomLibrary.ChangeSet changed = library.refresh();
		assertTrue(changed.updated().contains(replacement.id()));
		assertEquals("Replacement", library.find(replacement.id()).orElseThrow().displayName());
	}

	private static VirtualRoom room(String id, String name) {
		return VirtualRoom.builder(
				RoomId.of(id),
				name,
				new RoomDimensions(3, 3, 3))
			.block(RoomBlock.of(new RoomPosition(1, 0, 1), "minecraft:stone"))
			.marker(new RoomMarker(
				"default", RoomMarker.Kind.SPAWN, new RoomPosition(1, 1, 1)))
			.build();
	}
}
