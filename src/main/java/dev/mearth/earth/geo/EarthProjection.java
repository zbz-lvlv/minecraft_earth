package dev.mearth.earth.geo;

public final class EarthProjection {
	private static final double EARTH_RADIUS_METERS = 6_378_137.0;
	private static final double MAX_MERCATOR_LAT = 85.05112878;

	private EarthProjection() {
	}

	public static double clampLatitude(double latitude) {
		return Math.max(-MAX_MERCATOR_LAT, Math.min(MAX_MERCATOR_LAT, latitude));
	}

	public static double longitudeToBlockX(double longitude, double metersPerBlock) {
		double meters = Math.toRadians(longitude) * EARTH_RADIUS_METERS;
		return meters / metersPerBlock;
	}

	public static double latitudeToBlockZ(double latitude, double metersPerBlock) {
		double clamped = clampLatitude(latitude);
		double mercatorY = EARTH_RADIUS_METERS * Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(clamped) / 2.0));
		return -mercatorY / metersPerBlock;
	}

	public static double blockXToLongitude(double blockX, double metersPerBlock) {
		double meters = blockX * metersPerBlock;
		return Math.toDegrees(meters / EARTH_RADIUS_METERS);
	}

	public static double blockZToLatitude(double blockZ, double metersPerBlock) {
		double mercatorY = -blockZ * metersPerBlock;
		double latitude = 2.0 * Math.atan(Math.exp(mercatorY / EARTH_RADIUS_METERS)) - Math.PI / 2.0;
		return clampLatitude(Math.toDegrees(latitude));
	}
}
