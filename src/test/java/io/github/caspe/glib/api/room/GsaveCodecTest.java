package io.github.caspe.glib.api.room;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GsaveCodecTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void canonicalArchiveIsDeterministicAndRoundTrips() throws IOException {
		VirtualRoom room = exampleRoom();

		byte[] first = GsaveCodec.encode(room);
		byte[] second = GsaveCodec.encode(room);

		assertArrayEquals(first, second);
		assertEquals(room, GsaveCodec.decode(first));
	}

	@Test
	void mapInsertionOrderDoesNotAffectArchive() throws IOException {
		Map<String, String> forward = new LinkedHashMap<>();
		forward.put("facing", "north");
		forward.put("waterlogged", "false");
		Map<String, String> reverse = new LinkedHashMap<>();
		reverse.put("waterlogged", "false");
		reverse.put("facing", "north");

		VirtualRoom first = roomWithProperties(forward);
		VirtualRoom second = roomWithProperties(reverse);

		assertArrayEquals(GsaveCodec.encode(first), GsaveCodec.encode(second));
	}

	@Test
	void corruptedArchiveIsRejected() throws IOException {
		byte[] archive = GsaveCodec.encode(exampleRoom());
		archive[13] ^= 0x40; // First byte of the declared SHA-256 payload checksum.
		assertThrows(GsaveException.class, () -> GsaveCodec.decode(archive));
	}

	@Test
	void declaredHugePayloadDoesNotRequireADeclaredSizeAllocation() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			output.write(new byte[] {'G', 'S', 'A', 'V'});
			output.writeInt(GsaveCodec.FORMAT_VERSION);
			output.writeByte(1);
			output.writeInt(GsaveCodec.MAX_PAYLOAD_BYTES);
			output.write(new byte[32]);
			output.writeInt(2);
			output.write(new byte[] {3, 0}); // A valid raw-DEFLATE stream for an empty payload.
		}

		assertThrows(GsaveException.class, () -> GsaveCodec.decode(bytes.toByteArray()));
	}

	@Test
	void blockPropertyLimitIsSymmetricAcrossModelAndCodec() throws IOException {
		Map<String, String> maximum = new LinkedHashMap<>();
		for (int index = 0; index < RoomValidation.MAX_BLOCK_PROPERTIES; index++) {
			maximum.put("property_" + index, "value");
		}
		RoomBlock accepted =
				new RoomBlock(new RoomPosition(0, 0, 0), "minecraft:stone", maximum);
		VirtualRoom room = VirtualRoom.builder(
					RoomId.of("property-limit"), "Property limit", new RoomDimensions(1, 1, 1))
				.block(accepted)
				.build();
		assertEquals(room, GsaveCodec.decode(GsaveCodec.encode(room)));

		maximum.put("one_too_many", "value");
		assertThrows(
				IllegalArgumentException.class,
				() -> new RoomBlock(new RoomPosition(0, 0, 0), "minecraft:stone", maximum));
	}

	@Test
	void markerKindsHaveStableWireCodes() {
		assertEquals(0, RoomMarker.Kind.SPAWN.wireCode());
		assertEquals(1, RoomMarker.Kind.HOTSPOT.wireCode());
		assertEquals(2, RoomMarker.Kind.CUSTOM.wireCode());
		assertEquals(RoomMarker.Kind.SPAWN, RoomMarker.Kind.fromWireCode(0));
		assertThrows(IllegalArgumentException.class, () -> RoomMarker.Kind.fromWireCode(3));
	}

	@Test
	void atomicFileHelpersSaveLoadAndReplace() throws IOException {
		VirtualRoom first = exampleRoom();
		Path target = GsaveFiles.pathFor(temporaryDirectory.resolve("library"), first.id());
		GsaveFiles.saveAtomic(target, first);
		assertEquals(first, GsaveFiles.load(target));

		VirtualRoom replacement = VirtualRoom.builder(
						first.id(), "Replaced", new RoomDimensions(1, 1, 1))
				.build();
		GsaveFiles.saveAtomicIn(temporaryDirectory.resolve("library"), replacement);

		assertEquals(replacement, GsaveFiles.load(target));
		assertEquals(List.of("demo-room.gsave"), Files.list(target.getParent())
				.map(path -> path.getFileName().toString())
				.sorted()
				.toList());
	}

	private static VirtualRoom exampleRoom() {
		byte[] nbt = {10, 0, 0, 0};
		return new VirtualRoom(
				RoomId.of("demo-room"),
				"Demo Room",
				new RoomDimensions(4, 3, 2),
				Map.of("author", "G'Lib", "theme", "test"),
				List.of(
						new RoomBlock(
								new RoomPosition(1, 0, 0),
								"minecraft:oak_stairs",
								Map.of("facing", "north", "waterlogged", "false"),
								null),
						new RoomBlock(
								new RoomPosition(0, 0, 0),
								"minecraft:chest",
								Map.of("facing", "south"),
								nbt)),
				List.of(new RoomMarker(
						"entry",
						RoomMarker.Kind.SPAWN,
						new RoomPosition(2, 1, 1),
						270.0F,
						10.0F,
						Map.of("team", "all"))));
	}

	private static VirtualRoom roomWithProperties(Map<String, String> properties) {
		return new VirtualRoom(
				RoomId.of("ordering"),
				"Ordering",
				new RoomDimensions(1, 1, 1),
				Map.of(),
				List.of(new RoomBlock(
						new RoomPosition(0, 0, 0),
						"minecraft:oak_stairs",
						properties)),
				List.of());
	}
}
