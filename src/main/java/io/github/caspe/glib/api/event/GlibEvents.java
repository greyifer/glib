package io.github.caspe.glib.api.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class GlibEvents {
	private static final Map<String, List<Consumer<Object>>> LISTENERS = new ConcurrentHashMap<>();

	private GlibEvents() {
	}

	public static Subscription on(String event, Consumer<Object> listener) {
		if (event == null || event.isBlank()) {
			throw new IllegalArgumentException("Event name cannot be blank");
		}
		LISTENERS.computeIfAbsent(event, ignored -> new CopyOnWriteArrayList<>()).add(listener);
		return () -> LISTENERS.getOrDefault(event, List.of()).remove(listener);
	}

	public static <T> Subscription on(String event, Class<T> type, Consumer<T> listener) {
		return on(event, payload -> {
			if (type.isInstance(payload)) {
				listener.accept(type.cast(payload));
			}
		});
	}

	public static void emit(String event) {
		emit(event, null);
	}

	public static void emit(String event, Object payload) {
		for (Consumer<Object> listener : LISTENERS.getOrDefault(event, List.of())) {
			listener.accept(payload);
		}
	}

	public static int listenerCount(String event) {
		return LISTENERS.getOrDefault(event, List.of()).size();
	}

	public static void clear(String event) {
		if (event != null) {
			LISTENERS.remove(event);
		}
	}

	public static void clearAll() {
		LISTENERS.clear();
	}

	@FunctionalInterface
	public interface Subscription extends AutoCloseable {
		void unsubscribe();

		@Override
		default void close() {
			unsubscribe();
		}
	}
}
