package dev.mearth.earth.terrain;

import dev.mearth.earth.EarthMod;
import dev.mearth.earth.config.EarthConfig;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TerrainTileService {
	private static final double MAX_LAT = 85.05112878;
	private static final double RAW_ALTITUDE_OFFSET_METERS = 12.0;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final Map<TileKey, BufferedImage> TILE_CACHE = new ConcurrentHashMap<>();

	private TerrainTileService() {
	}

	public static double sampleMeters(double latitude, double longitude) {
		try {
			int zoom = EarthConfig.terrainZoom();
			double clampedLat = Math.max(-MAX_LAT, Math.min(MAX_LAT, latitude));
			double latRad = Math.toRadians(clampedLat);
			double n = 1 << zoom;
			double worldX = (longitude + 180.0) / 360.0 * n;
			double worldY = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;

			int tileX = floorToInt(worldX);
			int tileY = floorToInt(worldY);
			double pixelX = (worldX - tileX) * 256.0;
			double pixelY = (worldY - tileY) * 256.0;
			int x0 = floorToInt(pixelX);
			int y0 = floorToInt(pixelY);
			int x1 = x0 + 1;
			int y1 = y0 + 1;
			double fracX = pixelX - x0;
			double fracY = pixelY - y0;

			double e00 = sampleTilePixel(zoom, tileX, tileY, x0, y0);
			double e10 = sampleTilePixel(zoom, tileX, tileY, x1, y0);
			double e01 = sampleTilePixel(zoom, tileX, tileY, x0, y1);
			double e11 = sampleTilePixel(zoom, tileX, tileY, x1, y1);

			double top = lerp(e00, e10, fracX);
			double bottom = lerp(e01, e11, fracX);
			return lerp(top, bottom, fracY) + RAW_ALTITUDE_OFFSET_METERS;
		} catch (Exception exception) {
			EarthMod.LOGGER.warn("Terrain fetch failed at {}, {}: {}", latitude, longitude, exception.toString());
			return 0.0;
		}
	}

	private static double sampleTilePixel(int zoom, int tileX, int tileY, int pixelX, int pixelY) throws IOException, InterruptedException {
		int wrappedTileX = tileX;
		int wrappedTileY = tileY;
		int wrappedPixelX = pixelX;
		int wrappedPixelY = pixelY;
		int tileCount = 1 << zoom;

		if (wrappedPixelX < 0) {
			wrappedPixelX += 256;
			wrappedTileX -= 1;
		} else if (wrappedPixelX > 255) {
			wrappedPixelX -= 256;
			wrappedTileX += 1;
		}

		if (wrappedPixelY < 0) {
			wrappedPixelY += 256;
			wrappedTileY -= 1;
		} else if (wrappedPixelY > 255) {
			wrappedPixelY -= 256;
			wrappedTileY += 1;
		}

		wrappedTileX = Math.floorMod(wrappedTileX, tileCount);
		wrappedTileY = clamp(wrappedTileY, 0, tileCount - 1);

		BufferedImage image = loadTile(new TileKey(zoom, wrappedTileX, wrappedTileY));
		int rgb = image.getRGB(wrappedPixelX, wrappedPixelY);
		int r = (rgb >> 16) & 255;
		int g = (rgb >> 8) & 255;
		int b = rgb & 255;
		return (r * 256.0 + g + b / 256.0) - 32768.0;
	}

	private static BufferedImage loadTile(TileKey key) throws IOException, InterruptedException {
		BufferedImage cached = TILE_CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		String url = "https://elevation-tiles-prod.s3.amazonaws.com/terrarium/%d/%d/%d.png".formatted(key.zoom(), key.x(), key.y());
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(10))
			.header("User-Agent", "earthmod/1.0 (Fabric Minecraft mod)")
			.GET()
			.build();
		HttpResponse<java.io.InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() >= 400) {
			throw new IOException("Terrain tile returned HTTP " + response.statusCode());
		}

		BufferedImage image = ImageIO.read(response.body());
		if (image == null) {
			throw new IOException("Failed to decode terrain tile image");
		}
		TILE_CACHE.put(key, image);
		return image;
	}

	private static int floorToInt(double value) {
		return (int)Math.floor(value);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double lerp(double start, double end, double delta) {
		return start + (end - start) * delta;
	}

	private record TileKey(int zoom, int x, int y) {
	}
}
