package io.github.caspe.glib.api.skin;

/**
 * Options for extracting a player face from a 64x64 skin.
 *
 * @param size the output image size in pixels
 * @param includeHatLayer whether the translucent hat layer at skin coordinates 40,8 should be composited over the face
 */
public record FaceOptions(int size, boolean includeHatLayer) {
	public static final FaceOptions DEFAULT = new FaceOptions(8, true);

	public FaceOptions {
		if (size < 1) {
			throw new IllegalArgumentException("Face size must be at least 1 pixel");
		}
	}

	public static FaceOptions ofSize(int size) {
		return new FaceOptions(size, true);
	}

	public FaceOptions withoutHatLayer() {
		return new FaceOptions(size, false);
	}

	public FaceOptions withHatLayer(boolean includeHatLayer) {
		return new FaceOptions(size, includeHatLayer);
	}
}
