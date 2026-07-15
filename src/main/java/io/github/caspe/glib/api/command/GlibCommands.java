package io.github.caspe.glib.api.command;

import java.util.function.Consumer;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public final class GlibCommands {
	private GlibCommands() {
	}

	public static void register(String root, int permissionLevel, Consumer<LiteralArgumentBuilder<ServerCommandSource>> builder) {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LiteralArgumentBuilder<ServerCommandSource> command = root(root, permissionLevel);
			builder.accept(command);
			dispatcher.register(command);
		});
	}

	public static LiteralArgumentBuilder<ServerCommandSource> root(String name, int permissionLevel) {
		return literal(name).requires(source -> source.hasPermissionLevel(permissionLevel));
	}

	public static LiteralArgumentBuilder<ServerCommandSource> literal(String name) {
		return CommandManager.literal(name);
	}

	public static int feedback(ServerCommandSource source, String message) {
		source.sendFeedback(() -> Text.literal(message), false);
		return 1;
	}

	public static int broadcast(ServerCommandSource source, String message) {
		source.getServer().getPlayerManager().broadcast(Text.literal(message), false);
		return 1;
	}

	public static int error(ServerCommandSource source, String message) {
		source.sendError(Text.literal(message));
		return 0;
	}
}
