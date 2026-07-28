package io.github.caspe.glib.room;

import io.github.caspe.glib.api.room.GlibRoomLibrary;
import io.github.caspe.glib.api.room.RoomDimensions;
import io.github.caspe.glib.api.room.RoomId;
import io.github.caspe.glib.api.room.RoomInteractionCallback;
import io.github.caspe.glib.api.room.RoomMarker;
import io.github.caspe.glib.api.room.RoomPosition;
import io.github.caspe.glib.api.room.RoomSessionInfo;
import io.github.caspe.glib.api.room.VirtualRoom;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import io.github.caspe.glib.network.RoomLibraryNetworking;
import io.github.caspe.glib.network.RoomOverlayNetworking;
import io.github.caspe.glib.mixin.PlayerManagerAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoomRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("G'Lib Virtual Rooms");
	private static final AtomicBoolean REGISTERED = new AtomicBoolean();
	private static final Map<MinecraftServer, ServerState> SERVERS = new IdentityHashMap<>();
	private static final Map<PlayerKey, RoomSessionState> PLAYER_SESSIONS = new LinkedHashMap<>();
	private static final int HOST_MARGIN = 2;

	private RoomRuntime() {
	}

	public static void register() {
		if (!REGISTERED.compareAndSet(false, true)) {
			return;
		}
		ServerLifecycleEvents.SERVER_STARTED.register(RoomRuntime::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(RoomRuntime::prepareStop);
		ServerLifecycleEvents.SERVER_STOPPED.register(RoomRuntime::stop);
		ServerTickEvents.END_SERVER_TICK.register(RoomRuntime::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
			disconnect(handler.player));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
			recoverInterruptedReturn(handler.player));
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			!(entity instanceof ServerPlayerEntity player) || session(player).isEmpty());
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (player instanceof ServerPlayerEntity serverPlayer) {
				return interact(serverPlayer, hand, hit, RoomInteractionCallback.Kind.USE);
			}
			return ActionResult.PASS;
		});
		AttackBlockCallback.EVENT.register((player, world, hand, position, direction) -> {
			if (player instanceof ServerPlayerEntity serverPlayer) {
				BlockHitResult hit = new BlockHitResult(
					Vec3d.ofCenter(position), direction, position, false);
				return interact(serverPlayer, hand, hit, RoomInteractionCallback.Kind.ATTACK);
			}
			return ActionResult.PASS;
		});
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (player instanceof ServerPlayerEntity serverPlayer && isActive(serverPlayer)) {
				return TypedActionResult.fail(player.getStackInHand(hand));
			}
			return TypedActionResult.pass(player.getStackInHand(hand));
		});
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
			player instanceof ServerPlayerEntity serverPlayer && isActive(serverPlayer)
				? ActionResult.SUCCESS
				: ActionResult.PASS);
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
			player instanceof ServerPlayerEntity serverPlayer && isActive(serverPlayer)
				? ActionResult.SUCCESS
				: ActionResult.PASS);
		RoomCommands.register();
	}

	public static GlibRoomLibrary library(MinecraftServer server) {
		ServerState state = SERVERS.get(server);
		if (state == null) {
			throw new IllegalStateException("G'Lib room library is not available before the server starts");
		}
		return state.library();
	}

	public static Optional<RoomSessionInfo> session(ServerPlayerEntity player) {
		RoomSessionState session = activeSession(player);
		return Optional.ofNullable(session == null ? null : session.info());
	}

	static Optional<RoomSessionState> state(ServerPlayerEntity player) {
		return Optional.ofNullable(activeSession(player));
	}

	public static List<RoomSessionInfo> sessions(MinecraftServer server) {
		ServerState state = SERVERS.get(server);
		if (state == null) {
			return List.of();
		}
		return state.sessions().values().stream()
			.map(RoomSessionState::info)
			.toList();
	}

	public static List<VirtualRoom> compatibleRooms(MinecraftServer server) {
		ServerState state = SERVERS.get(server);
		return state == null ? List.of() : state.compatibleRooms();
	}

	public static RoomSessionInfo enter(ServerPlayerEntity player, RoomId roomId) throws IOException {
		ServerState serverState = requireState(player.getServer());
		requireSupportedClient(player);
		requireEntryCooldown(serverState, player);
		if (serverState.sessions().size() >= 128) {
			throw new IOException("The server already has the maximum of 128 virtual-room sessions");
		}
		VirtualRoom saved = serverState.library().find(roomId)
			.orElseThrow(() -> new IOException("Unknown virtual room '" + roomId + "'"));
		RoomMinecraftAdapter.ResolvedRoom room = RoomMinecraftAdapter.resolve(saved);
		leaveInternal(player, true, true, false);

		BlockPos anchor = findAnchor(
			serverState, player.getServerWorld(), room.saved().dimensions(), player.getPos(), null)
			.orElseThrow(() -> new IOException(
				"Could not find a verified-empty host volume in this world for " + roomId));
		RoomSessionState created = new RoomSessionState(
			UUID.randomUUID(), room, player.getServerWorld(), anchor);
		serverState.sessions().put(created.id(), created);
		try {
			joinInternal(player, created, null);
		} catch (IOException | RuntimeException exception) {
			serverState.sessions().remove(created.id());
			throw exception;
		}
		return created.info();
	}

	public static RoomSessionInfo join(
			ServerPlayerEntity player, UUID sessionId, @Nullable String spawnMarker) throws IOException {
		ServerState serverState = requireState(player.getServer());
		requireSupportedClient(player);
		requireEntryCooldown(serverState, player);
		RoomSessionState target = serverState.sessions().get(sessionId);
		if (target == null) {
			throw new IOException("Unknown virtual-room session " + sessionId);
		}
		if (rawSession(player) == target) {
			RoomSessionState.Participant participant = target.participants().get(player.getUuid());
			if (participant != null) {
				participant.resetOverlay(requireState(player.getServer()).tick());
			}
			teleportToSpawn(player, target, spawnMarker);
			RoomOverlaySender.sendFull(player, target);
			return target.info();
		}
		leaveInternal(player, true, true, false);
		joinInternal(player, target, spawnMarker);
		return target.info();
	}

	public static boolean leave(ServerPlayerEntity player) {
		return leaveInternal(player, true, true, true);
	}

	public static Optional<Vec3d> localPosition(ServerPlayerEntity player) {
		RoomSessionState session = activeSession(player);
		if (session == null) {
			return Optional.empty();
		}
		return Optional.of(session.toLocal(player.getPos()));
	}

	public static Optional<RoomPosition> localBlockPosition(ServerPlayerEntity player) {
		RoomSessionState session = activeSession(player);
		return session == null ? Optional.empty() : session.toLocal(player.getBlockPos());
	}

	public static Optional<Vec3d> toWorld(ServerPlayerEntity player, Vec3d localPosition) {
		RoomSessionState session = activeSession(player);
		return session == null ? Optional.empty() : Optional.of(session.toWorld(localPosition));
	}

	public static boolean teleportLocal(
			ServerPlayerEntity player,
			double x,
			double y,
			double z,
			float yaw,
			float pitch) {
		RoomSessionState session = activeSession(player);
		if (session == null
				|| !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
				|| !Float.isFinite(yaw) || !Float.isFinite(pitch)
				|| pitch < -90.0F || pitch > 90.0F
				|| x < 0.0 || x >= session.dimensions().width()
				|| y < 0.0 || y >= session.dimensions().height()
				|| z < 0.0 || z >= session.dimensions().depth()) {
			return false;
		}
		Vec3d worldPosition = session.toWorld(new Vec3d(x, y, z));
		player.setVelocity(Vec3d.ZERO);
		player.teleport(session.world(), worldPosition.x, worldPosition.y, worldPosition.z, yaw, pitch);
		RoomOverlaySender.sendFull(player, session);
		return true;
	}

	public static boolean teleportMarker(ServerPlayerEntity player, String markerId) {
		RoomSessionState session = activeSession(player);
		if (session == null) {
			return false;
		}
		for (RoomMarker marker : session.room().saved().markers()) {
			if (marker.id().equals(markerId)) {
				return teleportLocal(
					player,
					marker.position().x() + 0.5,
					marker.position().y(),
					marker.position().z() + 0.5,
					marker.yaw(),
					marker.pitch());
			}
		}
		return false;
	}

	public static boolean coordinateHud(ServerPlayerEntity player, boolean enabled) {
		RoomSessionState session = activeSession(player);
		if (session == null) {
			return false;
		}
		session.participants().get(player.getUuid()).coordinateHud(enabled);
		return true;
	}

	public static boolean coordinateHud(ServerPlayerEntity player) {
		RoomSessionState session = activeSession(player);
		return session != null && session.participants().get(player.getUuid()).coordinateHud();
	}

	public static boolean setVisualBlock(
			ServerPlayerEntity member, RoomPosition position, BlockState state) {
		RoomSessionState session = activeSession(member);
		if (session == null || !session.dimensions().contains(position)) {
			return false;
		}
		session.setState(position, state);
		RoomOverlaySender.sendBlock(session, position);
		return true;
	}

	public static boolean resetVisualBlock(
			ServerPlayerEntity member, RoomPosition position) {
		RoomSessionState session = activeSession(member);
		if (session == null || !session.dimensions().contains(position)) {
			return false;
		}
		session.setState(position, session.room().stateAt(position));
		RoomOverlaySender.sendBlock(session, position);
		return true;
	}

	public static VirtualRoom capture(
			ServerPlayerEntity author,
			BlockPos first,
			BlockPos second,
			RoomId id,
			String displayName,
			Collection<RoomMarker> markers) throws IOException {
		return RoomMinecraftAdapter.capture(
			author.getServerWorld(), first, second, id, displayName, author, markers);
	}

	public static GlibRoomLibrary.ChangeSet save(MinecraftServer server, VirtualRoom room) throws IOException {
		return library(server).save(room);
	}

	public static GlibRoomLibrary.ChangeSet reload(MinecraftServer server) throws IOException {
		return library(server).refresh();
	}

	static boolean repositoryConfigured(MinecraftServer server) {
		ServerState state = SERVERS.get(server);
		return state != null && state.repository() != null;
	}

	@Nullable
	static String repositoryConfigurationError(MinecraftServer server) {
		ServerState state = SERVERS.get(server);
		return state == null ? null : state.repositoryConfigurationError();
	}

	static Optional<RoomRepositorySynchronizer.Status> repositoryStatus(MinecraftServer server) {
		ServerState state = SERVERS.get(server);
		return Optional.ofNullable(
			state == null || state.repository() == null ? null : state.repository().status());
	}

	static CompletableFuture<RoomRepositorySynchronizer.PullResult> pullRepository(
			MinecraftServer server) {
		RoomRepositorySynchronizer repository = requireRepository(server);
		return repository.pull();
	}

	static CompletableFuture<RoomRepositoryClient.UpsertResult> pushRepository(
			MinecraftServer server, RoomId roomId) throws IOException {
		ServerState state = requireState(server);
		RoomRepositorySynchronizer repository = requireRepository(server);
		VirtualRoom room = state.library().find(roomId)
			.orElseThrow(() -> new IOException("Unknown virtual room '" + roomId + "'"));
		Optional<String> validationError = validationError(room);
		if (validationError.isPresent()) {
			throw new IOException(
				"Room '" + roomId + "' is not runtime-compatible: " + validationError.orElseThrow());
		}
		return repository.push(room);
	}

	public static void resend(ServerPlayerEntity player) {
		RoomSessionState session = activeSession(player);
		if (session != null) {
			RoomOverlaySender.sendFullForced(player, session);
		}
	}

	public static void refreshChunk(
			ServerPlayerEntity player, UUID sessionId, int chunkX, int chunkZ) {
		RoomSessionState session = activeSession(player);
		if (session == null || !session.id().equals(sessionId)) {
			return;
		}
		ServerState serverState = SERVERS.get(player.getServer());
		RoomSessionState.Participant participant = session.participants().get(player.getUuid());
		if (serverState == null || participant == null || player.getWorld() != session.world()) {
			return;
		}
		int minChunkX = session.anchor().getX() >> 4;
		int maxChunkX = (session.anchor().getX() + session.dimensions().width() - 1) >> 4;
		int minChunkZ = session.anchor().getZ() >> 4;
		int maxChunkZ = (session.anchor().getZ() + session.dimensions().depth() - 1) >> 4;
		if (chunkX < minChunkX || chunkX > maxChunkX || chunkZ < minChunkZ || chunkZ > maxChunkZ) {
			return;
		}
		participant.queueChunkRefresh(chunkX, chunkZ, serverState.tick());
	}

	static List<ServerPlayerEntity> players(RoomSessionState session) {
		List<ServerPlayerEntity> players = new ArrayList<>();
		for (UUID playerId : session.members()) {
			ServerPlayerEntity player = session.world().getServer().getPlayerManager().getPlayer(playerId);
			if (player != null) {
				players.add(player);
			}
		}
		return players;
	}

	public static boolean isActive(ServerPlayerEntity player) {
		return activeSession(player) != null;
	}

	public static boolean usesVirtualCollisions(ServerPlayerEntity player, Box query) {
		RoomSessionState session = activeSession(player);
		return session != null
			&& session.hostBox(HOST_MARGIN).intersects(query);
	}

	public static Iterable<net.minecraft.util.shape.VoxelShape> collisions(
			ServerPlayerEntity player, Box query) {
		RoomSessionState session = activeSession(player);
		return session == null
			? List.of()
			: RoomVirtualBlockView.collisions(session, player, query);
	}

	public static Optional<String> validationError(VirtualRoom room) {
		try {
			RoomMinecraftAdapter.resolve(room);
			return Optional.empty();
		} catch (IOException | RuntimeException exception) {
			String message = exception.getMessage();
			return Optional.of(message == null ? exception.getClass().getSimpleName() : message);
		}
	}

	@Nullable
	private static RoomSessionState rawSession(ServerPlayerEntity player) {
		return PLAYER_SESSIONS.get(playerKey(player));
	}

	@Nullable
	private static RoomSessionState activeSession(ServerPlayerEntity player) {
		RoomSessionState session = rawSession(player);
		return session != null && player.getWorld() == session.world() ? session : null;
	}

	private static PlayerKey playerKey(ServerPlayerEntity player) {
		return new PlayerKey(player.getServer(), player.getUuid());
	}

	private static void start(MinecraftServer server) {
		GlibRoomLibrary library = new GlibRoomLibrary(
			server.getSavePath(WorldSavePath.ROOT).resolve("glib").resolve("rooms"));
		RoomReturnStore returnStore = null;
		String returnStoreError = null;
		try {
			returnStore = RoomReturnStore.open(
				server.getSavePath(WorldSavePath.ROOT)
					.resolve("glib")
					.resolve("pending-room-returns.properties"));
		} catch (IOException | RuntimeException exception) {
			returnStoreError = exception.getMessage();
			LOGGER.error(
				"Virtual-room entry is disabled because the return journal could not be opened",
				exception);
		}
		ServerState state = new ServerState(library, returnStore, returnStoreError);
		SERVERS.put(server, state);
		try {
			RoomRepositoryClient.configured().ifPresent(client -> {
				state.repository(new RoomRepositorySynchronizer(server, library, client));
				LOGGER.info("Configured automatic virtual-room repository synchronization");
			});
		} catch (IllegalStateException exception) {
			state.repositoryConfigurationError(exception.getMessage());
			LOGGER.error("Virtual-room repository synchronization is disabled: {}", exception.getMessage());
		}
		library.listen(changes -> applyLibraryChanges(server, changes));
		try {
			reportRefresh(library.refresh());
		} catch (IOException exception) {
			LOGGER.error("Could not load virtual-room library {}", library.directory(), exception);
		}
		LOGGER.info("Loaded {} virtual room(s) from {}", library.size(), library.directory());
	}

	private static void stop(MinecraftServer server) {
		ServerState removed = SERVERS.remove(server);
		if (removed != null) {
			if (removed.repository() != null) {
				removed.repository().close();
			}
			for (RoomSessionState session : removed.sessions().values()) {
				for (UUID playerId : session.members()) {
					PLAYER_SESSIONS.remove(new PlayerKey(server, playerId));
				}
			}
			removed.sessions().clear();
		}
	}

	private static void disconnect(ServerPlayerEntity player) {
		leaveInternal(player, false, true, false, true);
		RoomCommands.clearSelection(player.getUuid());
		ServerState state = SERVERS.get(player.getServer());
		if (state != null) {
			state.lastEntryTicks().remove(player.getUuid());
		}
	}

	private static void prepareStop(MinecraftServer server) {
		ServerState state = SERVERS.get(server);
		if (state == null) {
			return;
		}
		if (state.repository() != null) {
			state.repository().close();
		}
		for (RoomSessionState session : List.copyOf(state.sessions().values())) {
			for (ServerPlayerEntity player : List.copyOf(players(session))) {
				leaveInternal(player, false, true, false, true);
			}
		}
	}

	private static void tick(MinecraftServer server) {
		ServerState state = SERVERS.get(server);
		if (state == null) {
			return;
		}
		state.tick(state.tick() + 1L);
		if (state.repository() != null) {
			state.repository().tick(state.tick());
		}
		for (RoomSessionState session : List.copyOf(state.sessions().values())) {
			tickSession(state, session);
		}

		if (state.tick() % 20L == 0L) {
			try {
				reportRefresh(state.library().refresh());
			} catch (IOException exception) {
				LOGGER.warn("Could not refresh virtual-room library", exception);
			}
		}
	}

	private static void tickSession(ServerState serverState, RoomSessionState session) {
		long tick = serverState.tick();
		for (Map.Entry<RoomPosition, Long> correction
				: List.copyOf(session.scheduledCorrections().entrySet())) {
			if (correction.getValue() > tick) {
				continue;
			}
			session.scheduledCorrections().remove(correction.getKey());
			RoomOverlaySender.sendBlock(session, correction.getKey());
		}
		for (Map.Entry<RoomPosition, Long> reset : List.copyOf(session.scheduledResets().entrySet())) {
			if (reset.getValue() > tick) {
				continue;
			}
			session.scheduledResets().remove(reset.getKey());
			session.setState(reset.getKey(), session.room().stateAt(reset.getKey()));
			RoomOverlaySender.sendBlock(session, reset.getKey());
		}

		for (ServerPlayerEntity player : players(session)) {
			if (player.getWorld() != session.world()) {
				RoomOverlayNetworking.end(player, overlayInfo(session));
				leaveInternal(player, false, false, false);
				continue;
			}
			RoomSessionState.Participant participant = session.participants().get(player.getUuid());
			participant.pollChunkRefresh(tick).ifPresent(refresh ->
				RoomOverlaySender.sendChunk(player, session, refresh.x(), refresh.z()));
			for (int sent = 0; sent < 16; sent++) {
				Optional<RoomPosition> blockEntity = participant.pollBlockEntityRefresh();
				if (blockEntity.isEmpty()) {
					break;
				}
				RoomOverlaySender.sendBlockEntity(player, session, blockEntity.orElseThrow());
			}
			long age = tick - participant.joinedTick();
			int pass = participant.overlayPass();
			if ((pass == 0 && age >= 2) || (pass == 1 && age >= 20)) {
				RoomOverlaySender.sendFull(player, session);
				participant.overlayPass(pass + 1);
			}

			Vec3d local = session.toLocal(player.getPos());
			if (participant.coordinateHud() && tick % 4L == 0L) {
				player.sendMessage(Text.literal(String.format(
					java.util.Locale.ROOT,
					"Room %s  x %.2f  y %.2f  z %.2f  block %d %d %d",
					session.roomId(),
					local.x, local.y, local.z,
					MathHelper.floor(local.x), MathHelper.floor(local.y), MathHelper.floor(local.z))), true);
			}
			if (!insideWithMargin(local, session.dimensions(), 1.5)) {
				teleportToSpawn(player, session, null);
				player.sendMessage(Text.literal("Returned to the virtual-room spawn (outside local bounds)."), false);
			}
		}

		if (!hostSliceClear(session, HOST_MARGIN, 4096)) {
			for (ServerPlayerEntity player : List.copyOf(players(session))) {
				player.sendMessage(Text.literal(
					"Virtual room closed because its non-destructive host volume is no longer empty."), false);
				leaveInternal(player, true, true, false);
			}
		}
	}

	private static ActionResult interact(
			ServerPlayerEntity player,
			Hand hand,
			BlockHitResult hit,
			RoomInteractionCallback.Kind kind) {
		RoomSessionState session = activeSession(player);
		if (session == null) {
			return ActionResult.PASS;
		}
		Optional<RoomPosition> local = session.toLocal(hit.getBlockPos());
		if (local.isEmpty()) {
			return ActionResult.SUCCESS;
		}

		RoomInteractionCallback.Context context = new RoomInteractionCallback.Context(
			player,
			session.info(),
			local.get(),
			kind,
			hand,
			hit,
			session.markerAt(local.get()));
		ActionResult callback = RoomInteractionCallback.EVENT.invoker().interact(context);
		if (callback != ActionResult.PASS) {
			RoomOverlaySender.sendBlock(session, local.get());
			scheduleInteractionCorrection(player, session, local.get(), hit, kind);
			return callback;
		}

		if (kind == RoomInteractionCallback.Kind.USE) {
			simulateUse(session, local.get(), requireState(player.getServer()).tick());
			player.swingHand(hand, true);
		} else {
			RoomOverlaySender.sendBlock(session, local.get());
		}
		scheduleInteractionCorrection(player, session, local.get(), hit, kind);
		return ActionResult.SUCCESS;
	}

	private static void scheduleInteractionCorrection(
			ServerPlayerEntity player,
			RoomSessionState session,
			RoomPosition position,
			BlockHitResult hit,
			RoomInteractionCallback.Kind kind) {
		long correctionTick = requireState(player.getServer()).tick() + 1L;
		session.scheduleCorrection(position, correctionTick);
		if (kind == RoomInteractionCallback.Kind.USE) {
			session.toLocal(hit.getBlockPos().offset(hit.getSide()))
				.ifPresent(adjacent -> session.scheduleCorrection(adjacent, correctionTick));
		}
	}

	private static void simulateUse(RoomSessionState session, RoomPosition position, long tick) {
		BlockState state = session.stateAt(position);
		if (state.contains(Properties.OPEN)) {
			boolean open = !state.get(Properties.OPEN);
			session.setState(position, state.with(Properties.OPEN, open));
			RoomOverlaySender.sendBlock(session, position);
			if (state.getBlock() instanceof DoorBlock && state.contains(Properties.DOUBLE_BLOCK_HALF)) {
				DoubleBlockHalf half = state.get(Properties.DOUBLE_BLOCK_HALF);
				int otherY = position.y() + (half == DoubleBlockHalf.LOWER ? 1 : -1);
				if (otherY >= 0 && otherY < session.dimensions().height()) {
					RoomPosition other = new RoomPosition(position.x(), otherY, position.z());
					BlockState otherState = session.stateAt(other);
					if (otherState.getBlock() == state.getBlock() && otherState.contains(Properties.OPEN)) {
						session.setState(other, otherState.with(Properties.OPEN, open));
						RoomOverlaySender.sendBlock(session, other);
					}
				}
			}
			return;
		}
		if (state.contains(Properties.POWERED)) {
			boolean powered = !state.get(Properties.POWERED);
			session.setState(position, state.with(Properties.POWERED, powered));
			if (powered && state.getBlock() instanceof ButtonBlock) {
				session.scheduleReset(position, tick + 20L);
			}
			RoomOverlaySender.sendBlock(session, position);
		}
	}

	private static void joinInternal(
			ServerPlayerEntity player,
			RoomSessionState session,
			@Nullable String markerId) throws IOException {
		RoomSessionState.ReturnPoint returnPoint = new RoomSessionState.ReturnPoint(
			player.getWorld().getRegistryKey(),
			player.getPos(),
			player.getYaw(),
			player.getPitch());
		ServerState serverState = requireState(player.getServer());
		if (serverState.returnStore() == null) {
			throw new IOException(
				"Virtual-room entry is disabled because the crash-recovery journal could not be opened"
					+ (serverState.returnStoreError() == null
						? ""
						: ": " + serverState.returnStoreError()));
		}
		if (player.currentScreenHandler != player.playerScreenHandler) {
			player.closeHandledScreen();
		}
		serverState.returnStore().put(player.getUuid(), returnPoint);
		try {
			session.add(player, returnPoint, serverState.tick());
			PLAYER_SESSIONS.put(playerKey(player), session);
			player.clearActiveItem();
			RoomOverlayNetworking.begin(player, overlayInfo(session));
			teleportToSpawn(player, session, markerId);
			RoomOverlaySender.sendFull(player, session);
			player.sendMessage(Text.literal(
				"Entered virtual room '" + session.room().saved().displayName() + "' (session "
					+ shortId(session.id()) + ")."), false);
		} catch (RuntimeException exception) {
			try {
				leaveInternal(player, false, true, false);
			} catch (RuntimeException cleanupException) {
				exception.addSuppressed(cleanupException);
			}
			throw exception;
		}
	}

	private static void teleportToSpawn(
			ServerPlayerEntity player, RoomSessionState session, @Nullable String markerId) {
		RoomMarker marker = session.spawnMarker(markerId);
		Vec3d destination = session.toWorld(new Vec3d(
			marker.position().x() + 0.5,
			marker.position().y(),
			marker.position().z() + 0.5));
		player.setVelocity(Vec3d.ZERO);
		player.teleport(
			session.world(), destination.x, destination.y, destination.z, marker.yaw(), marker.pitch());
	}

	private static boolean leaveInternal(
			ServerPlayerEntity player,
			boolean restoreOverlay,
			boolean returnPlayer,
			boolean notify) {
		return leaveInternal(player, restoreOverlay, returnPlayer, notify, false);
	}

	private static boolean leaveInternal(
			ServerPlayerEntity player,
			boolean restoreOverlay,
			boolean returnPlayer,
			boolean notify,
			boolean retainReturnRecovery) {
		RoomSessionState session = PLAYER_SESSIONS.remove(playerKey(player));
		if (session == null) {
			return false;
		}
		RoomSessionState.Participant participant = session.remove(player.getUuid());
		if (restoreOverlay && participant != null) {
			RoomOverlayNetworking.end(player, overlayInfo(session));
			RoomOverlaySender.restore(player, session);
		}
		if (returnPlayer && participant != null) {
			RoomSessionState.ReturnPoint returnPoint = participant.returnPoint();
			ServerWorld returnWorld = player.getServer().getWorld(returnPoint.world());
			if (returnWorld == null) {
				returnWorld = player.getServer().getOverworld();
			}
			player.setVelocity(Vec3d.ZERO);
			player.teleport(
				returnWorld,
				returnPoint.position().x,
				returnPoint.position().y,
				returnPoint.position().z,
				returnPoint.yaw(),
				returnPoint.pitch());
		}
		if (!retainReturnRecovery) {
			clearReturnRecovery(player);
		}
		ServerState state = SERVERS.get(player.getServer());
		if (session.isEmpty() && state != null) {
			state.sessions().remove(session.id());
		}
		if (notify) {
			player.sendMessage(Text.literal("Left the virtual room."), false);
		}
		return true;
	}

	private static void clearReturnRecovery(ServerPlayerEntity player) {
		ServerState state = SERVERS.get(player.getServer());
		if (state == null || state.returnStore() == null) {
			return;
		}
		try {
			((PlayerManagerAccessor) player.getServer().getPlayerManager()).glib$savePlayerData(player);
			state.returnStore().clear(player.getUuid());
		} catch (IOException | RuntimeException exception) {
			LOGGER.error(
				"Could not durably clear virtual-room return recovery for {}", player.getUuid(), exception);
		}
	}

	private static void recoverInterruptedReturn(ServerPlayerEntity player) {
		ServerState state = SERVERS.get(player.getServer());
		if (state == null || state.returnStore() == null) {
			return;
		}
		RoomSessionState.ReturnPoint returnPoint =
			state.returnStore().find(player.getUuid()).orElse(null);
		if (returnPoint == null) {
			return;
		}
		ServerWorld world = player.getServer().getWorld(returnPoint.world());
		Vec3d position = returnPoint.position();
		float yaw = returnPoint.yaw();
		float pitch = returnPoint.pitch();
		BlockPos blockPosition = BlockPos.ofFloored(position);
		if (world == null
				|| position.y < world.getBottomY()
				|| position.y >= world.getTopY()
				|| !world.getWorldBorder().contains(blockPosition)) {
			world = player.getServer().getOverworld();
			position = Vec3d.ofBottomCenter(world.getSpawnPos());
			yaw = 0.0F;
			pitch = 0.0F;
		}
		player.setVelocity(Vec3d.ZERO);
		player.teleport(world, position.x, position.y, position.z, yaw, pitch);
		clearReturnRecovery(player);
		player.sendMessage(Text.literal(
			"Recovered your position after an interrupted virtual-room session."), false);
	}

	private static void applyLibraryChanges(
			MinecraftServer server, GlibRoomLibrary.ChangeSet changes) {
		reportRefresh(changes);
		ServerState state = SERVERS.get(server);
		if (state == null) {
			return;
		}
		for (RoomSessionState session : List.copyOf(state.sessions().values())) {
			detachWorldMismatches(session);
		}
		for (RoomId removed : changes.removed()) {
			for (RoomSessionState session : List.copyOf(state.sessions().values())) {
				if (!session.roomId().equals(removed)) {
					continue;
				}
				for (ServerPlayerEntity player : List.copyOf(players(session))) {
					player.sendMessage(Text.literal(
						"Virtual room '" + removed + "' was removed; returning you."), false);
					leaveInternal(player, true, true, false);
				}
			}
		}
		for (RoomId updated : changes.updated()) {
			VirtualRoom saved = state.library().find(updated).orElse(null);
			if (saved == null) {
				continue;
			}
			final RoomMinecraftAdapter.ResolvedRoom replacement;
			try {
				replacement = RoomMinecraftAdapter.resolve(saved);
			} catch (IOException | RuntimeException exception) {
				LOGGER.error("Cannot apply updated virtual room '{}': {}", updated, exception.getMessage());
				continue;
			}
			for (RoomSessionState session : List.copyOf(state.sessions().values())) {
				if (session.roomId().equals(updated)) {
					liveReplace(state, session, replacement);
				}
			}
		}
		state.refreshCompatibleRooms();
		if (!changes.updated().isEmpty() || !changes.removed().isEmpty()) {
			RoomLibraryNetworking.broadcast(server);
		}
	}

	private static void liveReplace(
			ServerState state,
			RoomSessionState session,
			RoomMinecraftAdapter.ResolvedRoom replacement) {
		List<ServerPlayerEntity> members = List.copyOf(players(session));
		Map<UUID, Vec3d> localPositions = new LinkedHashMap<>();
		for (ServerPlayerEntity player : members) {
			localPositions.put(player.getUuid(), session.toLocal(player.getPos()));
			RoomOverlayNetworking.end(player, overlayInfo(session));
			RoomOverlaySender.restore(player, session);
		}

		BlockPos newAnchor = session.anchor();
		if (!hostClear(session.world(), newAnchor, replacement.saved().dimensions(), HOST_MARGIN)
				|| overlaps(state, session.world(), newAnchor, replacement.saved().dimensions(), session.id())) {
			newAnchor = findAnchor(
				state,
				session.world(),
				replacement.saved().dimensions(),
				Vec3d.ofCenter(session.anchor()),
				session.id()).orElse(null);
		}
		if (newAnchor == null) {
			for (ServerPlayerEntity player : members) {
				player.sendMessage(Text.literal(
					"Updated room needs a larger empty host volume; this session was closed."), false);
				leaveInternal(player, false, true, false);
			}
			return;
		}

		session.replaceRoom(replacement, newAnchor);
		for (ServerPlayerEntity player : members) {
			RoomSessionState.Participant participant = session.participants().get(player.getUuid());
			if (participant == null || player.getWorld() != session.world()) {
				continue;
			}
			participant.resetOverlay(state.tick());
			Vec3d local = localPositions.get(player.getUuid());
			RoomOverlayNetworking.begin(player, overlayInfo(session));
			if (!insideWithMargin(local, session.dimensions(), 0.0)) {
				teleportToSpawn(player, session, null);
			} else {
				Vec3d destination = session.toWorld(local);
				player.teleport(
					session.world(), destination.x, destination.y, destination.z,
					player.getYaw(), player.getPitch());
			}
			RoomOverlaySender.sendFull(player, session);
			player.sendMessage(Text.literal(
				"Virtual room '" + session.roomId() + "' live-reloaded to revision "
					+ session.revision() + "."), false);
		}
	}

	private static void detachWorldMismatches(RoomSessionState session) {
		for (ServerPlayerEntity player : List.copyOf(players(session))) {
			if (player.getWorld() == session.world()) {
				continue;
			}
			RoomOverlayNetworking.end(player, overlayInfo(session));
			leaveInternal(player, false, false, false);
		}
	}

	private static Optional<BlockPos> findAnchor(
			ServerState state,
			ServerWorld world,
			RoomDimensions dimensions,
			Vec3d near,
			@Nullable UUID ignoreSession) {
		int spacing = Math.max(64, Math.max(dimensions.width(), dimensions.depth()) + 16);
		int baseX = Math.floorDiv(MathHelper.floor(near.x), spacing) * spacing;
		int baseZ = Math.floorDiv(MathHelper.floor(near.z), spacing) * spacing;
		int topY = world.getTopY() - dimensions.height() - 8;
		int bottomY = world.getBottomY() + 4;
		if (topY < bottomY) {
			return Optional.empty();
		}

		for (int y = topY; y >= bottomY; y -= Math.max(32, dimensions.height() + 8)) {
			for (int radius = 0; radius <= 4; radius++) {
				for (int dz = -radius; dz <= radius; dz++) {
					for (int dx = -radius; dx <= radius; dx++) {
						if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
							continue;
						}
						BlockPos anchor = new BlockPos(
							baseX + dx * spacing, y, baseZ + dz * spacing);
						if (!insideBorder(world, anchor, dimensions)
								|| overlaps(state, world, anchor, dimensions, ignoreSession)
								|| !hostClear(world, anchor, dimensions, HOST_MARGIN)) {
							continue;
						}
						return Optional.of(anchor);
					}
				}
			}
		}
		return Optional.empty();
	}

	private static boolean insideBorder(
			ServerWorld world, BlockPos anchor, RoomDimensions dimensions) {
		return world.getWorldBorder().contains(anchor)
			&& world.getWorldBorder().contains(anchor.add(
				dimensions.width() - 1, dimensions.height() - 1, dimensions.depth() - 1));
	}

	private static boolean overlaps(
			ServerState state,
			ServerWorld world,
			BlockPos anchor,
			RoomDimensions dimensions,
			@Nullable UUID ignoreSession) {
		Box proposed = hostBox(anchor, dimensions, HOST_MARGIN + 2);
		for (RoomSessionState active : state.sessions().values()) {
			if (active.id().equals(ignoreSession) || active.world() != world) {
				continue;
			}
			if (proposed.intersects(active.hostBox(HOST_MARGIN + 2))) {
				return true;
			}
		}
		return false;
	}

	private static boolean hostClear(
			ServerWorld world, BlockPos anchor, RoomDimensions dimensions, int margin) {
		BlockPos min = anchor.add(-margin, -margin, -margin);
		BlockPos max = anchor.add(
			dimensions.width() - 1 + margin,
			dimensions.height() - 1 + margin,
			dimensions.depth() - 1 + margin);
		if (min.getY() < world.getBottomY() || max.getY() >= world.getTopY()) {
			return false;
		}
		int minChunkX = min.getX() >> 4;
		int maxChunkX = max.getX() >> 4;
		int minChunkZ = min.getZ() >> 4;
		int maxChunkZ = max.getZ() >> 4;
		for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
			for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
				if (!world.isChunkLoaded(new BlockPos(chunkX << 4, anchor.getY(), chunkZ << 4))) {
					return false;
				}
			}
		}
		for (BlockPos position : BlockPos.iterate(min, max)) {
			if (!world.getBlockState(position).isAir()) {
				return false;
			}
		}
		return true;
	}

	private static boolean hostSliceClear(
			RoomSessionState session, int margin, int scanBudget) {
		RoomDimensions dimensions = session.dimensions();
		int width = dimensions.width() + margin * 2;
		int height = dimensions.height() + margin * 2;
		int depth = dimensions.depth() + margin * 2;
		int volume = Math.multiplyExact(Math.multiplyExact(width, height), depth);
		int cursor = Math.floorMod(session.hostScanCursor(), volume);
		BlockPos min = session.anchor().add(-margin, -margin, -margin);
		for (int inspected = 0; inspected < Math.min(scanBudget, volume); inspected++) {
			int x = cursor % width;
			int yz = cursor / width;
			int z = yz % depth;
			int y = yz / depth;
			BlockPos position = min.add(x, y, z);
			if (session.world().isChunkLoaded(position)
					&& !session.world().getBlockState(position).isAir()) {
				session.hostScanCursor((cursor + 1) % volume);
				return false;
			}
			cursor = (cursor + 1) % volume;
		}
		session.hostScanCursor(cursor);
		return true;
	}

	private static Box hostBox(BlockPos anchor, RoomDimensions dimensions, int margin) {
		return new Box(
			anchor.getX() - margin,
			anchor.getY() - margin,
			anchor.getZ() - margin,
			anchor.getX() + dimensions.width() + margin,
			anchor.getY() + dimensions.height() + margin,
			anchor.getZ() + dimensions.depth() + margin);
	}

	private static boolean insideWithMargin(
			Vec3d local, RoomDimensions dimensions, double margin) {
		return local.x >= -margin && local.x < dimensions.width() + margin
			&& local.y >= -margin && local.y < dimensions.height() + margin
			&& local.z >= -margin && local.z < dimensions.depth() + margin;
	}

	private static ServerState requireState(@Nullable MinecraftServer server) {
		ServerState state = server == null ? null : SERVERS.get(server);
		if (state == null) {
			throw new IllegalStateException("G'Lib virtual-room runtime is not running");
		}
		return state;
	}

	private static RoomRepositorySynchronizer requireRepository(MinecraftServer server) {
		ServerState state = requireState(server);
		if (state.repository() == null) {
			String configurationError = state.repositoryConfigurationError();
			throw new IllegalStateException(configurationError == null
				? "The virtual-room repository is not configured"
				: configurationError);
		}
		return state.repository();
	}

	private static void requireEntryCooldown(
			ServerState state, ServerPlayerEntity player) throws IOException {
		Long previous = state.lastEntryTicks().get(player.getUuid());
		if (previous != null && state.tick() - previous < 40L) {
			throw new IOException("Wait two seconds before entering or joining another virtual room");
		}
		state.lastEntryTicks().put(player.getUuid(), state.tick());
	}

	private static void requireSupportedClient(ServerPlayerEntity player) throws IOException {
		if (!RoomOverlayNetworking.supported(player)) {
			throw new IOException("Your client does not support G'Lib virtual-room packets");
		}
	}

	private static void reportRefresh(GlibRoomLibrary.ChangeSet changes) {
		for (Map.Entry<java.nio.file.Path, String> error : changes.errors().entrySet()) {
			LOGGER.warn("Ignoring invalid room archive {}: {}", error.getKey(), error.getValue());
		}
	}

	private static String shortId(UUID id) {
		return id.toString().substring(0, 8);
	}

	private static OverlayInfo overlayInfo(RoomSessionState session) {
		return new OverlayInfo(
			session.id(),
			session.anchor(),
			session.dimensions().width(),
			session.dimensions().height(),
			session.dimensions().depth());
	}

	public record OverlayInfo(
			UUID sessionId,
			BlockPos anchor,
			int width,
			int height,
			int depth) {
	}

	private record PlayerKey(@Nullable MinecraftServer server, UUID playerId) {
	}

	private static final class ServerState {
		private final GlibRoomLibrary library;
		private final RoomReturnStore returnStore;
		private final String returnStoreError;
		private final Map<UUID, RoomSessionState> sessions = new LinkedHashMap<>();
		private final Map<UUID, Long> lastEntryTicks = new LinkedHashMap<>();
		private List<VirtualRoom> compatibleRooms = List.of();
		private RoomRepositorySynchronizer repository;
		private String repositoryConfigurationError;
		private long tick;

		private ServerState(
				GlibRoomLibrary library,
				@Nullable RoomReturnStore returnStore,
				@Nullable String returnStoreError) {
			this.library = library;
			this.returnStore = returnStore;
			this.returnStoreError = returnStoreError;
		}

		private GlibRoomLibrary library() {
			return library;
		}

		@Nullable
		private RoomReturnStore returnStore() {
			return returnStore;
		}

		@Nullable
		private String returnStoreError() {
			return returnStoreError;
		}

		private Map<UUID, RoomSessionState> sessions() {
			return sessions;
		}

		private Map<UUID, Long> lastEntryTicks() {
			return lastEntryTicks;
		}

		private List<VirtualRoom> compatibleRooms() {
			return compatibleRooms;
		}

		private void refreshCompatibleRooms() {
			compatibleRooms = library.rooms().stream()
				.filter(room -> validationError(room).isEmpty())
				.toList();
		}

		@Nullable
		private RoomRepositorySynchronizer repository() {
			return repository;
		}

		private void repository(RoomRepositorySynchronizer repository) {
			this.repository = repository;
		}

		@Nullable
		private String repositoryConfigurationError() {
			return repositoryConfigurationError;
		}

		private void repositoryConfigurationError(String error) {
			this.repositoryConfigurationError = error;
		}

		private long tick() {
			return tick;
		}

		private void tick(long tick) {
			this.tick = tick;
		}
	}
}
