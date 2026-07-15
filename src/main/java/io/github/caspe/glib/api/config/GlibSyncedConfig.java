package io.github.caspe.glib.api.config;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import io.github.caspe.glib.network.ConfigSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

public final class GlibSyncedConfig {
	private static final Map<String, Map<String, String>> VALUES = new ConcurrentHashMap<>();

	private GlibSyncedConfig() {
	}

	public static void set(String namespace, String key, String value) {
		values(namespace).put(cleanKey(key), value == null ? "" : value);
	}

	public static void set(String namespace, String key, int value) {
		set(namespace, key, Integer.toString(value));
	}

	public static void set(String namespace, String key, boolean value) {
		set(namespace, key, Boolean.toString(value));
	}

	public static String get(String namespace, String key, String fallback) {
		return values(namespace).getOrDefault(cleanKey(key), fallback);
	}

	public static int getInt(String namespace, String key, int fallback) {
		try {
			return Integer.parseInt(get(namespace, key, Integer.toString(fallback)));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public static boolean getBoolean(String namespace, String key, boolean fallback) {
		return Boolean.parseBoolean(get(namespace, key, Boolean.toString(fallback)));
	}

	public static Map<String, String> snapshot(String namespace) {
		return Map.copyOf(values(namespace));
	}

	public static void broadcast(MinecraftServer server, String namespace) {
		Objects.requireNonNull(server, "server");
		ConfigSyncPayload payload = new ConfigSyncPayload(cleanNamespace(namespace), snapshot(namespace));
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			send(player, payload);
		}
	}

	public static void send(ServerPlayerEntity target, String namespace) {
		send(target, new ConfigSyncPayload(cleanNamespace(namespace), snapshot(namespace)));
	}

	public static void applyClientValues(String namespace, Map<String, String> values) {
		VALUES.put(cleanNamespace(namespace), new ConcurrentHashMap<>(values));
	}

	private static void send(ServerPlayerEntity target, ConfigSyncPayload payload) {
		if (ServerPlayNetworking.canSend(target, ConfigSyncPayload.ID)) {
			ServerPlayNetworking.send(target, payload);
		}
	}

	private static Map<String, String> values(String namespace) {
		return VALUES.computeIfAbsent(cleanNamespace(namespace), ignored -> new ConcurrentHashMap<>());
	}

	private static String cleanNamespace(String namespace) {
		if (namespace == null || namespace.isBlank()) {
			throw new IllegalArgumentException("Config namespace cannot be blank");
		}
		return namespace;
	}

	private static String cleanKey(String key) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("Config key cannot be blank");
		}
		return key;
	}
}
