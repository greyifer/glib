package io.github.caspe.glib.api.player;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class GlibFakePlayers {
	private static final String NAME_PATTERN = "[A-Za-z0-9_]{1,16}";
	private static final SocketAddress FAKE_ADDRESS = new SocketAddress() {
	};
	private static final Map<UUID, ServerPlayerEntity> FAKE_PLAYERS = new LinkedHashMap<>();

	private GlibFakePlayers() {
	}

	public static void registerCommands() {
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(CommandManager.literal("glib")
				.requires(source -> source.hasPermissionLevel(2))
				.then(CommandManager.literal("fakeplayer")
					.then(CommandManager.literal("spawn")
						.then(CommandManager.argument("name", StringArgumentType.word())
							.executes(context -> spawnCommand(context.getSource(), StringArgumentType.getString(context, "name")))))
					.then(CommandManager.literal("remove")
						.then(CommandManager.argument("name", StringArgumentType.word())
							.suggests((context, builder) -> CommandSource.suggestMatching(names(context.getSource().getServer()), builder))
							.executes(context -> removeCommand(context.getSource(), StringArgumentType.getString(context, "name")))))
					.then(CommandManager.literal("list")
						.executes(context -> listCommand(context.getSource()))))));
	}

	public static Optional<ServerPlayerEntity> spawn(ServerCommandSource source, String name) {
		return spawn(source.getServer(), source.getWorld(), profile(source.getServer(), name), source.getPosition(), source.getRotation());
	}

	public static Optional<ServerPlayerEntity> spawn(MinecraftServer server, ServerWorld world, GameProfile profile, Vec3d position, Vec2f rotation) {
		return spawn(server, world, profile, position, rotation.y, rotation.x);
	}

	public static Optional<ServerPlayerEntity> spawn(MinecraftServer server, ServerWorld world, GameProfile profile, Vec3d position, float yaw, float pitch) {
		if (!validName(profile.getName()) || server.getPlayerManager().getPlayer(profile.getName()) != null) {
			return Optional.empty();
		}

		SyncedClientOptions options = SyncedClientOptions.createDefault();
		ServerPlayerEntity player = new ServerPlayerEntity(server, world, profile, options);
		player.refreshPositionAndAngles(position.x, position.y, position.z, yaw, pitch);
		player.setHeadYaw(yaw);
		player.setBodyYaw(yaw);
		player.setInvulnerable(false);

		FakeClientConnection connection = new FakeClientConnection();
		ConnectedClientData clientData = new ConnectedClientData(profile, 0, options, false);
		server.getPlayerManager().onPlayerConnect(connection, player, clientData);
		player.changeGameMode(GameMode.SURVIVAL);
		player.teleport(world, position.x, position.y, position.z, yaw, pitch);
		player.refreshPositionAndAngles(position.x, position.y, position.z, yaw, pitch);
		FAKE_PLAYERS.put(player.getUuid(), player);
		return Optional.of(player);
	}

	public static boolean remove(MinecraftServer server, String name) {
		ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
		if (player == null || !isFake(player)) {
			return false;
		}

		FAKE_PLAYERS.remove(player.getUuid());
		if (player.networkHandler != null) {
			player.networkHandler.onDisconnected(new DisconnectionInfo(Text.literal("Removed fake player")));
		} else {
			server.getPlayerManager().remove(player);
			player.getServerWorld().removePlayer(player, Entity.RemovalReason.DISCARDED);
		}
		return true;
	}

	public static boolean isFake(ServerPlayerEntity player) {
		return player != null && FAKE_PLAYERS.containsKey(player.getUuid());
	}

	public static List<ServerPlayerEntity> all(MinecraftServer server) {
		List<ServerPlayerEntity> players = new ArrayList<>();
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (isFake(player)) {
				players.add(player);
			}
		}
		players.sort(Comparator.comparing(player -> player.getGameProfile().getName(), String.CASE_INSENSITIVE_ORDER));
		return List.copyOf(players);
	}

	public static List<String> names(MinecraftServer server) {
		return all(server).stream().map(player -> player.getGameProfile().getName()).toList();
	}

	private static int spawnCommand(ServerCommandSource source, String name) {
		if (!validName(name)) {
			source.sendError(Text.literal("Fake player names must be 1-16 letters, numbers, or underscores."));
			return 0;
		}

		Optional<ServerPlayerEntity> player = spawn(source, name);
		if (player.isEmpty()) {
			source.sendError(Text.literal("Could not spawn fake player '" + name + "'. Is that name already online?"));
			return 0;
		}

		source.sendFeedback(() -> Text.literal("Spawned fake player " + player.get().getGameProfile().getName()), false);
		return 1;
	}

	private static int removeCommand(ServerCommandSource source, String name) {
		if (!remove(source.getServer(), name)) {
			source.sendError(Text.literal("No fake player named '" + name + "' is online."));
			return 0;
		}

		source.sendFeedback(() -> Text.literal("Removed fake player " + name), false);
		return 1;
	}

	private static int listCommand(ServerCommandSource source) {
		List<String> fakeNames = names(source.getServer());
		if (fakeNames.isEmpty()) {
			source.sendFeedback(() -> Text.literal("No fake players are online."), false);
		} else {
			source.sendFeedback(() -> Text.literal("Fake players: " + String.join(", ", fakeNames)), false);
		}
		return fakeNames.size();
	}

	private static GameProfile profile(MinecraftServer server, String name) {
		return server.getUserCache()
			.findByName(name)
			.orElseGet(() -> Uuids.getOfflinePlayerProfile(name));
	}

	private static boolean validName(String name) {
		return name != null && name.matches(NAME_PATTERN) && !name.toLowerCase(Locale.ROOT).startsWith("§");
	}

	private static final class FakeClientConnection extends ClientConnection {
		private boolean open = true;

		private FakeClientConnection() {
			super(NetworkSide.SERVERBOUND);
		}

		@Override
		public void send(Packet<?> packet) {
		}

		@Override
		public void send(Packet<?> packet, PacketCallbacks callbacks) {
		}

		@Override
		public void send(Packet<?> packet, PacketCallbacks callbacks, boolean flush) {
		}

		@Override
		public void flush() {
		}

		@Override
		public SocketAddress getAddress() {
			return FAKE_ADDRESS;
		}

		@Override
		public String getAddressAsString(boolean useRealAddress) {
			return "glib-fake-player";
		}

		@Override
		public void disconnect(Text disconnectReason) {
			open = false;
		}

		@Override
		public void disconnect(DisconnectionInfo disconnectionInfo) {
			open = false;
		}

		@Override
		public boolean isOpen() {
			return open;
		}

		@Override
		public void handleDisconnection() {
		}
	}
}
