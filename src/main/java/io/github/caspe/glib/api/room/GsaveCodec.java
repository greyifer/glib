package io.github.caspe.glib.api.room;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Deterministic codec for the portable G'Lib room archive format.
 *
 * <p>The envelope contains a format version, fixed raw-DEFLATE payload, declared
 * uncompressed length, and SHA-256 of the canonical uncompressed payload.</p>
 */
public final class GsaveCodec {
	public static final int FORMAT_VERSION = 1;
	public static final int MAX_ARCHIVE_BYTES = 64 * 1024 * 1024;
	public static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;

	private static final byte[] MAGIC = {'G', 'S', 'A', 'V'};
	private static final int COMPRESSION_DEFLATE = 1;
	private static final int SHA_256_BYTES = 32;

	private GsaveCodec() {
	}

	public static byte[] encode(VirtualRoom room) throws IOException {
		byte[] payload = encodePayload(room);
		if (payload.length > MAX_PAYLOAD_BYTES) {
			throw new GsaveException("Room payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
		}
		byte[] compressed = deflate(payload);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(compressed.length + 64);
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			output.write(MAGIC);
			output.writeInt(FORMAT_VERSION);
			output.writeByte(COMPRESSION_DEFLATE);
			output.writeInt(payload.length);
			output.write(sha256(payload));
			output.writeInt(compressed.length);
			output.write(compressed);
		}
		byte[] archive = bytes.toByteArray();
		if (archive.length > MAX_ARCHIVE_BYTES) {
			throw new GsaveException("Room archive exceeds " + MAX_ARCHIVE_BYTES + " bytes");
		}
		return archive;
	}

