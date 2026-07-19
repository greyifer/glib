package io.github.caspe.glib.client.social;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public final class GlibNetworkConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("glib-network.json");
	private static volatile Settings settings = load();

	private GlibNetworkConfig() {
	}

	public static Settings get() {
		return settings;
	}

	public static boolean configured() {
		String host = settings.apiUri().getHost();
		return host != null && !host.toLowerCase(Locale.ROOT).contains("change-me");
	}

	public static synchronized void save(String apiUrl) throws IOException {
		Settings updated = validated(apiUrl);
		Files.createDirectories(PATH.getParent());
		try (Writer writer = Files.newBufferedWriter(PATH)) {
			GSON.toJson(new StoredSettings(updated.apiUrl()), writer);
		}
		settings = updated;
	}

	public static Settings validated(String rawUrl) {
		String value = rawUrl == null ? "" : rawUrl.trim();
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		URI uri;
		try {
			uri = URI.create(value);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Enter a valid server URL.");
		}
		String scheme = uri.getScheme();
		String host = uri.getHost();
		if (scheme == null || host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
			throw new IllegalArgumentException("Enter a full server URL without a path, query, or password.");
		}
		boolean local = host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1");
		if (!scheme.equalsIgnoreCase("https") && !(local && scheme.equalsIgnoreCase("http"))) {
			throw new IllegalArgumentException("Use HTTPS. Plain HTTP is allowed only for localhost.");
		}
		String path = uri.getPath();
		if (path != null && !path.isEmpty() && !path.equals("/")) {
			throw new IllegalArgumentException("The server URL must not include a path.");
		}
		return new Settings(value, uri);
	}

	private static Settings load() {
		String override = System.getProperty("glib.network.apiUrl");
		if (override == null || override.isBlank()) {
			override = System.getenv("GLIB_NETWORK_URL");
		}
		if (override != null && !override.isBlank()) {
			return validated(override);
		}
		if (Files.isRegularFile(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				StoredSettings stored = GSON.fromJson(reader, StoredSettings.class);
				if (stored != null) return validated(stored.apiUrl());
			} catch (IOException | JsonParseException | IllegalArgumentException ignored) {
			}
		}
		Properties defaults = new Properties();
		try (var stream = GlibNetworkConfig.class.getResourceAsStream("/glib-network.properties")) {
			if (stream != null) defaults.load(stream);
		} catch (IOException ignored) {
		}
		return validated(defaults.getProperty("apiUrl", "http://127.0.0.1:8080"));
	}

	private record StoredSettings(String apiUrl) {
	}

	public record Settings(String apiUrl, URI apiUri) {
		public Settings(String apiUrl) {
			this(apiUrl, URI.create(apiUrl));
		}
	}
}
