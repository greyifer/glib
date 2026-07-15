package io.github.caspe.glib.api.skin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.github.caspe.glib.client.ClientTasks;
import io.github.caspe.glib.client.DynamicSkinTextures;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public final class MutableSkinTexture implements AutoCloseable {
	private static final AtomicLong NEXT_ID = new AtomicLong();

	private final Object imageLock = new Object();
	private final Identifier id;
	private final NativeImage image;
	private final NativeImageBackedTexture texture;
	private final SkinEditHistory history = new SkinEditHistory(this);
	private volatile boolean closed;
	private volatile boolean autoUpload = true;

	private MutableSkinTexture(Identifier id, NativeImage image, NativeImageBackedTexture texture) {
		this.id = id;
		this.image = image;
		this.texture = texture;
	}

	public static CompletableFuture<MutableSkinTexture> blank(String prefix, int width, int height) {
		return create(prefix, new NativeImage(width, height, false));
	}

	public static CompletableFuture<MutableSkinTexture> copyOf(String prefix, NativeImage source) {
		Objects.requireNonNull(source, "source");
		NativeImage copy = SkinImages.copy(source);
		CompletableFuture<MutableSkinTexture> future = create(prefix, copy);
		future.whenComplete((texture, throwable) -> {
			if (throwable != null) {
				copy.close();
			}
		});
		return future;
	}

	/**
	 * Creates a live texture and takes ownership of {@code image}. The image will be closed when this texture is closed.
	 */
	public static CompletableFuture<MutableSkinTexture> create(String prefix, NativeImage image) {
		Objects.requireNonNull(image, "image");
		return ClientTasks.supply(() -> {
			Identifier id = nextId(prefix);
			NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
			MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
			texture.upload();
			return new MutableSkinTexture(id, image, texture);
		});
	}

	public Identifier id() {
		return id;
	}

	public Identifier textureId() {
		return id;
	}

	public int width() {
		return image.getWidth();
	}

	public int height() {
		return image.getHeight();
	}

	public boolean autoUpload() {
		return autoUpload;
	}

	public SkinEditHistory history() {
		return history;
	}

	public MutableSkinTexture autoUpload(boolean autoUpload) {
		this.autoUpload = autoUpload;
		return this;
	}

	public int getPixelArgb(int x, int y) {
		synchronized (imageLock) {
			checkOpen();
			return SkinColor.nativeToArgb(image.getColor(x, y));
		}
	}

	public MutableSkinTexture setPixelArgb(int x, int y, int argb) {
		synchronized (imageLock) {
			checkOpen();
			image.setColor(x, y, SkinColor.argbToNative(argb));
		}
		markDirty();
		return this;
	}

	public int getNativePixel(int x, int y) {
		synchronized (imageLock) {
			checkOpen();
			return image.getColor(x, y);
		}
	}

	public MutableSkinTexture setNativePixel(int x, int y, int nativeColor) {
		synchronized (imageLock) {
			checkOpen();
			image.setColor(x, y, nativeColor);
		}
		markDirty();
		return this;
	}

	public MutableSkinTexture fillArgb(int x, int y, int width, int height, int argb) {
		synchronized (imageLock) {
			checkOpen();
			image.fillRect(x, y, width, height, SkinColor.argbToNative(argb));
		}
		markDirty();
		return this;
	}

	public MutableSkinTexture fillRegionArgb(SkinRegion region, int argb) {
		Objects.requireNonNull(region, "region");
		return fillArgb(region.x(), region.y(), region.width(), region.height(), argb);
	}

	public int getRegionPixelArgb(SkinRegion region, int localX, int localY) {
		Objects.requireNonNull(region, "region");
		requireRegionPixel(region, localX, localY);
		return getPixelArgb(region.x() + localX, region.y() + localY);
	}

	public MutableSkinTexture setRegionPixelArgb(SkinRegion region, int localX, int localY, int argb) {
		Objects.requireNonNull(region, "region");
		requireRegionPixel(region, localX, localY);
		return setPixelArgb(region.x() + localX, region.y() + localY, argb);
	}

	public MutableSkinTexture edit(Consumer<NativeImage> editor) {
		Objects.requireNonNull(editor, "editor");
		synchronized (imageLock) {
			checkOpen();
			editor.accept(image);
		}
		markDirty();
		return this;
	}

	public MutableSkinTexture editRegion(SkinRegion region, Consumer<NativeImage> editor) {
		Objects.requireNonNull(region, "region");
		Objects.requireNonNull(editor, "editor");
		NativeImage regionImage;
		synchronized (imageLock) {
			checkOpen();
			regionImage = SkinImages.copyRegion(image, region);
		}
		try {
			editor.accept(regionImage);
			pasteRegion(region, regionImage);
		} finally {
			regionImage.close();
		}
		return this;
	}

	public NativeImage copyImage() {
		synchronized (imageLock) {
			checkOpen();
			return SkinImages.copy(image);
		}
	}

	public NativeImage copyRegion(SkinRegion region) {
		Objects.requireNonNull(region, "region");
		synchronized (imageLock) {
			checkOpen();
			return SkinImages.copyRegion(image, region);
		}
	}

	public MutableSkinTexture pasteRegion(SkinRegion region, NativeImage source) {
		Objects.requireNonNull(region, "region");
		Objects.requireNonNull(source, "source");
		if (source.getWidth() != region.width() || source.getHeight() != region.height()) {
			throw new IllegalArgumentException("Expected region image " + region.width() + "x" + region.height() + ", got " + source.getWidth() + "x" + source.getHeight());
		}
		synchronized (imageLock) {
			checkOpen();
			for (int y = 0; y < region.height(); y++) {
				for (int x = 0; x < region.width(); x++) {
					image.setColor(region.x() + x, region.y() + y, source.getColor(x, y));
				}
			}
		}
		markDirty();
		return this;
	}

	public MutableSkinTexture applyLayer(SkinLayer layer) {
		Objects.requireNonNull(layer, "layer");
		return blendRegion(layer.region(), layer.image(), layer.blendMode(), layer.opacity());
	}

	public MutableSkinTexture blendRegion(SkinRegion region, NativeImage layer, SkinBlendMode blendMode, float opacity) {
		Objects.requireNonNull(region, "region");
		Objects.requireNonNull(layer, "layer");
		SkinBlendMode mode = blendMode == null ? SkinBlendMode.NORMAL : blendMode;
		if (layer.getWidth() != region.width() || layer.getHeight() != region.height()) {
			throw new IllegalArgumentException("Layer image must match region size");
		}
		synchronized (imageLock) {
			checkOpen();
			for (int y = 0; y < region.height(); y++) {
				for (int x = 0; x < region.width(); x++) {
					int targetX = region.x() + x;
					int targetY = region.y() + y;
					int sourceArgb = SkinColor.nativeToArgb(layer.getColor(x, y));
					int destinationArgb = SkinColor.nativeToArgb(image.getColor(targetX, targetY));
					image.setColor(targetX, targetY, SkinColor.argbToNative(mode.blend(sourceArgb, destinationArgb, opacity)));
				}
			}
		}
		markDirty();
		return this;
	}

	public MutableSkinTexture blendRegionArgb(SkinRegion region, int argb, SkinBlendMode blendMode, float opacity) {
		Objects.requireNonNull(region, "region");
		SkinBlendMode mode = blendMode == null ? SkinBlendMode.NORMAL : blendMode;
		synchronized (imageLock) {
			checkOpen();
			for (int y = 0; y < region.height(); y++) {
				for (int x = 0; x < region.width(); x++) {
					int targetX = region.x() + x;
					int targetY = region.y() + y;
					int destinationArgb = SkinColor.nativeToArgb(image.getColor(targetX, targetY));
					image.setColor(targetX, targetY, SkinColor.argbToNative(mode.blend(argb, destinationArgb, opacity)));
				}
			}
		}
		markDirty();
		return this;
	}

	public MutableSkinTexture tintRegion(SkinRegion region, int tintArgb, float opacity) {
		return blendRegionArgb(region, tintArgb, SkinBlendMode.TINT, opacity);
	}

	public MutableSkinTexture replaceImage(NativeImage source) {
		Objects.requireNonNull(source, "source");
		if (source.getWidth() != width() || source.getHeight() != height()) {
			throw new IllegalArgumentException("Expected image " + width() + "x" + height() + ", got " + source.getWidth() + "x" + source.getHeight());
		}
		synchronized (imageLock) {
			checkOpen();
			image.copyFrom(source);
		}
		markDirty();
		return this;
	}

	public MutableSkinTexture replaceNativePixels(int width, int height, int[] nativePixels) {
		Objects.requireNonNull(nativePixels, "nativePixels");
		if (width != width() || height != height()) {
			throw new IllegalArgumentException("Expected pixels for " + width() + "x" + height() + ", texture is " + width() + "x" + height());
		}
		if (nativePixels.length != width * height) {
			throw new IllegalArgumentException("Expected " + (width * height) + " pixels, got " + nativePixels.length);
		}
		synchronized (imageLock) {
			checkOpen();
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					image.setColor(x, y, nativePixels[y * width + x]);
				}
			}
		}
		markDirty();
		return this;
	}

	public int[] copyNativePixels() {
		synchronized (imageLock) {
			checkOpen();
			int width = width();
			int height = height();
			int[] pixels = new int[width * height];
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					pixels[y * width + x] = image.getColor(x, y);
				}
			}
			return pixels;
		}
	}

	public SkinSyncSnapshot toSyncSnapshot(UUID uuid, SkinTextures.Model model) {
		SkinModel syncModel = model == SkinTextures.Model.SLIM ? SkinModel.SLIM : SkinModel.WIDE;
		return new SkinSyncSnapshot(uuid, width(), height(), copyNativePixels(), syncModel);
	}

	public MutableSkinTexture markDirty() {
		if (autoUpload) {
			DynamicSkinTextures.markDirty(this);
		}
		SkinEvents.fireChanged(this);
		return this;
	}

	public CompletableFuture<Void> uploadNow() {
		return ClientTasks.run(this::uploadOnClientThread);
	}

	public void uploadOnClientThread() {
		synchronized (imageLock) {
			if (!closed) {
				texture.upload();
			}
		}
	}

	public MutableSkinTexture overridePlayer(UUID uuid, SkinTextures baseTextures) {
		SkinOverrideRegistry.set(uuid, this, baseTextures);
		return this;
	}

	public MutableSkinTexture overridePlayer(UUID uuid, SkinTextures.Model model) {
		SkinOverrideRegistry.set(uuid, this, model);
		return this;
	}

	public boolean isClosed() {
		return closed;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			DynamicSkinTextures.unregister(this);
			ClientTasks.run(() -> MinecraftClient.getInstance().getTextureManager().destroyTexture(id));
		}
	}

	private void checkOpen() {
		if (closed) {
			throw new IllegalStateException("Texture is already closed");
		}
	}

	private static void requireRegionPixel(SkinRegion region, int localX, int localY) {
		if (!region.contains(localX, localY)) {
			throw new IndexOutOfBoundsException("Pixel " + localX + "," + localY + " is outside region " + region.name());
		}
	}

	private static Identifier nextId(String prefix) {
		String cleanPrefix = sanitizePrefix(prefix);
		return Identifier.of("glib", "dynamic/" + cleanPrefix + "/" + NEXT_ID.incrementAndGet());
	}

	private static String sanitizePrefix(String prefix) {
		if (prefix == null || prefix.isBlank()) {
			return "skin";
		}

		String sanitized = prefix.toLowerCase().replaceAll("[^a-z0-9/._-]", "_");
		while (sanitized.contains("//")) {
			sanitized = sanitized.replace("//", "/");
		}
		return sanitized.replaceAll("^/+", "").replaceAll("/+$", "");
	}
}
