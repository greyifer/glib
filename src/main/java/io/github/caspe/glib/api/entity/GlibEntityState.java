package io.github.caspe.glib.api.entity;

/** Generic runtime state selected by an editor owned by a consuming mod. */
public record GlibEntityState(
	boolean aiEnabled,
	boolean invulnerable,
	boolean shadowVisible,
	boolean movementLocked
) {
	public static GlibEntityState protectedDisplay() {
		return new GlibEntityState(false, true, true, true);
	}
}
