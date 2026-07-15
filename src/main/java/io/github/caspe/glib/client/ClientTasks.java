package io.github.caspe.glib.client;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

@Environment(EnvType.CLIENT)
public final class ClientTasks {
	private ClientTasks() {
	}

	public static CompletableFuture<Void> run(Runnable runnable) {
		return supply(() -> {
			runnable.run();
			return null;
		});
	}

	public static <T> CompletableFuture<T> supply(Supplier<T> supplier) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client.isOnThread()) {
			return completedFrom(supplier);
		}

		CompletableFuture<T> future = new CompletableFuture<>();
		client.execute(() -> {
			try {
				future.complete(supplier.get());
			} catch (Throwable throwable) {
				future.completeExceptionally(throwable);
			}
		});
		return future;
	}

	private static <T> CompletableFuture<T> completedFrom(Supplier<T> supplier) {
		try {
			return CompletableFuture.completedFuture(supplier.get());
		} catch (Throwable throwable) {
			return CompletableFuture.failedFuture(throwable);
		}
	}
}
