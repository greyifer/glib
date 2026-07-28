package io.github.caspe.glib.room;

import io.github.caspe.glib.api.room.RoomPosition;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

final class RoomVirtualBlockView implements BlockView {
	private final RoomSessionState session;

	private RoomVirtualBlockView(RoomSessionState session) {
		this.session = session;
	}

	static Iterable<VoxelShape> collisions(
			RoomSessionState session, ServerPlayerEntity player, Box query) {
		RoomVirtualBlockView view = new RoomVirtualBlockView(session);
		ShapeContext context = ShapeContext.of(player);
		int minX = Math.max(0, MathHelper.floor(query.minX - session.anchor().getX()) - 1);
		int minY = Math.max(0, MathHelper.floor(query.minY - session.anchor().getY()) - 1);
		int minZ = Math.max(0, MathHelper.floor(query.minZ - session.anchor().getZ()) - 1);
		int maxX = Math.min(
			session.dimensions().width() - 1,
			MathHelper.floor(query.maxX - session.anchor().getX()) + 1);
		int maxY = Math.min(
			session.dimensions().height() - 1,
			MathHelper.floor(query.maxY - session.anchor().getY()) + 1);
		int maxZ = Math.min(
			session.dimensions().depth() - 1,
			MathHelper.floor(query.maxZ - session.anchor().getZ()) + 1);
		if (minX > maxX || minY > maxY || minZ > maxZ) {
			return List.of();
		}

		List<VoxelShape> result = new ArrayList<>();
		for (int y = minY; y <= maxY; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					RoomPosition local = new RoomPosition(x, y, z);
					BlockState state = session.stateAt(local);
					if (state.isAir()) {
						continue;
					}
					BlockPos worldPos = session.toWorld(local);
					VoxelShape shape = state.getCollisionShape(view, worldPos, context);
					if (shape.isEmpty()) {
						continue;
					}
					VoxelShape translated = shape.offset(
						worldPos.getX(), worldPos.getY(), worldPos.getZ());
					if (translated.getBoundingBox().intersects(query)) {
						result.add(translated);
					}
				}
			}
		}
		return List.copyOf(result);
	}

	@Override
	@Nullable
	public BlockEntity getBlockEntity(BlockPos pos) {
		return session.containsWorld(pos) ? null : session.world().getBlockEntity(pos);
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		return session.toLocal(pos)
			.map(session::stateAt)
			.orElseGet(() -> session.world().getBlockState(pos));
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public int getHeight() {
		return session.world().getHeight();
	}

	@Override
	public int getBottomY() {
		return session.world().getBottomY();
	}
}
