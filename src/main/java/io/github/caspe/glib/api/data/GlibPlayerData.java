package io.github.caspe.glib.api.data;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

public final class GlibPlayerData {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<Path, Map<UUID, PlayerData>> CACHE = new ConcurrentHashMap<>();

	private GlibPlayerData() {
	}

	public static PlayerData get(MinecraftServer server, UUID uuid) {
		Path root = root(server);
		return CACHE.computeIfAbsent(root, ignored -> new ConcurrentHashMap<>())
			.computeIfAbsent(uuid, id -> load(root, id));
	}

	public static void saveAll(MinecraftServer server) {
		Path root = root(server);
		for (PlayerData data : CACHE.getOrDefault(root, Map.of()).values()) {
			data.save();
		}
	}

	private static PlayerData load(Path root, UUID uuid) {
		Path path = root.resolve(uuid + ".json");
		JsonObject values = new JsonObject();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				JsonElement element = JsonParser.parseReader(reader);
				if (element != null && element.isJsonObject()) {
					values = element.getAsJsonObject();
				}
			} catch (IOException ignored) {
				values = new JsonObject();
			}
		}
		return new PlayerData(path, values);
	}

	private static Path root(MinecraftServer server) {
		return server.getSavePath(WorldSavePath.ROOT).resolve("glib").resolve("player_data");
	}

	public static final class PlayerData {
		private final Path path;
		private final JsonObject values;

		private PlayerData(Path path, JsonObject values) {
			this.path = path;
			this.values = values;
		}

		public String getString(String key, String fallback) {
			JsonElement element = values.get(key);
			return element == null || element.isJsonNull() ? fallback : element.getAsString();
		}

		public int getInt(String key, int fallback) {
			JsonElement element = values.get(key);
			return element == null || element.isJsonNull() ? fallback : element.getAsInt();
		}

		public long getLong(String key, long fallback) {
			JsonElement element = values.get(key);
			return element == null || element.isJsonNull() ? fallback : element.getAsLong();
		}

		public boolean getBoolean(String key, boolean fallback) {
			JsonElement element = values.get(key);
			return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
		}

		public void setString(String key, String value) {
			values.addProperty(key, value);
		}

		public void setInt(String key, int value) {
			values.addProperty(key, value);
		}

		public void setLong(String key, long value) {
			values.addProperty(key, value);
		}

		public void setBoolean(String key, boolean value) {
			values.addProperty(key, value);
		}

		public void addInt(String key, int amount) {
			setInt(key, getInt(key, 0) + amount);
		}

		public JsonObject raw() {
			return values;
		}

		public void save() {
			try {
				Files.createDirectories(path.getParent());
				try (Writer writer = Files.newBufferedWriter(path)) {
					GSON.toJson(values, writer);
				}
			} catch (IOException ignored) {
				// Persistence helpers should never crash gameplay.
			}
		}
	}
}
