package io.github.caspe.glib.api.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class GlibScreenAnimator {
	private final long openedAtNanos = System.nanoTime();
	private final float openSeconds;
	private final float closeSeconds;
	private final double openDistance;
	private final double closeExtraDistance;
	private boolean closing;
	private long closingStartedAtNanos;

	public GlibScreenAnimator(float openSeconds, float closeSeconds, double openDistance, double closeExtraDistance) {
		this.openSeconds = openSeconds;
		this.closeSeconds = closeSeconds;
		this.openDistance = openDistance;
		this.closeExtraDistance = closeExtraDistance;
	}

	public double verticalOffset(int screenHeight) {
		if (closing) {
			return -(screenHeight + closeExtraDistance) * GlibUi.easeInCubic(closeProgress());
		}
		return -openDistance * (1.0D - GlibUi.easeOutCubic(openProgress()));
	}

	public boolean requestClose() {
		if (closing) {
			return false;
		}
		closing = true;
		closingStartedAtNanos = System.nanoTime();
		return true;
	}

	public boolean closing() {
		return closing;
	}

	public boolean closeComplete() {
		return closing && closeProgress() >= 1.0F;
	}

	public float secondsSinceOpen() {
		return (System.nanoTime() - openedAtNanos) / 1_000_000_000.0F;
	}

	public float openProgress() {
		return Math.min(1.0F, secondsSinceOpen() / openSeconds);
	}

	public float closeProgress() {
		if (!closing) {
			return 0.0F;
		}
		float seconds = (System.nanoTime() - closingStartedAtNanos) / 1_000_000_000.0F;
		return Math.min(1.0F, seconds / closeSeconds);
	}
}
