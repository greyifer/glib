package io.github.caspe.glib.api.skin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class SkinRegions {
	public static final SkinRegion FULL = new SkinRegion("full", 0, 0, 64, 64);
	public static final SkinRegion HEAD_TOP = new SkinRegion("headTop", 8, 0, 8, 8);
	public static final SkinRegion HEAD_BOTTOM = new SkinRegion("headBottom", 16, 0, 8, 8);
	public static final SkinRegion HEAD_RIGHT = new SkinRegion("headRight", 0, 8, 8, 8);
	public static final SkinRegion HEAD_FRONT = new SkinRegion("headFront", 8, 8, 8, 8);
	public static final SkinRegion HEAD_LEFT = new SkinRegion("headLeft", 16, 8, 8, 8);
	public static final SkinRegion HEAD_BACK = new SkinRegion("headBack", 24, 8, 8, 8);
	public static final SkinRegion HAT_TOP = new SkinRegion("hatTop", 40, 0, 8, 8);
	public static final SkinRegion HAT_BOTTOM = new SkinRegion("hatBottom", 48, 0, 8, 8);
	public static final SkinRegion HAT_RIGHT = new SkinRegion("hatRight", 32, 8, 8, 8);
	public static final SkinRegion HAT_FRONT = new SkinRegion("hatFront", 40, 8, 8, 8);
	public static final SkinRegion HAT_LEFT = new SkinRegion("hatLeft", 48, 8, 8, 8);
	public static final SkinRegion HAT_BACK = new SkinRegion("hatBack", 56, 8, 8, 8);
	public static final SkinRegion BODY_TOP = new SkinRegion("bodyTop", 20, 16, 8, 4);
	public static final SkinRegion BODY_BOTTOM = new SkinRegion("bodyBottom", 28, 16, 8, 4);
	public static final SkinRegion BODY_RIGHT = new SkinRegion("bodyRight", 16, 20, 4, 12);
	public static final SkinRegion BODY_FRONT = new SkinRegion("bodyFront", 20, 20, 8, 12);
	public static final SkinRegion BODY_LEFT = new SkinRegion("bodyLeft", 28, 20, 4, 12);
	public static final SkinRegion BODY_BACK = new SkinRegion("bodyBack", 32, 20, 8, 12);
	public static final SkinRegion RIGHT_ARM_TOP = new SkinRegion("rightArmTop", 44, 16, 4, 4);
	public static final SkinRegion RIGHT_ARM_BOTTOM = new SkinRegion("rightArmBottom", 48, 16, 4, 4);
	public static final SkinRegion RIGHT_ARM_RIGHT = new SkinRegion("rightArmRight", 40, 20, 4, 12);
	public static final SkinRegion RIGHT_ARM_FRONT = new SkinRegion("rightArmFront", 44, 20, 4, 12);
	public static final SkinRegion RIGHT_ARM_LEFT = new SkinRegion("rightArmLeft", 48, 20, 4, 12);
	public static final SkinRegion RIGHT_ARM_BACK = new SkinRegion("rightArmBack", 52, 20, 4, 12);
	public static final SkinRegion LEFT_ARM_TOP = new SkinRegion("leftArmTop", 36, 48, 4, 4);
	public static final SkinRegion LEFT_ARM_BOTTOM = new SkinRegion("leftArmBottom", 40, 48, 4, 4);
	public static final SkinRegion LEFT_ARM_RIGHT = new SkinRegion("leftArmRight", 32, 52, 4, 12);
	public static final SkinRegion LEFT_ARM_FRONT = new SkinRegion("leftArmFront", 36, 52, 4, 12);
	public static final SkinRegion LEFT_ARM_LEFT = new SkinRegion("leftArmLeft", 40, 52, 4, 12);
	public static final SkinRegion LEFT_ARM_BACK = new SkinRegion("leftArmBack", 44, 52, 4, 12);
	public static final SkinRegion RIGHT_LEG_TOP = new SkinRegion("rightLegTop", 4, 16, 4, 4);
	public static final SkinRegion RIGHT_LEG_BOTTOM = new SkinRegion("rightLegBottom", 8, 16, 4, 4);
	public static final SkinRegion RIGHT_LEG_RIGHT = new SkinRegion("rightLegRight", 0, 20, 4, 12);
	public static final SkinRegion RIGHT_LEG_FRONT = new SkinRegion("rightLegFront", 4, 20, 4, 12);
	public static final SkinRegion RIGHT_LEG_LEFT = new SkinRegion("rightLegLeft", 8, 20, 4, 12);
	public static final SkinRegion RIGHT_LEG_BACK = new SkinRegion("rightLegBack", 12, 20, 4, 12);
	public static final SkinRegion LEFT_LEG_TOP = new SkinRegion("leftLegTop", 20, 48, 4, 4);
	public static final SkinRegion LEFT_LEG_BOTTOM = new SkinRegion("leftLegBottom", 24, 48, 4, 4);
	public static final SkinRegion LEFT_LEG_RIGHT = new SkinRegion("leftLegRight", 16, 52, 4, 12);
	public static final SkinRegion LEFT_LEG_FRONT = new SkinRegion("leftLegFront", 20, 52, 4, 12);
	public static final SkinRegion LEFT_LEG_LEFT = new SkinRegion("leftLegLeft", 24, 52, 4, 12);
	public static final SkinRegion LEFT_LEG_BACK = new SkinRegion("leftLegBack", 28, 52, 4, 12);
	public static final SkinRegion FACE = HEAD_FRONT;
	public static final SkinRegion HAT = HAT_FRONT;
	public static final SkinRegion BODY = BODY_FRONT;
	public static final SkinRegion RIGHT_ARM = RIGHT_ARM_FRONT;
	public static final SkinRegion LEFT_ARM = LEFT_ARM_FRONT;
	public static final SkinRegion RIGHT_LEG = RIGHT_LEG_FRONT;
	public static final SkinRegion LEFT_LEG = LEFT_LEG_FRONT;

	private static final List<SkinRegion> ALL = List.of(
		FULL,
		HEAD_TOP, HEAD_BOTTOM, HEAD_RIGHT, HEAD_FRONT, HEAD_LEFT, HEAD_BACK,
		HAT_TOP, HAT_BOTTOM, HAT_RIGHT, HAT_FRONT, HAT_LEFT, HAT_BACK,
		BODY_TOP, BODY_BOTTOM, BODY_RIGHT, BODY_FRONT, BODY_LEFT, BODY_BACK,
		RIGHT_ARM_TOP, RIGHT_ARM_BOTTOM, RIGHT_ARM_RIGHT, RIGHT_ARM_FRONT, RIGHT_ARM_LEFT, RIGHT_ARM_BACK,
		LEFT_ARM_TOP, LEFT_ARM_BOTTOM, LEFT_ARM_RIGHT, LEFT_ARM_FRONT, LEFT_ARM_LEFT, LEFT_ARM_BACK,
		RIGHT_LEG_TOP, RIGHT_LEG_BOTTOM, RIGHT_LEG_RIGHT, RIGHT_LEG_FRONT, RIGHT_LEG_LEFT, RIGHT_LEG_BACK,
		LEFT_LEG_TOP, LEFT_LEG_BOTTOM, LEFT_LEG_RIGHT, LEFT_LEG_FRONT, LEFT_LEG_LEFT, LEFT_LEG_BACK
	);
	private static final Map<String, SkinRegion> BY_NAME = ALL.stream()
		.collect(Collectors.toUnmodifiableMap(region -> key(region.name()), Function.identity()));

	private SkinRegions() {
	}

	public static List<SkinRegion> all() {
		return ALL;
	}

	public static Optional<SkinRegion> byName(String name) {
		if (name == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(BY_NAME.get(key(name)));
	}

	public static SkinRegion require(String name) {
		return byName(name).orElseThrow(() -> new IllegalArgumentException("Unknown skin region: " + name));
	}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
