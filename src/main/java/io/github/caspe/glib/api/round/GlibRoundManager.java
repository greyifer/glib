package io.github.caspe.glib.api.round;

import java.util.ArrayList;
import java.util.List;

import io.github.caspe.glib.api.timer.GlibRoundTimer;
import io.github.caspe.glib.api.timer.GlibSyncedTimers;
import net.minecraft.server.MinecraftServer;

public final class GlibRoundManager {
	private final String channel;
	private final List<Listener> listeners = new ArrayList<>();
	private Phase phase = Phase.IDLE;
	private int lobbyTicks;
	private int hideTicks;
	private int activeTicks;
	private int remainingTicks;

	public GlibRoundManager(String channel) {
		this.channel = channel;
	}

	public GlibRoundManager configure(int lobbyTicks, int hideTicks, int activeTicks) {
		this.lobbyTicks = Math.max(0, lobbyTicks);
		this.hideTicks = Math.max(0, hideTicks);
		this.activeTicks = Math.max(1, activeTicks);
		return this;
	}

	public GlibRoundManager addListener(Listener listener) {
		listeners.add(listener);
		return this;
	}

	public boolean start() {
		if (phase != Phase.IDLE) {
			return false;
		}
		enter(lobbyTicks > 0 ? Phase.LOBBY : hideTicks > 0 ? Phase.HIDING : Phase.ACTIVE);
		return true;
	}

	public void stop() {
		if (phase == Phase.IDLE) {
			return;
		}
		Phase previous = phase;
		phase = Phase.IDLE;
		remainingTicks = 0;
		for (Listener listener : listeners) {
			listener.onRoundEnded(previous);
		}
	}

	public void tick() {
		if (phase == Phase.IDLE) {
			return;
		}

		remainingTicks = Math.max(0, remainingTicks - 1);
		for (Listener listener : listeners) {
			listener.onTick(phase, remainingTicks);
		}
		if (remainingTicks > 0) {
			return;
		}

		if (phase == Phase.LOBBY) {
			enter(hideTicks > 0 ? Phase.HIDING : Phase.ACTIVE);
		} else if (phase == Phase.HIDING) {
			enter(Phase.ACTIVE);
		} else if (phase == Phase.ACTIVE) {
			stop();
		}
	}

	public void syncTimer(MinecraftServer server, String lobbyLabel, String hideLabel, String activeLabel) {
		if (phase == Phase.IDLE) {
			GlibSyncedTimers.hide(server);
			return;
		}
		GlibSyncedTimers.broadcast(server, channel, phase.id(), label(lobbyLabel, hideLabel, activeLabel), remainingTicks, true);
	}

	public GlibRoundTimer.Snapshot snapshot(String lobbyLabel, String hideLabel, String activeLabel) {
		return new GlibRoundTimer.Snapshot(phase.id(), label(lobbyLabel, hideLabel, activeLabel), remainingTicks, phase != Phase.IDLE);
	}

	public Phase phase() {
		return phase;
	}

	public int remainingTicks() {
		return remainingTicks;
	}

	public boolean active() {
		return phase != Phase.IDLE;
	}

	private String label(String lobbyLabel, String hideLabel, String activeLabel) {
		return switch (phase) {
			case LOBBY -> lobbyLabel;
			case HIDING -> hideLabel;
			case ACTIVE -> activeLabel;
			case IDLE -> "";
		};
	}

	private void enter(Phase next) {
		Phase previous = phase;
		phase = next;
		remainingTicks = switch (next) {
			case LOBBY -> lobbyTicks;
			case HIDING -> hideTicks;
			case ACTIVE -> activeTicks;
			case IDLE -> 0;
		};
		for (Listener listener : listeners) {
			listener.onPhaseChanged(previous, next);
		}
	}

	public enum Phase {
		IDLE("idle"),
		LOBBY("lobby"),
		HIDING("hiding"),
		ACTIVE("active");

		private final String id;

		Phase(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}
	}

	public interface Listener {
		default void onPhaseChanged(Phase previous, Phase next) {
		}

		default void onTick(Phase phase, int remainingTicks) {
		}

		default void onRoundEnded(Phase previous) {
		}
	}
}
