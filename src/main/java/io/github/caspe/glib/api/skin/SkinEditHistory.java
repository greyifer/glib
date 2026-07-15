package io.github.caspe.glib.api.skin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.texture.NativeImage;

@Environment(EnvType.CLIENT)
public final class SkinEditHistory {
	private final MutableSkinTexture texture;
	private final Deque<NativeImage> undo = new ArrayDeque<>();
	private final Deque<NativeImage> redo = new ArrayDeque<>();
	private int maxDepth = 32;

	SkinEditHistory(MutableSkinTexture texture) {
		this.texture = texture;
	}

	public SkinEditHistory maxDepth(int maxDepth) {
		if (maxDepth < 1) {
			throw new IllegalArgumentException("maxDepth must be at least 1");
		}
		this.maxDepth = maxDepth;
		trim(undo);
		trim(redo);
		return this;
	}

	public int maxDepth() {
		return maxDepth;
	}

	public SkinEditHistory snapshot() {
		undo.push(texture.copyImage());
		trim(undo);
		clear(redo);
		return this;
	}

	public MutableSkinTexture edit(Runnable editor) {
		Objects.requireNonNull(editor, "editor");
		snapshot();
		editor.run();
		return texture;
	}

	public boolean undo() {
		if (undo.isEmpty()) {
			return false;
		}
		redo.push(texture.copyImage());
		trim(redo);
		return restore(undo.pop());
	}

	public boolean redo() {
		if (redo.isEmpty()) {
			return false;
		}
		undo.push(texture.copyImage());
		trim(undo);
		return restore(redo.pop());
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	public void clear() {
		clear(undo);
		clear(redo);
	}

	private boolean restore(NativeImage image) {
		try {
			texture.replaceImage(image);
			return true;
		} finally {
			image.close();
		}
	}

	private void trim(Deque<NativeImage> stack) {
		while (stack.size() > maxDepth) {
			stack.removeLast().close();
		}
	}

	private static void clear(Deque<NativeImage> stack) {
		while (!stack.isEmpty()) {
			stack.pop().close();
		}
	}
}