	public static VirtualRoom decode(byte[] archive) throws IOException {
		if (archive.length > MAX_ARCHIVE_BYTES) {
			throw new GsaveException("Room archive exceeds " + MAX_ARCHIVE_BYTES + " bytes");
		}
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(archive))) {
			byte[] magic = input.readNBytes(MAGIC.length);
			if (!Arrays.equals(MAGIC, magic)) {
				throw new GsaveException("Not a G'Lib room archive");
			}
			int version = input.readInt();
			if (version != FORMAT_VERSION) {
				throw new GsaveException("Unsupported .gsave format version: " + version);
			}
			int compression = input.readUnsignedByte();
			if (compression != COMPRESSION_DEFLATE) {
				throw new GsaveException("Unsupported .gsave compression method: " + compression);
			}
			int payloadLength = checkedCount(input.readInt(), MAX_PAYLOAD_BYTES, "payload length");
			byte[] expectedHash = readExactly(input, SHA_256_BYTES, "payload checksum");
			int compressedLength = checkedCount(input.readInt(), MAX_ARCHIVE_BYTES, "compressed length");
			byte[] compressed = readExactly(input, compressedLength, "compressed payload");
			if (input.read() != -1) {
				throw new GsaveException("Unexpected trailing data in .gsave archive");
			}

			byte[] payload = inflate(compressed, payloadLength);
			if (!MessageDigest.isEqual(expectedHash, sha256(payload))) {
				throw new GsaveException("Room payload checksum does not match");
			}
			return decodePayload(payload);
		} catch (EOFException exception) {
			throw new GsaveException("Truncated .gsave archive", exception);
		} catch (IllegalArgumentException exception) {
			throw new GsaveException("Invalid room data: " + exception.getMessage(), exception);
		}
	}

	private static byte[] encodePayload(VirtualRoom room) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			writeString(output, room.id().value());
			writeString(output, room.displayName());
			output.writeInt(room.dimensions().width());
			output.writeInt(room.dimensions().height());
			output.writeInt(room.dimensions().depth());
			writeMap(output, room.metadata());

			output.writeInt(room.blocks().size());
			for (RoomBlock block : room.blocks()) {
				writePosition(output, block.position());
				writeString(output, block.blockId());
				writeMap(output, block.properties());
				byte[] nbt = block.blockEntityNbt().orElse(null);
				if (nbt == null) {
					output.writeInt(-1);
				} else {
					output.writeInt(nbt.length);
					output.write(nbt);
				}
			}

			output.writeInt(room.markers().size());
			for (RoomMarker marker : room.markers()) {
				writeString(output, marker.id());
				output.writeByte(marker.kind().wireCode());
				writePosition(output, marker.position());
				output.writeFloat(marker.yaw());
				output.writeFloat(marker.pitch());
				writeMap(output, marker.metadata());
			}
		}
		return bytes.toByteArray();
	}

	private static VirtualRoom decodePayload(byte[] payload) throws IOException {
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
			RoomId id = new RoomId(readString(input));
			String displayName = readString(input);
			RoomDimensions dimensions =
					new RoomDimensions(input.readInt(), input.readInt(), input.readInt());
			Map<String, String> metadata = readMap(input, RoomValidation.MAX_METADATA_ENTRIES);

			int blockCount = checkedCount(input.readInt(), RoomValidation.MAX_BLOCKS, "block count");
			List<RoomBlock> blocks = new ArrayList<>(blockCount);
			for (int index = 0; index < blockCount; index++) {
				RoomPosition position = readPosition(input);
				String blockId = readString(input);
				Map<String, String> properties = readMap(input, RoomValidation.MAX_BLOCK_PROPERTIES);
				int nbtLength = input.readInt();
				byte[] nbt;
				if (nbtLength == -1) {
					nbt = null;
				} else {
					checkedCount(nbtLength, RoomValidation.MAX_BLOCK_ENTITY_BYTES, "block-entity NBT length");
					nbt = readExactly(input, nbtLength, "block-entity NBT");
				}
				blocks.add(new RoomBlock(position, blockId, properties, nbt));
			}

			int markerCount = checkedCount(input.readInt(), RoomValidation.MAX_MARKERS, "marker count");
			List<RoomMarker> markers = new ArrayList<>(markerCount);
			for (int index = 0; index < markerCount; index++) {
				String markerId = readString(input);
				int kind = input.readUnsignedByte();
				RoomPosition position = readPosition(input);
				float yaw = input.readFloat();
				float pitch = input.readFloat();
				Map<String, String> markerMetadata =
						readMap(input, RoomValidation.MAX_METADATA_ENTRIES);
				markers.add(new RoomMarker(
						markerId,
						RoomMarker.Kind.fromWireCode(kind),
						position,
						yaw,
						pitch,
						markerMetadata));
			}
			if (input.read() != -1) {
				throw new GsaveException("Unexpected trailing data in room payload");
			}
			return new VirtualRoom(id, displayName, dimensions, metadata, blocks, markers);
		} catch (EOFException exception) {
			throw new GsaveException("Truncated room payload", exception);
		}
	}

	private static void writePosition(DataOutputStream output, RoomPosition position) throws IOException {
		output.writeInt(position.x());
		output.writeInt(position.y());
		output.writeInt(position.z());
	}

	private static RoomPosition readPosition(DataInputStream input) throws IOException {
		return new RoomPosition(input.readInt(), input.readInt(), input.readInt());
	}

	private static void writeMap(DataOutputStream output, Map<String, String> map) throws IOException {
		output.writeInt(map.size());
		for (Map.Entry<String, String> entry : map.entrySet()) {
			writeString(output, entry.getKey());
			writeString(output, entry.getValue());
		}
	}

	private static Map<String, String> readMap(DataInputStream input, int maximumEntries) throws IOException {
		int count = checkedCount(input.readInt(), maximumEntries, "map entry count");
		Map<String, String> result = new LinkedHashMap<>(count);
		for (int index = 0; index < count; index++) {
			String key = readString(input);
			String value = readString(input);
			if (result.put(key, value) != null) {
				throw new GsaveException("Duplicate map key: " + key);
			}
		}
		return result;
	}

	private static void writeString(DataOutputStream output, String value) throws IOException {
		byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
		if (encoded.length > RoomValidation.MAX_STRING_BYTES) {
			throw new GsaveException("Encoded string exceeds size limit");
		}
		output.writeInt(encoded.length);
		output.write(encoded);
	}

	private static String readString(DataInputStream input) throws IOException {
		int length = checkedCount(input.readInt(), RoomValidation.MAX_STRING_BYTES, "string length");
		byte[] encoded = readExactly(input, length, "string");
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(encoded))
					.toString();
		} catch (CharacterCodingException exception) {
			throw new GsaveException("String is not valid UTF-8", exception);
		}
	}

	private static int checkedCount(int value, int maximum, String label) throws GsaveException {
		if (value < 0 || value > maximum) {
			throw new GsaveException("Invalid " + label + ": " + value);
		}
		return value;
	}

	private static byte[] readExactly(DataInputStream input, int length, String label) throws IOException {
		byte[] value = input.readNBytes(length);
		if (value.length != length) {
			throw new GsaveException("Truncated " + label);
		}
		return value;
	}

	private static byte[] deflate(byte[] payload) {
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
		deflater.setInput(payload);
		deflater.finish();
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		while (!deflater.finished()) {
			int written = deflater.deflate(buffer);
			compressed.write(buffer, 0, written);
		}
		deflater.end();
		return compressed.toByteArray();
	}

	private static byte[] inflate(byte[] compressed, int expectedLength) throws GsaveException {
		Inflater inflater = new Inflater(true);
		inflater.setInput(compressed);
		ByteArrayOutputStream payload =
				new ByteArrayOutputStream(Math.min(expectedLength, 8192));
		byte[] buffer = new byte[8192];
		try {
			while (!inflater.finished()) {
				int written = inflater.inflate(buffer);
				if (written == 0) {
					if (inflater.needsDictionary()) {
						throw new GsaveException("Compressed payload requires an unsupported dictionary");
					}
					if (inflater.needsInput()) {
						break;
					}
					throw new GsaveException("Compressed payload made no decoding progress");
				}
				if (payload.size() > expectedLength - written) {
					throw new GsaveException("Compressed payload is larger than its declaration");
				}
				payload.write(buffer, 0, written);
			}
			if (!inflater.finished() || payload.size() != expectedLength || inflater.getRemaining() != 0) {
				throw new GsaveException("Compressed payload length does not match its declaration");
			}
			return payload.toByteArray();
		} catch (DataFormatException exception) {
			throw new GsaveException("Invalid compressed room payload", exception);
		} finally {
			inflater.end();
		}
	}

	private static byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("Java runtime does not provide SHA-256", exception);
		}
	}
}
