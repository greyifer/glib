package io.github.caspe.glib.api.skin;

import java.util.Objects;

public final class SkinPaintSession {
	private MutableSkinTexture texture;
	private SkinBrush brush = SkinBrush.DEFAULT;
	private int color = 0xFFFFFFFF;

	public SkinPaintSession(MutableSkinTexture texture) {
		this.texture = Objects.requireNonNull(texture, "texture");
	}

	public MutableSkinTexture texture() {
		return texture;
	}

	public SkinPaintSession texture(MutableSkinTexture texture) {
		this.texture = Objects.requireNonNull(texture, "texture");
		return this;
	}

	public SkinBrush brush() {
		return brush;
	}

	public SkinPaintSession brush(SkinBrush brush) {
		this.brush = Objects.requireNonNullElse(brush, SkinBrush.DEFAULT);
		return this;
	}

	public SkinPaintSession resizeBrush(int amount) {
		brush = brush.resized(amount);
		return this;
	}

	public int color() {
		return color;
	}

	public SkinPaintSession color(int color) {
		this.color = color | 0xFF000000;
		return this;
	}

	public SkinPaintSession pick(SkinPaintTarget target) {
		color(SkinPainter.sample(texture, target));
		return this;
	}

	public SkinPaintSession paint(SkinPaintTarget target) {
		SkinPainter.paint(texture, target, brush, color);
		return this;
	}
}
