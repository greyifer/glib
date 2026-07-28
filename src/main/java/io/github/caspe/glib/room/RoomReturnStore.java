package io.github.caspe.glib.room;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Crash-recovery journal for players moved into packet-only room host volumes.
 */
final class RoomReturnStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("G'Lib Virtual Room Returns");
	static final int MAX_ENTRIES = 4_096;
	static final long MAX_FILE_BYTES = 1024L * 1024L;
	private final Path file;
	private final int maxEntries;
	private final long maxFileBytes;
	private final Map<UUID, RoomSessionState.ReturnPoint> returns = new LinkedHashMap<>();

	private RoomReturnStore(Path file, int maxEntries, long maxFileBytes) {
		this.file = file.toAbsolutePath().normalize();
		if (maxEntries < 1 || maxFileBytes < 1L) {
			throw new IllegalArgumentException("Virtual-room return journal bounds must be positive");
		}
		this.maxEntries = maxEntries;
		this.maxFileBytes = maxFileBytes;
	}

	static RoomReturnStore open(Path file) throws IOException {
		return open(file, MAX_ENTRIES, MAX_FILE_BYTES);
	}

	static RoomReturnStore open(Path file, int maxEntries, long maxFileBytes) throws IOException {
		RoomReturnStore store = new RoomReturnStore(file, maxEntries, maxFileBytes);
		store.load();
		return store;
	}

	synchronized Optional<RoomSessionState.ReturnPoint> find(UUID playerId) {
		return Optional.ofNullable(returns.get(playerId));
	}

	synchronized void put(UUID playerId, RoomSessionState.ReturnPoint returnPoint) throws IOException {
		if (!returns.containsKey(playerId) && returns.size() >= maxEntries) {
			throw new IOException(
				"Virtual-room return journal already contains its maximum of "
					+ maxEntries + " recovery records");
		}
		RoomSessionState.ReturnPoint previous = returns.put(playerId, returnPoint);
		try {
			write();
		} catch (IOException exception) {
			if (previous == null) {
				returns.remove(playerId);
			} else {
				returns.put(playerId, previous);
			}
			throw exception;
		}
	}

	synchronized boolean clear(UUID playerId) throws IOException {
		RoomSessionState.ReturnPoint previous = returns.remove(playerId);
		if (previous == null) {
			return false;
		}
		try {
			write();
			return true;
		} catch (IOException exception) {
			returns.put(playerId, previous);
			throw exception;
		}
	}

	private void load() throws IOException {
		if (!Files.exists(file)) {
			return;
		}
		if (!Files.isRegularFile(file) || Files.size(file) > maxFileBytes) {
			throw new IOException("Virtual-room return journal is not a bounded regular file: " + file);
		}
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ)) {
			properties.load(input);
		}
		for (String key : properties.stringPropertyNames()) {
			try {
				UUID playerId = UUID.fromString(key);
				if (!returns.containsKey(playerId) && returns.size() >= maxEntries) {
					throw new IOException(
						"Virtual-room return journal contains more than "
							+ maxEntries + " recovery records");
				}
				returns.put(playerId, decode(properties.getProperty(key)));
			} catch (IllegalArgumentException exception) {
				LOGGER.warn("Ignoring invalid virtual-room return record for {}", key);
			}
		}
	}

	private void write() throws IOException {
		Path directory = file.getParent();
		Files.createDirectories(directory);
		Properties properties = new Properties();
		for (Map.Entry<UUID, RoomSessionState.ReturnPoint> entry : returns.entrySet()) {
			properties.setProperty(entry.getKey().toString(), encode(entry.getValue()));
		}
		ByteArrayOutputStream serialized = new ByteArrayOutputStream();
		properties.store(serialized, "G'Lib interrupted virtual-room return points");
		byte[] bytes = serialized.toByteArray();
		if (bytes.length > maxFileBytes) {
			throw new IOException(
				"Virtual-room return journal exceeds its " + maxFileBytes + "-byte limit");
		}
		Path temporary = Files.createTempFile(directory, ".pending-room-returns-", ".tmp");
		try {
			try (OutputStream output = Files.newOutputStream(
					temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				output.write(bytes);
			}
			try {
				Files.move(
					temporary,
					file,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				throw new IOException(
					"Filesystem does not support an atomic virtual-room return journal",
					exception);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static String encode(RoomSessionState.ReturnPoint point) {
		return String.join(
			";",
			point.world().getValue().toString(),
			Double.toString(point.position().x),
			Double.toString(point.position().y),
			Double.toString(point.position().z),
			Float.toString(point.yaw()),
			Float.toString(point.pitch()));
	}

	private static RoomSessionState.ReturnPoint decode(String encoded) {
		String[] parts = encoded.split(";", -1);
		if (parts.length != 6) {
			throw new IllegalArgumentException("wrong field count");
		}
		Identifier worldId = Identifier.tryParse(parts[0]);
		if (worldId == null) {
			throw new IllegalArgumentException("invalid world id");
		}
		double x = Double.parseDouble(parts[1]);
		double y = Double.parseDouble(parts[2]);
		double z = Double.parseDouble(parts[3]);
		float yaw = Float.parseFloat(parts[4]);
		float pitch = Float.parseFloat(parts[5]);
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
				|| !Float.isFinite(yaw) || !Float.isFinite(pitch)
				|| pitch < -90.0F || pitch > 90.0F) {
			throw new IllegalArgumentException("non-finite or invalid coordinates");
		}
		RegistryKey<World> world = RegistryKey.of(RegistryKeys.WORLD, worldId);
		return new RoomSessionState.ReturnPoint(world, new Vec3d(x, y, z), yaw, pitch);
	}
}
