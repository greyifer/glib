package io.github.caspe.glib.api.timer;

public final class GlibRoundTimer {
	private String phase = "idle";
	private int remainingTicks;
	private boolean running;

	public String phase() {
		return phase;
	}

	public int remainingTicks() {
		return remainingTicks;
	}

	public boolean running() {
		return running;
	}

	public GlibRoundTimer start(String phase, int durationTicks) {
		this.phase = cleanPhase(phase);
		this.remainingTicks = Math.max(0, durationTicks);
		this.running = this.remainingTicks > 0;
		return this;
	}

	public GlibRoundTimer stop() {
		phase = "idle";
		remainingTicks = 0;
		running = false;
		return this;
	}

	public boolean tick() {
		if (!running) {
			return false;
		}
		remainingTicks = Math.max(0, remainingTicks - 1);
		if (remainingTicks == 0) {
			running = false;
			return true;
		}
		return false;
	}

	public Snapshot snapshot(String label) {
		return new Snapshot(phase, label == null ? phase : label, remainingTicks, running);
	}

	private static String cleanPhase(String phase) {
		return phase == null || phase.isBlank() ? "idle" : phase;
	}

	public record Snapshot(String phase, String label, int remainingTicks, boolean visible) {
	}
}
