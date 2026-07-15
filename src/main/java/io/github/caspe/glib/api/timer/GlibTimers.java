package io.github.caspe.glib.api.timer;

/**
 * Small client HUD timer API for mods that want a polished countdown without
 * owning the render plumbing themselves.
 */
public final class GlibTimers {
	private static final long NANOS_PER_TICK = 50_000_000L;
	private static final long FINISHED_HOLD_NANOS = 1_250_000_000L;
	private static final Object LOCK = new Object();

	private static String label = "";
	private static int remainingTicks;
	private static long endNanos;
	private static boolean countdown;
	private static boolean visible;
	private static long revision;

	private GlibTimers() {
	}

	public static void start(String label, int durationTicks) {
		synchronized (LOCK) {
			GlibTimers.label = cleanLabel(label);
			GlibTimers.remainingTicks = Math.max(0, durationTicks);
			GlibTimers.endNanos = System.nanoTime() + (long) GlibTimers.remainingTicks * NANOS_PER_TICK;
			GlibTimers.countdown = true;
			GlibTimers.visible = true;
			GlibTimers.revision++;
		}
	}

	public static void show(String label, int remainingTicks) {
		synchronized (LOCK) {
			GlibTimers.label = cleanLabel(label);
			GlibTimers.remainingTicks = Math.max(0, remainingTicks);
			GlibTimers.endNanos = 0L;
			GlibTimers.countdown = false;
			GlibTimers.visible = true;
			GlibTimers.revision++;
		}
	}

	public static void setRemainingTicks(int remainingTicks) {
		synchronized (LOCK) {
			GlibTimers.remainingTicks = Math.max(0, remainingTicks);
			GlibTimers.endNanos = 0L;
			GlibTimers.countdown = false;
			GlibTimers.visible = true;
			GlibTimers.revision++;
		}
	}

	public static void setLabel(String label) {
		synchronized (LOCK) {
			GlibTimers.label = cleanLabel(label);
			GlibTimers.revision++;
		}
	}

	public static void hide() {
		synchronized (LOCK) {
			GlibTimers.visible = false;
			GlibTimers.revision++;
		}
	}

	public static TimerSnapshot snapshot() {
		synchronized (LOCK) {
			if (!visible) {
				return new TimerSnapshot(label, remainingTicks, false, revision);
			}

			int ticks = remainingTicks;
			boolean showing = true;
			if (countdown) {
				long now = System.nanoTime();
				long nanosLeft = endNanos - now;
				ticks = nanosLeft <= 0L ? 0 : (int) Math.ceil(nanosLeft / (double) NANOS_PER_TICK);
				showing = ticks > 0 || now - endNanos < FINISHED_HOLD_NANOS;
			}
			return new TimerSnapshot(label, ticks, showing, revision);
		}
	}

	private static String cleanLabel(String label) {
		return label == null ? "" : label;
	}

	public record TimerSnapshot(String label, int remainingTicks, boolean visible, long revision) {
		public int wholeSeconds() {
			return (int) Math.ceil(Math.max(0, remainingTicks) / 20.0D);
		}
	}
}
