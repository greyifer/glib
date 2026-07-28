package io.github.caspe.glib.api.room;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;

import java.util.Objects;
import java.util.Optional;

/**
 * Server-side interaction hook for packet-only room blocks and saved hotspots.
 *
 * <p>Return {@link ActionResult#PASS} to let another callback or G'Lib's
 * door/lever/button simulation handle the interaction. Any other result consumes
 * it. The real host world is never invoked.</p>
 */
@FunctionalInterface
public interface RoomInteractionCallback {
	Event<RoomInteractionCallback> EVENT = EventFactory.createArrayBacked(
		RoomInteractionCallback.class,
		callbacks -> context -> {
			for (RoomInteractionCallback callback : callbacks) {
				ActionResult result = callback.interact(context);
				if (result != ActionResult.PASS) {
					return result;
				}
			}
			return ActionResult.PASS;
		});

	ActionResult interact(Context context);

	record Context(
			ServerPlayerEntity player,
			RoomSessionInfo session,
			RoomPosition position,
			Kind kind,
			Hand hand,
			BlockHitResult hit,
			Optional<RoomMarker> marker) {
		public Context {
			Objects.requireNonNull(player, "player");
			Objects.requireNonNull(session, "session");
			Objects.requireNonNull(position, "position");
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(hand, "hand");
			Objects.requireNonNull(hit, "hit");
			marker = Objects.requireNonNull(marker, "marker");
		}
	}

	enum Kind {
		USE,
		ATTACK
	}
}
