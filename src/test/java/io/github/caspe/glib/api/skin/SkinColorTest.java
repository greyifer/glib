package io.github.caspe.glib.api.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class SkinColorTest {
	@Test
	void argbRoundTripsThroughNativeColor() {
		int argb = 0x8044AAEF;
		assertEquals(argb, SkinColor.nativeToArgb(SkinColor.argbToNative(argb)));
	}

	@Test
	void fullyTransparentTopKeepsBottomColor() {
		assertEquals(0xFF123456, SkinColor.blendArgb(0x00112233, 0xFF123456));
	}

	@Test
	void fullyOpaqueTopReplacesBottomColor() {
		assertEquals(0xFFAABBCC, SkinColor.blendArgb(0xFFAABBCC, 0xFF123456));
	}

	@Test
	void blendModeOpacityZeroKeepsDestinationColor() {
		assertEquals(0xFF123456, SkinBlendMode.SCREEN.blend(0xFFFFFFFF, 0xFF123456, 0.0F));
	}

	@Test
	void multiplyWithWhiteKeepsDestinationColor() {
		assertEquals(0xFF336699, SkinBlendMode.MULTIPLY.blend(0xFFFFFFFF, 0xFF336699));
	}

	@Test
	void screenWithBlackKeepsDestinationColor() {
		assertEquals(0xFF336699, SkinBlendMode.SCREEN.blend(0xFF000000, 0xFF336699));
	}

	@Test
	void overlayUsesDestinationAsBase() {
		assertEquals(0xFF404040, SkinBlendMode.OVERLAY.blend(0xFFFFFFFF, 0xFF202020));
	}
}
