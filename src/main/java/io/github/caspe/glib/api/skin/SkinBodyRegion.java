package io.github.caspe.glib.api.skin;

import java.util.Map;

import net.minecraft.util.math.Vec3d;

public record SkinBodyRegion(
	SkinBodyPart part,
	double minX,
	double minY,
	double minZ,
	double maxX,
	double maxY,
	double maxZ,
	Map<SkinSurface, SkinRegion> regions
) {
	public SkinRegion region(SkinSurface surface) {
		SkinRegion region = regions.get(surface);
		if (region == null) {
			throw new IllegalArgumentException("No region for " + part + " " + surface);
		}
		return region;
	}

	public Vec3d center(SkinSurface surface) {
		return switch (surface) {
			case TOP -> new Vec3d(mid(minX, maxX), maxY, mid(minZ, maxZ));
			case BOTTOM -> new Vec3d(mid(minX, maxX), minY, mid(minZ, maxZ));
			case RIGHT -> new Vec3d(minX, mid(minY, maxY), mid(minZ, maxZ));
			case LEFT -> new Vec3d(maxX, mid(minY, maxY), mid(minZ, maxZ));
			case FRONT -> new Vec3d(mid(minX, maxX), mid(minY, maxY), maxZ);
			case BACK -> new Vec3d(mid(minX, maxX), mid(minY, maxY), minZ);
		};
	}

	public Vec3d normal(SkinSurface surface) {
		return switch (surface) {
			case TOP -> new Vec3d(0.0D, 1.0D, 0.0D);
			case BOTTOM -> new Vec3d(0.0D, -1.0D, 0.0D);
			case RIGHT -> new Vec3d(-1.0D, 0.0D, 0.0D);
			case LEFT -> new Vec3d(1.0D, 0.0D, 0.0D);
			case FRONT -> new Vec3d(0.0D, 0.0D, 1.0D);
			case BACK -> new Vec3d(0.0D, 0.0D, -1.0D);
		};
	}

	public Vec3d point(SkinSurface surface, double u, double v) {
		Vec3d[] corners = corners(surface);
		Vec3d uAxis = corners[1].subtract(corners[0]);
		Vec3d vAxis = corners[3].subtract(corners[0]);
		return corners[0].add(uAxis.multiply(u)).add(vAxis.multiply(v));
	}

	public Vec3d[] corners(SkinSurface surface) {
		return switch (surface) {
			case TOP -> new Vec3d[] {
				new Vec3d(minX, maxY, minZ),
				new Vec3d(maxX, maxY, minZ),
				new Vec3d(maxX, maxY, maxZ),
				new Vec3d(minX, maxY, maxZ)
			};
			case BOTTOM -> new Vec3d[] {
				new Vec3d(minX, minY, maxZ),
				new Vec3d(maxX, minY, maxZ),
				new Vec3d(maxX, minY, minZ),
				new Vec3d(minX, minY, minZ)
			};
			case RIGHT -> new Vec3d[] {
				new Vec3d(minX, maxY, minZ),
				new Vec3d(minX, maxY, maxZ),
				new Vec3d(minX, minY, maxZ),
				new Vec3d(minX, minY, minZ)
			};
			case FRONT -> new Vec3d[] {
				new Vec3d(minX, maxY, maxZ),
				new Vec3d(maxX, maxY, maxZ),
				new Vec3d(maxX, minY, maxZ),
				new Vec3d(minX, minY, maxZ)
			};
			case LEFT -> new Vec3d[] {
				new Vec3d(maxX, maxY, maxZ),
				new Vec3d(maxX, maxY, minZ),
				new Vec3d(maxX, minY, minZ),
				new Vec3d(maxX, minY, maxZ)
			};
			case BACK -> new Vec3d[] {
				new Vec3d(maxX, maxY, minZ),
				new Vec3d(minX, maxY, minZ),
				new Vec3d(minX, minY, minZ),
				new Vec3d(maxX, minY, minZ)
			};
		};
	}

	public double pixelWorldSize(SkinSurface surface) {
		SkinRegion region = region(surface);
		Vec3d[] corners = corners(surface);
		double uSize = corners[1].distanceTo(corners[0]) / Math.max(1, region.width());
		double vSize = corners[3].distanceTo(corners[0]) / Math.max(1, region.height());
		return (uSize + vSize) * 0.5D;
	}

	private static double mid(double min, double max) {
		return (min + max) * 0.5D;
	}
}
