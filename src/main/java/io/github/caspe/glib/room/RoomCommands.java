package io.github.caspe.glib.room;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.caspe.glib.api.room.GlibRoomLibrary;
import io.github.caspe.glib.api.room.RoomCoordinateSystem;
import io.github.caspe.glib.api.room.RoomId;
import io.github.caspe.glib.api.room.RoomMarker;
import io.github.caspe.glib.api.room.RoomPosition;
import io.github.caspe.glib.api.room.RoomSessionInfo;
import io.github.caspe.glib.api.room.VirtualRoom;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class RoomCommands {
	private static final Map<UUID, Selection> SELECTIONS = new LinkedHashMap<>();
	private static final int LIST_PAGE_SIZE = 20;

	private RoomCommands() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(CommandManager.literal("glib")
				.then(CommandManager.literal("room")
					.then(CommandManager.literal("list")
						.executes(context -> list(context, 1))
						.then(CommandManager.argument("page", IntegerArgumentType.integer(1))
							.executes(context -> list(
								context, IntegerArgumentType.getInteger(context, "page")))))
					.then(CommandManager.literal("enter")
						.then(CommandManager.argument("room", StringArgumentType.word())
							.suggests((context, builder) ->
								CommandSource.suggestMatching(roomIds(context), builder))
							.executes(RoomCommands::enter)))
					.then(CommandManager.literal("join")
						.then(CommandManager.argument("session", StringArgumentType.word())
							.executes(RoomCommands::join)))
					.then(CommandManager.literal("invite")
						.then(CommandManager.argument("player", EntityArgumentType.player())
							.executes(RoomCommands::invite)))
					.then(CommandManager.literal("leave")
						.executes(RoomCommands::leave))
					.then(CommandManager.literal("where")
						.executes(RoomCommands::where))
					.then(CommandManager.literal("coords")
						.executes(context -> coords(context, null))
						.then(CommandManager.argument("enabled", BoolArgumentType.bool())
							.executes(context -> coords(
								context, BoolArgumentType.getBool(context, "enabled")))))
					.then(CommandManager.literal("tp")
						.then(CommandManager.literal("marker")
							.then(CommandManager.argument("marker", StringArgumentType.word())
								.suggests((context, builder) ->
									CommandSource.suggestMatching(markerIds(context), builder))
								.executes(RoomCommands::teleportMarker)))
						.then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
							.then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
								.then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
									.executes(RoomCommands::teleportLocal)))))
					.then(editCommands())
					.then(CommandManager.literal("reload")
						.requires(source -> source.hasPermissionLevel(2))
						.executes(RoomCommands::reload))
					.then(CommandManager.literal("validate")
						.requires(source -> source.hasPermissionLevel(2))
						.then(CommandManager.argument("room", StringArgumentType.word())
							.suggests((context, builder) ->
								CommandSource.suggestMatching(roomIds(context), builder))
							.executes(RoomCommands::validate)))
					.then(repositoryCommands())
					.then(CommandManager.literal("sessions")
						.requires(source -> source.hasPermissionLevel(2))
						.executes(RoomCommands::sessions))
					.then(CommandManager.literal("test")
						.requires(source -> source.hasPermissionLevel(2))
						.then(CommandManager.literal("status")
							.executes(RoomCommands::testStatus))
						.then(CommandManager.literal("resend")
							.executes(RoomCommands::testResend))))));
	}

	static void clearSelection(UUID playerId) {
		SELECTIONS.remove(playerId);
	}

	private static LiteralArgumentBuilder<ServerCommandSource> editCommands() {
		return CommandManager.literal("edit")
			.requires(source -> source.hasPermissionLevel(2))
			.then(positionCommand("pos1", true))
			.then(positionCommand("pos2", false))
			.then(CommandManager.literal("marker")
				.then(CommandManager.literal("clear")
					.executes(RoomCommands::clearMarkers))
				.then(CommandManager.literal("add")
					.then(CommandManager.argument("id", StringArgumentType.word())
						.then(markerKind("spawn", RoomMarker.Kind.SPAWN))
						.then(markerKind("hotspot", RoomMarker.Kind.HOTSPOT))
						.then(markerKind("custom", RoomMarker.Kind.CUSTOM)))))
			.then(CommandManager.literal("save")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.executes(context -> save(context,
						StringArgumentType.getString(context, "id"), false))
					.then(CommandManager.argument("name", StringArgumentType.greedyString())
						.executes(context -> save(
							context, StringArgumentType.getString(context, "name"), false)))))
			.then(CommandManager.literal("export")
				.then(CommandManager.argument("id", StringArgumentType.word())
					.executes(context -> save(context,
						StringArgumentType.getString(context, "id"), true))
					.then(CommandManager.argument("name", StringArgumentType.greedyString())
						.executes(context -> save(
							context, StringArgumentType.getString(context, "name"), true)))));
	}

	private static LiteralArgumentBuilder<ServerCommandSource> repositoryCommands() {
		return CommandManager.literal("repo")
			.requires(source -> source.hasPermissionLevel(2))
			.then(CommandManager.literal("status")
				.executes(RoomCommands::repositoryStatus))
			.then(CommandManager.literal("pull")
				.executes(RoomCommands::repositoryPull))
			.then(CommandManager.literal("push")
				.then(CommandManager.argument("room", StringArgumentType.word())
					.suggests((context, builder) ->
						CommandSource.suggestMatching(roomIds(context), builder))
					.executes(RoomCommands::repositoryPush)));
	}

	private static LiteralArgumentBuilder<ServerCommandSource> positionCommand(
			String name, boolean first) {
		return CommandManager.literal(name)
			.executes(context -> setPosition(
				context, BlockPos.ofFloored(context.getSource().getPosition()), first))
			.then(CommandManager.argument("position", BlockPosArgumentType.blockPos())
				.executes(context -> setPosition(
					context, BlockPosArgumentType.getBlockPos(context, "position"), first)));
	}

	private static LiteralArgumentBuilder<ServerCommandSource> markerKind(
			String literal, RoomMarker.Kind kind) {
		return CommandManager.literal(literal)
			.executes(context -> addMarker(
				context, StringArgumentType.getString(context, "id"), kind));
	}

	private static int list(CommandContext<ServerCommandSource> context, int page) {
		List<VirtualRoom> rooms = RoomRuntime.compatibleRooms(context.getSource().getServer());
		if (rooms.isEmpty()) {
			return feedback(context.getSource(), "No compatible virtual rooms are available.");
		}
		int pageCount = (rooms.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE;
		if (page > pageCount) {
			return error(context.getSource(), "Library page must be between 1 and " + pageCount + ".");
		}
		int from = (page - 1) * LIST_PAGE_SIZE;
		int to = Math.min(rooms.size(), from + LIST_PAGE_SIZE);
		context.getSource().sendFeedback(() -> Text.literal(
			"Virtual-room library (page " + page + "/" + pageCount + ", "
				+ rooms.size() + " total):"), false);
		for (VirtualRoom room : rooms.subList(from, to)) {
			context.getSource().sendFeedback(() -> Text.literal(String.format(
				Locale.ROOT,
				"- %s: %s [%dx%dx%d, %d visual blocks, %d markers]",
				room.id(),
				room.displayName(),
				room.dimensions().width(),
				room.dimensions().height(),
				room.dimensions().depth(),
				room.blocks().size(),
				room.markers().size())), false);
		}
		return to - from;
	}

	private static int enter(CommandContext<ServerCommandSource> context) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			RoomSessionInfo session = RoomRuntime.enter(
				player, RoomId.of(StringArgumentType.getString(context, "room")));
			return feedback(context.getSource(),
				"Created room session " + session.sessionId() + ".");
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int join(CommandContext<ServerCommandSource> context) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			UUID sessionId = UUID.fromString(StringArgumentType.getString(context, "session"));
			RoomRuntime.join(player, sessionId, null);
			return 1;
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int invite(CommandContext<ServerCommandSource> context) {
		try {
			ServerPlayerEntity sender = context.getSource().getPlayerOrThrow();
			ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
			RoomSessionInfo session = RoomRuntime.session(sender)
				.orElseThrow(() -> new IOException("You are not in a virtual room."));
			target.sendMessage(Text.literal(
				sender.getName().getString() + " invited you to '" + session.displayName()
					+ "'. Join with /glib room join " + session.sessionId()), false);
			return feedback(
				context.getSource(), "Sent a virtual-room invite to " + target.getName().getString() + ".");
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int leave(CommandContext<ServerCommandSource> context) {
		try {
			return RoomRuntime.leave(context.getSource().getPlayerOrThrow())
				? 1
				: error(context.getSource(), "You are not in a virtual room.");
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int where(CommandContext<ServerCommandSource> context) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			Vec3d position = RoomRuntime.localPosition(player).orElse(null);
			if (position == null) {
				return error(context.getSource(), "You are not in a virtual room.");
			}
			RoomSessionInfo session = RoomRuntime.session(player).orElseThrow();
			return feedback(context.getSource(), String.format(
				Locale.ROOT,
				"%s session %s: local %.3f %.3f %.3f (block %d %d %d)",
				session.roomId(),
				session.sessionId(),
				position.x, position.y, position.z,
				(int) Math.floor(position.x),
				(int) Math.floor(position.y),
				(int) Math.floor(position.z)));
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int coords(
			CommandContext<ServerCommandSource> context, Boolean requested) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			boolean enabled = requested == null ? !RoomRuntime.coordinateHud(player) : requested;
			if (!RoomRuntime.coordinateHud(player, enabled)) {
				return error(context.getSource(), "You are not in a virtual room.");
			}
			return feedback(context.getSource(),
				"Virtual-room coordinate HUD " + (enabled ? "enabled." : "disabled."));
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int teleportLocal(CommandContext<ServerCommandSource> context) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			boolean success = RoomRuntime.teleportLocal(
				player,
				DoubleArgumentType.getDouble(context, "x"),
				DoubleArgumentType.getDouble(context, "y"),
				DoubleArgumentType.getDouble(context, "z"),
				player.getYaw(),
				player.getPitch());
			return success ? 1 : error(context.getSource(),
				"Local position is outside the current room, or you are not in one.");
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int teleportMarker(CommandContext<ServerCommandSource> context) {
		try {
			boolean success = RoomRuntime.teleportMarker(
				context.getSource().getPlayerOrThrow(),
				StringArgumentType.getString(context, "marker"));
			return success ? 1 : error(context.getSource(), "Unknown room marker.");
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int setPosition(
			CommandContext<ServerCommandSource> context, BlockPos position, boolean first) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			Selection selection = SELECTIONS.computeIfAbsent(
				player.getUuid(), ignored -> new Selection(player.getWorld().getRegistryKey()));
			if (!selection.world().equals(player.getWorld().getRegistryKey())) {
				selection = new Selection(player.getWorld().getRegistryKey());
				SELECTIONS.put(player.getUuid(), selection);
			}
			if (first) {
				selection.first(position.toImmutable());
			} else {
				selection.second(position.toImmutable());
			}
			return feedback(context.getSource(),
				(first ? "First" : "Second") + " room corner set to " + position.toShortString() + ".");
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int addMarker(
			CommandContext<ServerCommandSource> context,
			String id,
			RoomMarker.Kind kind) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			Selection selection = selection(player);
			String normalizedId = new RoomMarker(
				id, kind, new RoomPosition(0, 0, 0)).id();
			selection.markers().put(normalizedId, new PendingMarker(
				normalizedId,
				kind,
				player.getBlockPos().toImmutable(),
				player.getYaw(),
				player.getPitch()));
			return feedback(context.getSource(),
				"Saved pending " + kind.name().toLowerCase(Locale.ROOT)
					+ " marker '" + normalizedId + "' at " + player.getBlockPos().toShortString() + ".");
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int clearMarkers(CommandContext<ServerCommandSource> context) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			Selection selection = selection(player);
			int count = selection.markers().size();
			selection.markers().clear();
			return feedback(context.getSource(), "Cleared " + count + " pending room marker(s).");
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int save(
			CommandContext<ServerCommandSource> context,
			String displayName,
			boolean publish) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			Selection selection = selection(player);
			if (selection.first() == null || selection.second() == null) {
				return error(context.getSource(), "Set both corners with /glib room edit pos1 and pos2.");
			}
			if (!selection.world().equals(player.getWorld().getRegistryKey())) {
				return error(context.getSource(), "Return to the selection's world before saving.");
			}
			RoomId id = RoomId.of(StringArgumentType.getString(context, "id"));
			RoomCoordinateSystem coordinates = RoomCoordinateSystem.fromInclusiveCorners(
				selection.first().getX(), selection.first().getY(), selection.first().getZ(),
				selection.second().getX(), selection.second().getY(), selection.second().getZ());
			List<RoomMarker> markers = new ArrayList<>();
			for (PendingMarker pending : selection.markers().values()) {
				RoomPosition local = coordinates.toLocal(
					pending.worldPosition().getX(),
					pending.worldPosition().getY(),
					pending.worldPosition().getZ());
				markers.add(new RoomMarker(
					pending.id(), pending.kind(), local,
					pending.yaw(), pending.pitch(), Map.of()));
			}
			VirtualRoom room = RoomRuntime.capture(
				player,
				selection.first(),
				selection.second(),
				id,
				displayName,
				markers);
			RoomRuntime.save(context.getSource().getServer(), room);
			long activeSessions = RoomRuntime.sessions(context.getSource().getServer()).stream()
				.filter(session -> session.roomId().equals(id))
				.count();
			feedback(context.getSource(), String.format(
				Locale.ROOT,
				"Saved %s (%dx%dx%d, %d blocks) to %s; %d active session(s) now use it.",
				id,
				room.dimensions().width(),
				room.dimensions().height(),
				room.dimensions().depth(),
				room.blocks().size(),
				RoomRuntime.library(context.getSource().getServer()).directory(),
				activeSessions));
			if (publish) {
				if (!RoomRuntime.repositoryConfigured(context.getSource().getServer())) {
					String configurationError =
						RoomRuntime.repositoryConfigurationError(context.getSource().getServer());
					return error(
						context.getSource(),
						"Saved locally, but repository export is unavailable"
							+ (configurationError == null ? "." : ": " + configurationError));
				}
				reportPush(
					context.getSource(),
					id,
					RoomRuntime.pushRepository(context.getSource().getServer(), id));
				return feedback(context.getSource(), "Repository export queued for '" + id + "'.");
			}
			return 1;
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int repositoryStatus(CommandContext<ServerCommandSource> context) {
		if (!RoomRuntime.repositoryConfigured(context.getSource().getServer())) {
			String configurationError =
				RoomRuntime.repositoryConfigurationError(context.getSource().getServer());
			return error(
				context.getSource(),
				configurationError == null
					? "Room repository sync is not configured."
					: "Room repository sync configuration error: " + configurationError);
		}
		RoomRepositorySynchronizer.Status status =
			RoomRuntime.repositoryStatus(context.getSource().getServer()).orElseThrow();
		String lastPull = status.lastSuccessfulPull() == null
			? "never"
			: status.lastSuccessfulPull().toString();
		String lastError = status.lastError() == null ? "none" : status.lastError();
		return feedback(context.getSource(), String.format(
			Locale.ROOT,
			"Repository configured; pulling=%s, last success=%s, remote=%d, installed last pull=%d, last error=%s",
			status.pulling(),
			lastPull,
			status.remoteRooms(),
			status.installedRooms(),
			lastError));
	}

	private static int repositoryPull(CommandContext<ServerCommandSource> context) {
		try {
			ServerCommandSource source = context.getSource();
			RoomRuntime.pullRepository(source.getServer()).whenComplete((result, error) ->
				source.getServer().execute(() -> {
					if (error != null) {
						source.sendError(Text.literal(
							"Room repository pull failed: " + message(error)));
						return;
					}
					source.sendFeedback(() -> Text.literal(String.format(
						Locale.ROOT,
						"Repository pull complete: %d installed, %d removed, %d unchanged, %d rejected.",
						result.installed(),
						result.removed(),
						result.unchanged(),
						result.rejected().size())), false);
					for (String rejected : result.rejected()) {
						source.sendError(Text.literal("Rejected " + rejected));
					}
				}));
			return feedback(source, "Room repository pull queued.");
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int repositoryPush(CommandContext<ServerCommandSource> context) {
		try {
			RoomId id = RoomId.of(StringArgumentType.getString(context, "room"));
			reportPush(
				context.getSource(),
				id,
				RoomRuntime.pushRepository(context.getSource().getServer(), id));
			return feedback(context.getSource(), "Repository export queued for '" + id + "'.");
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static void reportPush(
			ServerCommandSource source,
			RoomId id,
			java.util.concurrent.CompletableFuture<RoomRepositoryClient.UpsertResult> future) {
		future.whenComplete((result, error) -> source.getServer().execute(() -> {
			if (error != null) {
				source.sendError(Text.literal(
					"Repository export failed for '" + id + "': " + message(error)));
				return;
			}
			source.sendFeedback(() -> Text.literal(
				"Repository " + result.operation().name().toLowerCase(Locale.ROOT)
					+ " '" + id + "' at revision " + result.metadata().revision() + "."), false);
		}));
	}

	private static int reload(CommandContext<ServerCommandSource> context) {
		try {
			GlibRoomLibrary.ChangeSet changes = RoomRuntime.reload(context.getSource().getServer());
			return feedback(context.getSource(),
				"Room library refreshed: " + changes.updated().size() + " updated, "
					+ changes.removed().size() + " removed, " + changes.errors().size() + " invalid.");
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int validate(CommandContext<ServerCommandSource> context) {
		try {
			RoomId id = RoomId.of(StringArgumentType.getString(context, "room"));
			VirtualRoom room = RoomRuntime.library(context.getSource().getServer()).find(id)
				.orElseThrow(() -> new IOException("Unknown room '" + id + "'"));
			RoomMinecraftAdapter.resolve(room);
			return feedback(context.getSource(),
				"Room '" + id + "' is valid for this server's block registry and runtime limits.");
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int sessions(CommandContext<ServerCommandSource> context) {
		List<RoomSessionInfo> sessions = RoomRuntime.sessions(context.getSource().getServer());
		if (sessions.isEmpty()) {
			return feedback(context.getSource(), "No virtual-room sessions are running.");
		}
		for (RoomSessionInfo session : sessions) {
			context.getSource().sendFeedback(() -> Text.literal(
				session.sessionId() + "  " + session.roomId() + "  "
					+ session.members().size() + " player(s)  anchor " + session.anchor().toShortString()
					+ "  revision " + session.revision()), false);
		}
		return sessions.size();
	}

	private static int testStatus(CommandContext<ServerCommandSource> context) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			RoomSessionInfo session = RoomRuntime.session(player)
				.orElseThrow(() -> new IOException("You are not in a virtual room."));
			Vec3d local = RoomRuntime.localPosition(player).orElseThrow();
			return feedback(context.getSource(), String.format(
				Locale.ROOT,
				"%s session %s, local %.3f %.3f %.3f, host anchor %s, revision %d",
				session.roomId(),
				session.sessionId(),
				local.x,
				local.y,
				local.z,
				session.anchor().toShortString(),
				session.revision()));
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static int testResend(CommandContext<ServerCommandSource> context) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			if (RoomRuntime.session(player).isEmpty()) {
				return error(context.getSource(), "You are not in a virtual room.");
			}
			RoomRuntime.resend(player);
			return feedback(context.getSource(), "Re-sent the complete packet-only room overlay.");
		} catch (Exception exception) {
			return error(context.getSource(), message(exception));
		}
	}

	private static Collection<String> roomIds(CommandContext<ServerCommandSource> context) {
		return RoomRuntime.compatibleRooms(context.getSource().getServer()).stream()
			.map(room -> room.id().value())
			.toList();
	}

	private static Collection<String> markerIds(CommandContext<ServerCommandSource> context) {
		try {
			ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
			return RoomRuntime.state(player)
				.map(session -> session.room().saved().markers().stream()
					.map(RoomMarker::id)
					.toList())
				.orElse(List.of());
		} catch (Exception exception) {
			return List.of();
		}
	}

	private static Selection selection(ServerPlayerEntity player) {
		Selection selection = SELECTIONS.get(player.getUuid());
		if (selection == null) {
			throw new IllegalStateException("Set a room corner first.");
		}
		return selection;
	}

	private static int feedback(ServerCommandSource source, String message) {
		source.sendFeedback(() -> Text.literal(message), false);
		return 1;
	}

	private static int error(ServerCommandSource source, String message) {
		source.sendError(Text.literal(message));
		return 0;
	}

	private static String message(Throwable exception) {
		Throwable current = exception;
		while ((current instanceof java.util.concurrent.CompletionException
				|| current instanceof java.util.concurrent.ExecutionException)
				&& current.getCause() != null) {
			current = current.getCause();
		}
		return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
	}

	private static final class Selection {
		private final RegistryKey<World> world;
		private final Map<String, PendingMarker> markers = new LinkedHashMap<>();
		private BlockPos first;
		private BlockPos second;

		private Selection(RegistryKey<World> world) {
			this.world = world;
		}

		private RegistryKey<World> world() {
			return world;
		}

		private BlockPos first() {
			return first;
		}

		private void first(BlockPos first) {
			this.first = first;
		}

		private BlockPos second() {
			return second;
		}

		private void second(BlockPos second) {
			this.second = second;
		}

		private Map<String, PendingMarker> markers() {
			return markers;
		}
	}

	private record PendingMarker(
			String id,
			RoomMarker.Kind kind,
			BlockPos worldPosition,
			float yaw,
			float pitch) {
	}
}
