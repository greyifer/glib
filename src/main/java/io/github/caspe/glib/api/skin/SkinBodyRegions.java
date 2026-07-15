package io.github.caspe.glib.api.skin;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SkinBodyRegions {
	private static final double MODEL_ORIGIN_Y = 1.501D;
	private static final double PX = 1.0D / 16.0D;

	public static final SkinBodyRegion HEAD = part(SkinBodyPart.HEAD, -4.0D * PX, MODEL_ORIGIN_Y, -4.0D * PX, 4.0D * PX, MODEL_ORIGIN_Y + 8.0D * PX, 4.0D * PX,
		SkinRegions.HEAD_TOP, SkinRegions.HEAD_BOTTOM, SkinRegions.HEAD_RIGHT, SkinRegions.HEAD_FRONT, SkinRegions.HEAD_LEFT, SkinRegions.HEAD_BACK);
	public static final SkinBodyRegion BODY = part(SkinBodyPart.BODY, -4.0D * PX, MODEL_ORIGIN_Y - 12.0D * PX, -2.0D * PX, 4.0D * PX, MODEL_ORIGIN_Y, 2.0D * PX,
		SkinRegions.BODY_TOP, SkinRegions.BODY_BOTTOM, SkinRegions.BODY_RIGHT, SkinRegions.BODY_FRONT, SkinRegions.BODY_LEFT, SkinRegions.BODY_BACK);
	public static final SkinBodyRegion RIGHT_ARM = part(SkinBodyPart.RIGHT_ARM, -8.0D * PX, MODEL_ORIGIN_Y - 12.0D * PX, -2.0D * PX, -4.0D * PX, MODEL_ORIGIN_Y, 2.0D * PX,
		SkinRegions.RIGHT_ARM_TOP, SkinRegions.RIGHT_ARM_BOTTOM, SkinRegions.RIGHT_ARM_RIGHT, SkinRegions.RIGHT_ARM_FRONT, SkinRegions.RIGHT_ARM_LEFT, SkinRegions.RIGHT_ARM_BACK);
	public static final SkinBodyRegion LEFT_ARM = part(SkinBodyPart.LEFT_ARM, 4.0D * PX, MODEL_ORIGIN_Y - 12.0D * PX, -2.0D * PX, 8.0D * PX, MODEL_ORIGIN_Y, 2.0D * PX,
		SkinRegions.LEFT_ARM_TOP, SkinRegions.LEFT_ARM_BOTTOM, SkinRegions.LEFT_ARM_RIGHT, SkinRegions.LEFT_ARM_FRONT, SkinRegions.LEFT_ARM_LEFT, SkinRegions.LEFT_ARM_BACK);
	public static final SkinBodyRegion RIGHT_LEG = part(SkinBodyPart.RIGHT_LEG, -3.9D * PX, MODEL_ORIGIN_Y - 24.0D * PX, -2.0D * PX, 0.1D * PX, MODEL_ORIGIN_Y - 12.0D * PX, 2.0D * PX,
		SkinRegions.RIGHT_LEG_TOP, SkinRegions.RIGHT_LEG_BOTTOM, SkinRegions.RIGHT_LEG_RIGHT, SkinRegions.RIGHT_LEG_FRONT, SkinRegions.RIGHT_LEG_LEFT, SkinRegions.RIGHT_LEG_BACK);
	public static final SkinBodyRegion LEFT_LEG = part(SkinBodyPart.LEFT_LEG, -0.1D * PX, MODEL_ORIGIN_Y - 24.0D * PX, -2.0D * PX, 3.9D * PX, MODEL_ORIGIN_Y - 12.0D * PX, 2.0D * PX,
		SkinRegions.LEFT_LEG_TOP, SkinRegions.LEFT_LEG_BOTTOM, SkinRegions.LEFT_LEG_RIGHT, SkinRegions.LEFT_LEG_FRONT, SkinRegions.LEFT_LEG_LEFT, SkinRegions.LEFT_LEG_BACK);

	private static final List<SkinBodyRegion> ALL = List.of(HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG);

	private SkinBodyRegions() {
	}

	public static List<SkinBodyRegion> all() {
		return ALL;
	}

	public static Optional<SkinBodyRegion> byPart(SkinBodyPart part) {
		return ALL.stream().filter(region -> region.part() == part).findFirst();
	}

	public static SkinBodyRegion require(SkinBodyPart part) {
		return byPart(part).orElseThrow(() -> new IllegalArgumentException("Unknown body part: " + part));
	}

	private static SkinBodyRegion part(SkinBodyPart part, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, SkinRegion top, SkinRegion bottom, SkinRegion right, SkinRegion front, SkinRegion left, SkinRegion back) {
		Map<SkinSurface, SkinRegion> regions = new EnumMap<>(SkinSurface.class);
		regions.put(SkinSurface.TOP, top);
		regions.put(SkinSurface.BOTTOM, bottom);
		regions.put(SkinSurface.RIGHT, right);
		regions.put(SkinSurface.FRONT, front);
		regions.put(SkinSurface.LEFT, left);
		regions.put(SkinSurface.BACK, back);
		return new SkinBodyRegion(part, minX, minY, minZ, maxX, maxY, maxZ, Map.copyOf(regions));
	}
}
