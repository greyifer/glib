package io.github.caspe.glib.api.request;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import io.github.caspe.glib.api.event.GlibCooldowns;
import io.github.caspe.glib.network.GlibRequestPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

public final class GlibRequests {
	private static final Map<String, RegisteredHandler> HANDLERS = new ConcurrentHashMap<>();

	private GlibRequests() {
	}

	public static void registerServer(String namespace, String action, int cooldownTicks, Handler handler) {
		HANDLERS.put(key(namespace, action), new RegisteredHandler(Math.max(0, cooldownTicks), Objects.requireNonNull(handler, "handler")));
	}

	public static void handle(MinecraftServer server, ServerPlayerEntity player, GlibRequestPayload payload) {
		RegisteredHandler registered = HANDLERS.get(key(payload.namespace(), payload.action()));
		if (registered == null) {
			return;
		}

		String cooldownKey = "request:" + payload.namespace() + ":" + payload.action() + ":" + player.getUuid();
		if (registered.cooldownTicks() > 0 && !GlibCooldowns.tryUse(cooldownKey, player.getServerWorld(), registered.cooldownTicks())) {
			return;
		}
		registered.handler().handle(new Context(server, player, payload.namespace(), payload.action(), payload.values()));
	}

	private static String key(String namespace, String action) {
		return namespace + ":" + action;
	}

	@FunctionalInterface
	public interface Handler {
		void handle(Context context);
	}

	public record Context(MinecraftServer server, ServerPlayerEntity player, String namespace, String action, Map<String, String> values) {
		public String value(String key, String fallback) {
			return values.getOrDefault(key, fallback);
		}

		public int intValue(String key, int fallback) {
			try {
				return Integer.parseInt(value(key, Integer.toString(fallback)));
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}
	}

	private record RegisteredHandler(int cooldownTicks, Handler handler) {
	}
}
