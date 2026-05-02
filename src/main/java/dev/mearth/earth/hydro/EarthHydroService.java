package dev.mearth.earth.hydro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mearth.earth.EarthMod;
import dev.mearth.earth.config.EarthConfig;
import dev.mearth.earth.terrain.TerrainTileService;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EarthHydroService {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final Map<HydroTileKey, HydroTile> CACHE = new ConcurrentHashMap<>();
	private static final Map<HydroTileKey, CompletableFuture<HydroTile>> IN_FLIGHT = new ConcurrentHashMap<>();
	private static final Map<HydroFeatureCacheKey, Double> LAKE_LEVEL_CACHE = new ConcurrentHashMap<>();
	private static final Map<HydroTileKey, LakeRaster> LAKE_RASTER_CACHE = new ConcurrentHashMap<>();
	private static final Map<HydroTileKey, RiverRaster> RIVER_RASTER_CACHE = new ConcurrentHashMap<>();
	private static final Path CACHE_DIR = FabricLoader.getInstance().getConfigDir().resolve("earthmod").resolve("hydro");
	private static final String CACHE_VERSION = "v3";
	private static final double TILE_SIZE_DEGREES = 0.25;
	private static final double TILE_FETCH_MARGIN_DEGREES = 0.02;
	private static final double LINEAR_WATER_PROXIMITY_METERS = 120.0;
	private static final double EARTH_RADIUS_METERS = 6_371_008.8;
	private static final int FEATURE_ELEVATION_SAMPLE_LIMIT = 32;
	private static final int LAKE_RASTER_SIZE = 1024;
	private static final int RIVER_RASTER_SIZE = 1024;
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "earthmod-hydro");
		thread.setDaemon(true);
		return thread;
	});

	private EarthHydroService() {
	}

	public static HydroSample sample(double latitude, double longitude) throws IOException, InterruptedException {
		HydroTileKey key = HydroTileKey.from(latitude, longitude);
		HydroTile cached = CACHE.get(key);
		if (cached != null) {
			return summarize(cached, latitude, longitude);
		}

		HydroTile diskCached = readCacheFile(key);
		if (diskCached != null) {
			CACHE.put(key, diskCached);
			return summarize(diskCached, latitude, longitude);
		}

		HydroTile fetched = fetchFromApi(key);
		CACHE.put(key, fetched);
		writeCacheFile(key, fetched);
		return summarize(fetched, latitude, longitude);
	}

	public static LakeSample sampleLakes(double latitude, double longitude) throws IOException, InterruptedException {
		HydroTileKey key = HydroTileKey.from(latitude, longitude);
		HydroTile cached = CACHE.get(key);
		if (cached != null) {
			return summarizeLakes(key, cached, latitude, longitude);
		}

		HydroTile diskCached = readCacheFile(key);
		if (diskCached != null) {
			CACHE.put(key, diskCached);
			return summarizeLakes(key, diskCached, latitude, longitude);
		}

		HydroTile fetched = fetchFromApi(key);
		CACHE.put(key, fetched);
		writeCacheFile(key, fetched);
		return summarizeLakes(key, fetched, latitude, longitude);
	}

	public static RiverSample sampleRivers(double latitude, double longitude) throws IOException, InterruptedException {
		HydroTileKey key = HydroTileKey.from(latitude, longitude);
		HydroTile cached = CACHE.get(key);
		if (cached != null) {
			return summarizeRivers(key, cached, latitude, longitude);
		}

		HydroTile diskCached = readCacheFile(key);
		if (diskCached != null) {
			CACHE.put(key, diskCached);
			return summarizeRivers(key, diskCached, latitude, longitude);
		}

		HydroTile fetched = fetchFromApi(key);
		CACHE.put(key, fetched);
		writeCacheFile(key, fetched);
		return summarizeRivers(key, fetched, latitude, longitude);
	}

	public static HydroSample getCachedOrFetchAsync(double latitude, double longitude) {
		HydroTileKey key = HydroTileKey.from(latitude, longitude);
		HydroTile cached = CACHE.get(key);
		if (cached != null) {
			return summarize(cached, latitude, longitude);
		}

		HydroTile diskCached = readCacheFile(key);
		if (diskCached != null) {
			CACHE.put(key, diskCached);
			return summarize(diskCached, latitude, longitude);
		}

		IN_FLIGHT.computeIfAbsent(key, ignored -> CompletableFuture.supplyAsync(() -> {
			try {
				HydroTile fetched = fetchFromApi(key);
				CACHE.put(key, fetched);
				writeCacheFile(key, fetched);
				return fetched;
			} catch (Exception exception) {
				throw new RuntimeException(exception);
			} finally {
				IN_FLIGHT.remove(key);
			}
		}, EXECUTOR).whenComplete((result, throwable) -> {
			if (throwable != null) {
				EarthMod.LOGGER.warn("Async hydro fetch failed for {}, {}: {}", latitude, longitude, throwable.toString());
			}
		}));

		return null;
	}

	private static HydroTile fetchFromApi(HydroTileKey key) throws IOException, InterruptedException {
		double south = key.southLatitude() - TILE_FETCH_MARGIN_DEGREES;
		double west = key.westLongitude() - TILE_FETCH_MARGIN_DEGREES;
		double north = key.northLatitude() + TILE_FETCH_MARGIN_DEGREES;
		double east = key.eastLongitude() + TILE_FETCH_MARGIN_DEGREES;
		String query = """
			[out:json][timeout:20];
			(
			  relation["natural"="water"](%1$.6f,%2$.6f,%3$.6f,%4$.6f);
			  relation["waterway"~"river|stream|canal|ditch|drain|riverbank"](%1$.6f,%2$.6f,%3$.6f,%4$.6f);
			  relation["water"~"lake|reservoir|pond|river|lagoon|canal|basin"](%1$.6f,%2$.6f,%3$.6f,%4$.6f);
			  way["natural"="water"](%1$.6f,%2$.6f,%3$.6f,%4$.6f);
			  way["waterway"~"river|stream|canal|ditch|drain|riverbank"](%1$.6f,%2$.6f,%3$.6f,%4$.6f);
			  way["water"~"lake|reservoir|pond|river|lagoon|canal|basin"](%1$.6f,%2$.6f,%3$.6f,%4$.6f);
			);
			out geom;
			""".formatted(south, west, north, east);

		HttpRequest request = HttpRequest.newBuilder(URI.create(EarthConfig.hydroOverpassEndpoint()))
			.timeout(Duration.ofSeconds(20))
			.header("Content-Type", "text/plain; charset=utf-8")
			.header("User-Agent", "earthmod/1.0 (Fabric Minecraft mod)")
			.POST(HttpRequest.BodyPublishers.ofString(query, StandardCharsets.UTF_8))
			.build();
		HttpResponse<java.io.InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() >= 400) {
			throw new IOException("Hydro request returned HTTP " + response.statusCode());
		}

		try (Reader reader = new InputStreamReader(response.body(), StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			HydroTile tile = parseHydroTile(root, key);
			EarthMod.LOGGER.info(
				"Fetched hydro data for tile {} [{}, {}] -> [{}, {}] with {} features",
				key.cacheStem(),
				key.southLatitude(),
				key.westLongitude(),
				key.northLatitude(),
				key.eastLongitude(),
				tile.features().size()
			);
			return tile;
		}
	}

	private static HydroTile parseHydroTile(JsonObject root, HydroTileKey key) throws IOException {
		JsonElement elementsElement = root.get("elements");
		if (elementsElement == null || !elementsElement.isJsonArray()) {
			throw new IOException("Missing hydro elements array");
		}

		List<HydroFeature> features = new ArrayList<>();
		for (JsonElement element : elementsElement.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}
			features.addAll(parseFeatures(element.getAsJsonObject()));
		}
		return new HydroTile(key, features);
	}

	private static List<HydroFeature> parseFeatures(JsonObject object) {
		JsonObject tags = object.has("tags") && object.get("tags").isJsonObject() ? object.getAsJsonObject("tags") : new JsonObject();
		WaterBodyKind kind = classify(tags);
		if (kind == WaterBodyKind.UNKNOWN) {
			return List.of();
		}

		String featureName = stringTag(tags, "name");
		String sourceTag = firstNonBlank(
			stringTag(tags, "water"),
			stringTag(tags, "waterway"),
			stringTag(tags, "natural")
		);
		String elementType = stringTag(object, "type");
		if ("relation".equals(elementType)) {
			return parseRelationFeatures(object, kind, featureName, sourceTag);
		}

		JsonArray geometry = object.has("geometry") && object.get("geometry").isJsonArray() ? object.getAsJsonArray("geometry") : null;
		if (geometry == null || geometry.isEmpty()) {
			return List.of();
		}

		List<GeoPoint> points = parseGeometryPoints(geometry);
		if (points.size() < 2) {
			return List.of();
		}

		boolean closed = isClosed(points);
		boolean area = closed || kind.isStandingWater() || kind == WaterBodyKind.RIVERBANK;
		return List.of(new HydroFeature(
			object.has("id") ? object.get("id").getAsLong() : -1L,
			kind,
			area,
			featureName,
			sourceTag,
			points
		));
	}

	private static List<HydroFeature> parseRelationFeatures(
		JsonObject object,
		WaterBodyKind kind,
		String featureName,
		String sourceTag
	) {
		JsonArray members = object.has("members") && object.get("members").isJsonArray() ? object.getAsJsonArray("members") : null;
		if (members == null || members.isEmpty()) {
			return List.of();
		}

		List<List<GeoPoint>> outerMembers = new ArrayList<>();
		long relationId = object.has("id") ? object.get("id").getAsLong() : -1L;
		for (JsonElement memberElement : members) {
			if (!memberElement.isJsonObject()) {
				continue;
			}
			JsonObject member = memberElement.getAsJsonObject();
			String memberType = stringTag(member, "type");
			String role = stringTag(member, "role");
			if (!"way".equals(memberType) || "inner".equals(role)) {
				continue;
			}

			JsonArray geometry = member.has("geometry") && member.get("geometry").isJsonArray() ? member.getAsJsonArray("geometry") : null;
			if (geometry == null || geometry.isEmpty()) {
				continue;
			}

			List<GeoPoint> points = parseGeometryPoints(geometry);
			if (points.size() < 2) {
				continue;
			}
			outerMembers.add(points);
		}
		List<HydroFeature> features = new ArrayList<>();
		for (List<GeoPoint> ring : stitchOuterRings(outerMembers)) {
			if (ring.size() < 3) {
				continue;
			}
			features.add(new HydroFeature(
				relationId,
				kind,
				true,
				featureName,
				sourceTag,
				ring
			));
		}
		return features;
	}

	private static List<GeoPoint> parseGeometryPoints(JsonArray geometry) {
		List<GeoPoint> points = new ArrayList<>(geometry.size());
		for (JsonElement pointElement : geometry) {
			if (!pointElement.isJsonObject()) {
				continue;
			}
			JsonObject point = pointElement.getAsJsonObject();
			JsonElement latElement = point.get("lat");
			JsonElement lonElement = point.get("lon");
			if (latElement == null || lonElement == null) {
				continue;
			}
			points.add(new GeoPoint(latElement.getAsDouble(), lonElement.getAsDouble()));
		}
		return points;
	}

	private static List<List<GeoPoint>> stitchOuterRings(List<List<GeoPoint>> fragments) {
		List<List<GeoPoint>> remaining = new ArrayList<>();
		for (List<GeoPoint> fragment : fragments) {
			remaining.add(new ArrayList<>(fragment));
		}

		boolean merged;
		do {
			merged = false;
			for (int i = 0; i < remaining.size() && !merged; i++) {
				for (int j = i + 1; j < remaining.size() && !merged; j++) {
					List<GeoPoint> joined = tryJoin(remaining.get(i), remaining.get(j));
					if (joined != null) {
						remaining.set(i, joined);
						remaining.remove(j);
						merged = true;
					}
				}
			}
		} while (merged);

		List<List<GeoPoint>> rings = new ArrayList<>();
		for (List<GeoPoint> fragment : remaining) {
			if (!isClosed(fragment)) {
				fragment = new ArrayList<>(fragment);
				fragment.add(fragment.getFirst());
			}
			rings.add(fragment);
		}
		return rings;
	}

	private static List<GeoPoint> tryJoin(List<GeoPoint> first, List<GeoPoint> second) {
		GeoPoint firstStart = first.getFirst();
		GeoPoint firstEnd = first.getLast();
		GeoPoint secondStart = second.getFirst();
		GeoPoint secondEnd = second.getLast();

		if (samePoint(firstEnd, secondStart)) {
			return concatenate(first, second, false);
		}
		if (samePoint(firstEnd, secondEnd)) {
			return concatenate(first, second, true);
		}
		if (samePoint(firstStart, secondEnd)) {
			return concatenate(second, first, false);
		}
		if (samePoint(firstStart, secondStart)) {
			return concatenate(reverse(second), first, false);
		}
		return null;
	}

	private static List<GeoPoint> concatenate(List<GeoPoint> first, List<GeoPoint> second, boolean reverseSecond) {
		List<GeoPoint> result = new ArrayList<>(first);
		List<GeoPoint> tail = reverseSecond ? reverse(second) : second;
		for (int index = 1; index < tail.size(); index++) {
			result.add(tail.get(index));
		}
		return result;
	}

	private static List<GeoPoint> reverse(List<GeoPoint> points) {
		List<GeoPoint> reversed = new ArrayList<>(points.size());
		for (int index = points.size() - 1; index >= 0; index--) {
			reversed.add(points.get(index));
		}
		return reversed;
	}

	private static boolean samePoint(GeoPoint first, GeoPoint second) {
		return Math.abs(first.latitude() - second.latitude()) < 1.0e-7
			&& Math.abs(first.longitude() - second.longitude()) < 1.0e-7;
	}

	private static WaterBodyKind classify(JsonObject tags) {
		String natural = stringTag(tags, "natural");
		String water = stringTag(tags, "water");
		String waterway = stringTag(tags, "waterway");

		if ("river".equals(waterway)) {
			return WaterBodyKind.RIVER;
		}
		if ("stream".equals(waterway)) {
			return WaterBodyKind.STREAM;
		}
		if ("canal".equals(waterway)) {
			return WaterBodyKind.CANAL;
		}
		if ("ditch".equals(waterway) || "drain".equals(waterway)) {
			return WaterBodyKind.DRAINAGE;
		}
		if ("riverbank".equals(waterway)) {
			return WaterBodyKind.RIVERBANK;
		}
		if ("water".equals(natural)) {
			if ("lake".equals(water)) {
				return WaterBodyKind.LAKE;
			}
			if ("reservoir".equals(water)) {
				return WaterBodyKind.RESERVOIR;
			}
			if ("pond".equals(water)) {
				return WaterBodyKind.POND;
			}
			if ("river".equals(water)) {
				return WaterBodyKind.RIVERBANK;
			}
			if ("canal".equals(water)) {
				return WaterBodyKind.CANAL;
			}
			if ("lagoon".equals(water) || "basin".equals(water)) {
				return WaterBodyKind.OTHER_WATER;
			}
			return WaterBodyKind.LAKE;
		}
		return WaterBodyKind.UNKNOWN;
	}

	private static HydroSample summarize(HydroTile tile, double latitude, double longitude) {
		boolean insideWater = false;
		boolean insideLake = false;
		boolean insideRiver = false;
		double nearestWaterMeters = Double.POSITIVE_INFINITY;
		double nearestLakeMeters = Double.POSITIVE_INFINITY;
		double nearestRiverMeters = Double.POSITIVE_INFINITY;
		HydroFeature nearestFeature = null;
		double nearestFeatureMeters = Double.POSITIVE_INFINITY;
		FeatureHit bestLakeHit = null;
		FeatureHit bestRiverHit = null;

		for (HydroFeature feature : tile.features()) {
			FeatureHit hit = sampleFeatureHit(latitude, longitude, feature);
			double distanceMeters = hit.distanceMeters();
			boolean contains = hit.contains();

			if (contains) {
				insideWater = true;
				if (feature.kind().isRiverLike()) {
					insideRiver = true;
				}
				if (feature.kind().isStandingWater()) {
					insideLake = true;
				}
			}

			if (distanceMeters < nearestWaterMeters) {
				nearestWaterMeters = distanceMeters;
			}
			if (feature.kind().isStandingWater() && distanceMeters < nearestLakeMeters) {
				nearestLakeMeters = distanceMeters;
				bestLakeHit = hit;
			}
			if (feature.kind().isRiverLike() && distanceMeters < nearestRiverMeters) {
				nearestRiverMeters = distanceMeters;
				bestRiverHit = hit;
			}
			if (distanceMeters < nearestFeatureMeters) {
				nearestFeatureMeters = distanceMeters;
				nearestFeature = feature;
			}
		}

		boolean riverNearby = nearestRiverMeters <= LINEAR_WATER_PROXIMITY_METERS;
		WaterBodyKind nearestKind = nearestFeature != null ? nearestFeature.kind() : WaterBodyKind.UNKNOWN;
		String nearestName = nearestFeature != null ? nearestFeature.displayName() : null;
		double lakeWaterLevelMeters = bestLakeHit != null ? cachedStandingWaterLevelMeters(bestLakeHit.feature()) : Double.NaN;
		double riverWaterLevelMeters = bestRiverHit != null ? sampleRiverWaterLevelMeters(bestRiverHit) : Double.NaN;
		double riverHalfWidthMeters = bestRiverHit != null ? riverHalfWidthMeters(bestRiverHit.feature().kind(), bestRiverHit.downstreamProgress()) : -1.0;
		return new HydroSample(
			latitude,
			longitude,
			insideWater,
			insideLake,
			insideRiver || riverNearby,
			finiteOrNegativeOne(nearestWaterMeters),
			finiteOrNegativeOne(nearestLakeMeters),
			finiteOrNegativeOne(nearestRiverMeters),
			nearestKind,
			nearestName,
			tile.features().size(),
			finiteOrNegativeOne(lakeWaterLevelMeters),
			finiteOrNegativeOne(riverWaterLevelMeters),
			riverHalfWidthMeters
		);
	}

	private static LakeSample summarizeLakes(HydroTileKey key, HydroTile tile, double latitude, double longitude) {
		LakeRaster raster = LAKE_RASTER_CACHE.computeIfAbsent(key, ignored -> buildLakeRaster(key, tile));
		int cellX = clamp((int)Math.floor((longitude - key.westLongitude()) / TILE_SIZE_DEGREES * raster.width()), 0, raster.width() - 1);
		int cellY = clamp((int)Math.floor((latitude - key.southLatitude()) / TILE_SIZE_DEGREES * raster.height()), 0, raster.height() - 1);
		int featureIndex = raster.featureIndex(cellX, cellY);
		if (featureIndex < 0) {
			return new LakeSample(latitude, longitude, false, -1.0, -1.0);
		}

		LakeRasterFeature feature = raster.features().get(featureIndex);
		return new LakeSample(
			latitude,
			longitude,
			true,
			0.0,
			feature.waterLevelMeters()
		);
	}

	private static RiverSample summarizeRivers(HydroTileKey key, HydroTile tile, double latitude, double longitude) {
		RiverRaster raster = RIVER_RASTER_CACHE.computeIfAbsent(key, ignored -> buildRiverRaster(key, tile));
		int cellX = clamp((int)Math.floor((longitude - key.westLongitude()) / TILE_SIZE_DEGREES * raster.width()), 0, raster.width() - 1);
		int cellY = clamp((int)Math.floor((latitude - key.southLatitude()) / TILE_SIZE_DEGREES * raster.height()), 0, raster.height() - 1);
		double waterLevelMeters = raster.waterLevelMeters(cellX, cellY);
		if (!Double.isFinite(waterLevelMeters)) {
			return new RiverSample(latitude, longitude, false, -1.0);
		}
		return new RiverSample(latitude, longitude, true, waterLevelMeters);
	}

	private static HydroTile readCacheFile(HydroTileKey key) {
		Path path = cachePath(key);
		if (!Files.exists(path)) {
			return null;
		}

		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			return GSON.fromJson(reader, HydroTile.class);
		} catch (Exception exception) {
			EarthMod.LOGGER.warn("Failed to read hydro cache {}: {}", path, exception.toString());
			return null;
		}
	}

	private static void writeCacheFile(HydroTileKey key, HydroTile tile) {
		Path path = cachePath(key);
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(tile, writer);
			}
		} catch (IOException exception) {
			EarthMod.LOGGER.warn("Failed to write hydro cache {}: {}", path, exception.toString());
		}
	}

	private static Path cachePath(HydroTileKey key) {
		return CACHE_DIR.resolve(CACHE_VERSION).resolve(key.cacheStem() + ".json");
	}

	private static boolean pointInPolygon(double latitude, double longitude, List<GeoPoint> polygon) {
		boolean inside = false;
		for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
			GeoPoint a = polygon.get(i);
			GeoPoint b = polygon.get(j);
			boolean intersects = ((a.latitude() > latitude) != (b.latitude() > latitude))
				&& (longitude < (b.longitude() - a.longitude()) * (latitude - a.latitude()) / (b.latitude() - a.latitude()) + a.longitude());
			if (intersects) {
				inside = !inside;
			}
		}
		return inside;
	}

	private static FeatureHit sampleFeatureHit(double latitude, double longitude, HydroFeature feature) {
		boolean contains = feature.area() && pointInPolygon(latitude, longitude, feature.points());
		SegmentHit segmentHit = nearestSegmentHit(latitude, longitude, feature.points(), feature.area());
		double distanceMeters = contains ? 0.0 : segmentHit.distanceMeters();
		double downstreamProgress = segmentHit.downstreamProgress();
		if (feature.kind().isRiverLike() && feature.points().size() >= 2 && !feature.area()) {
			double startElevation = TerrainTileService.sampleMeters(feature.points().getFirst().latitude(), feature.points().getFirst().longitude());
			double endElevation = TerrainTileService.sampleMeters(feature.points().getLast().latitude(), feature.points().getLast().longitude());
			if (endElevation > startElevation) {
				downstreamProgress = 1.0 - downstreamProgress;
			}
		}
		return new FeatureHit(feature, distanceMeters, contains, downstreamProgress, segmentHit.closestPoint());
	}

	private static SegmentHit nearestSegmentHit(double latitude, double longitude, List<GeoPoint> points, boolean closed) {
		double minDistance = Double.POSITIVE_INFINITY;
		double totalLength = totalLengthMeters(points, closed);
		double traversedLength = 0.0;
		double bestProgress = 0.0;
		GeoPoint bestPoint = points.getFirst();
		int segmentCount = closed ? points.size() : points.size() - 1;
		for (int index = 0; index < segmentCount; index++) {
			GeoPoint start = points.get(index);
			GeoPoint end = points.get((index + 1) % points.size());
			double segmentLength = haversineMeters(start, end);
			SegmentProjection projection = projectPointOntoSegment(latitude, longitude, start, end);
			if (projection.distanceMeters() < minDistance) {
				minDistance = projection.distanceMeters();
				bestPoint = projection.closestPoint();
				double lengthAtProjection = traversedLength + segmentLength * projection.segmentFraction();
				bestProgress = totalLength <= 1.0e-6 ? 0.0 : clamp(lengthAtProjection / totalLength, 0.0, 1.0);
			}
			traversedLength += segmentLength;
		}
		return new SegmentHit(minDistance, bestProgress, bestPoint);
	}

	private static double totalLengthMeters(List<GeoPoint> points, boolean closed) {
		double total = 0.0;
		int segmentCount = closed ? points.size() : points.size() - 1;
		for (int index = 0; index < segmentCount; index++) {
			total += haversineMeters(points.get(index), points.get((index + 1) % points.size()));
		}
		return total;
	}

	private static SegmentProjection projectPointOntoSegment(double latitude, double longitude, GeoPoint start, GeoPoint end) {
		double referenceLatitude = Math.toRadians((start.latitude() + end.latitude() + latitude) / 3.0);
		double x = longitudeToMeters(longitude, referenceLatitude);
		double y = latitudeToMeters(latitude);
		double x1 = longitudeToMeters(start.longitude(), referenceLatitude);
		double y1 = latitudeToMeters(start.latitude());
		double x2 = longitudeToMeters(end.longitude(), referenceLatitude);
		double y2 = latitudeToMeters(end.latitude());
		double dx = x2 - x1;
		double dy = y2 - y1;
		double lengthSquared = dx * dx + dy * dy;
		if (lengthSquared <= 1.0e-9) {
			return new SegmentProjection(
				Math.hypot(x - x1, y - y1),
				0.0,
				start
			);
		}
		double projection = ((x - x1) * dx + (y - y1) * dy) / lengthSquared;
		double clampedProjection = clamp(projection, 0.0, 1.0);
		double closestX = x1 + clampedProjection * dx;
		double closestY = y1 + clampedProjection * dy;
		double closestLatitude = metersToLatitude(closestY);
		double closestLongitude = metersToLongitude(closestX, referenceLatitude);
		return new SegmentProjection(
			Math.hypot(x - closestX, y - closestY),
			clampedProjection,
			new GeoPoint(closestLatitude, closestLongitude)
		);
	}

	private static double sampleStandingWaterLevelMeters(HydroFeature feature) {
		List<Double> elevations = new ArrayList<>();
		List<GeoPoint> points = feature.points();
		int step = Math.max(1, points.size() / FEATURE_ELEVATION_SAMPLE_LIMIT);
		for (int index = 0; index < points.size(); index += step) {
			GeoPoint point = points.get(index);
			elevations.add(TerrainTileService.sampleMeters(point.latitude(), point.longitude()));
		}
		if (elevations.isEmpty()) {
			return Double.NaN;
		}
		elevations.sort(Comparator.naturalOrder());
		int percentileIndex = (int)Math.floor((elevations.size() - 1) * 0.20);
		return elevations.get(percentileIndex);
	}

	private static double cachedStandingWaterLevelMeters(HydroFeature feature) {
		return LAKE_LEVEL_CACHE.computeIfAbsent(feature.cacheKey(), ignored -> sampleStandingWaterLevelMeters(feature));
	}

	private static LakeRaster buildLakeRaster(HydroTileKey key, HydroTile tile) {
		List<LakeRasterFeature> rasterFeatures = new ArrayList<>();
		for (HydroFeature feature : tile.features()) {
			if (!feature.kind().isStandingWater() || !feature.area() || feature.points().size() < 3) {
				continue;
			}
			rasterFeatures.add(new LakeRasterFeature(feature, cachedStandingWaterLevelMeters(feature)));
		}

		int[] featureIndices = new int[LAKE_RASTER_SIZE * LAKE_RASTER_SIZE];
		for (int index = 0; index < featureIndices.length; index++) {
			featureIndices[index] = -1;
		}

		for (int featureIndex = 0; featureIndex < rasterFeatures.size(); featureIndex++) {
			HydroFeature feature = rasterFeatures.get(featureIndex).feature();
			double minLatitude = Double.POSITIVE_INFINITY;
			double maxLatitude = Double.NEGATIVE_INFINITY;
			double minLongitude = Double.POSITIVE_INFINITY;
			double maxLongitude = Double.NEGATIVE_INFINITY;
			for (GeoPoint point : feature.points()) {
				minLatitude = Math.min(minLatitude, point.latitude());
				maxLatitude = Math.max(maxLatitude, point.latitude());
				minLongitude = Math.min(minLongitude, point.longitude());
				maxLongitude = Math.max(maxLongitude, point.longitude());
			}

			int minX = clamp((int)Math.floor((minLongitude - key.westLongitude()) / TILE_SIZE_DEGREES * LAKE_RASTER_SIZE), 0, LAKE_RASTER_SIZE - 1);
			int maxX = clamp((int)Math.floor((maxLongitude - key.westLongitude()) / TILE_SIZE_DEGREES * LAKE_RASTER_SIZE), 0, LAKE_RASTER_SIZE - 1);
			int minY = clamp((int)Math.floor((minLatitude - key.southLatitude()) / TILE_SIZE_DEGREES * LAKE_RASTER_SIZE), 0, LAKE_RASTER_SIZE - 1);
			int maxY = clamp((int)Math.floor((maxLatitude - key.southLatitude()) / TILE_SIZE_DEGREES * LAKE_RASTER_SIZE), 0, LAKE_RASTER_SIZE - 1);

			for (int y = minY; y <= maxY; y++) {
				double sampleLatitude = key.southLatitude() + (y + 0.5) / LAKE_RASTER_SIZE * TILE_SIZE_DEGREES;
				for (int x = minX; x <= maxX; x++) {
					double sampleLongitude = key.westLongitude() + (x + 0.5) / LAKE_RASTER_SIZE * TILE_SIZE_DEGREES;
					if (pointInPolygon(sampleLatitude, sampleLongitude, feature.points())) {
						int cellIndex = y * LAKE_RASTER_SIZE + x;
						if (featureIndices[cellIndex] < 0
							|| rasterFeatures.get(featureIndex).waterLevelMeters() > rasterFeatures.get(featureIndices[cellIndex]).waterLevelMeters()) {
							featureIndices[cellIndex] = featureIndex;
						}
					}
				}
			}
		}

		return new LakeRaster(LAKE_RASTER_SIZE, LAKE_RASTER_SIZE, featureIndices, List.copyOf(rasterFeatures));
	}

	private static RiverRaster buildRiverRaster(HydroTileKey key, HydroTile tile) {
		double[] waterLevels = new double[RIVER_RASTER_SIZE * RIVER_RASTER_SIZE];
		for (int index = 0; index < waterLevels.length; index++) {
			waterLevels[index] = Double.NaN;
		}

		for (HydroFeature feature : tile.features()) {
			if (!feature.kind().isRiverLike() || feature.points().size() < 2 || feature.area()) {
				continue;
			}
			rasterizeRiverFeature(key, waterLevels, feature);
		}

		smoothRiverWaterLevels(waterLevels, RIVER_RASTER_SIZE, RIVER_RASTER_SIZE, 2);
		fillRiverGaps(waterLevels, RIVER_RASTER_SIZE, RIVER_RASTER_SIZE, 2);
		pruneIsolatedRiverCells(waterLevels, RIVER_RASTER_SIZE, RIVER_RASTER_SIZE, 2);
		return new RiverRaster(RIVER_RASTER_SIZE, RIVER_RASTER_SIZE, waterLevels);
	}

	private static void rasterizeRiverFeature(HydroTileKey key, double[] waterLevels, HydroFeature feature) {
		List<GeoPoint> points = feature.points();
		double totalLength = totalLengthMeters(points, false);
		if (totalLength <= 1.0e-6) {
			return;
		}

		double startElevation = TerrainTileService.sampleMeters(points.getFirst().latitude(), points.getFirst().longitude());
		double endElevation = TerrainTileService.sampleMeters(points.getLast().latitude(), points.getLast().longitude());
		boolean reverseProgress = endElevation > startElevation;
		double traversedLength = 0.0;
		double sampleStepMeters = riverRasterSampleStepMeters(key);

		for (int index = 0; index < points.size() - 1; index++) {
			GeoPoint start = points.get(index);
			GeoPoint end = points.get(index + 1);
			double segmentLength = haversineMeters(start, end);
			int steps = Math.max(1, (int)Math.ceil(segmentLength / sampleStepMeters));
			for (int step = 0; step <= steps; step++) {
				double fraction = step / (double)steps;
				double sampleLatitude = lerp(start.latitude(), end.latitude(), fraction);
				double sampleLongitude = lerp(start.longitude(), end.longitude(), fraction);
				double downstreamProgress = (traversedLength + segmentLength * fraction) / totalLength;
				if (reverseProgress) {
					downstreamProgress = 1.0 - downstreamProgress;
				}
				double halfWidthMeters = riverHalfWidthMeters(feature.kind(), downstreamProgress);
				double waterLevelMeters = TerrainTileService.sampleMeters(sampleLatitude, sampleLongitude);
				burnRiverSample(key, waterLevels, sampleLatitude, sampleLongitude, halfWidthMeters, waterLevelMeters);
			}
			traversedLength += segmentLength;
		}
	}

	private static double riverRasterSampleStepMeters(HydroTileKey key) {
		double middleLatitude = (key.southLatitude() + key.northLatitude()) * 0.5;
		double latitudeStepDegrees = TILE_SIZE_DEGREES / RIVER_RASTER_SIZE;
		double longitudeStepDegrees = TILE_SIZE_DEGREES / RIVER_RASTER_SIZE;
		double latMetersPerCell = haversineMeters(
			new GeoPoint(middleLatitude, key.westLongitude()),
			new GeoPoint(middleLatitude + latitudeStepDegrees, key.westLongitude())
		);
		double lonMetersPerCell = haversineMeters(
			new GeoPoint(middleLatitude, key.westLongitude()),
			new GeoPoint(middleLatitude, key.westLongitude() + longitudeStepDegrees)
		);
		double minCellMeters = Math.max(1.0, Math.min(latMetersPerCell, lonMetersPerCell));
		return Math.max(6.0, minCellMeters * 0.75);
	}

	private static void burnRiverSample(
		HydroTileKey key,
		double[] waterLevels,
		double latitude,
		double longitude,
		double halfWidthMeters,
		double waterLevelMeters
	) {
		double centerX = (longitude - key.westLongitude()) / TILE_SIZE_DEGREES * RIVER_RASTER_SIZE;
		double centerY = (latitude - key.southLatitude()) / TILE_SIZE_DEGREES * RIVER_RASTER_SIZE;
		double latMetersPerCell = haversineMeters(
			new GeoPoint(latitude, longitude),
			new GeoPoint(latitude + TILE_SIZE_DEGREES / RIVER_RASTER_SIZE, longitude)
		);
		double lonMetersPerCell = haversineMeters(
			new GeoPoint(latitude, longitude),
			new GeoPoint(latitude, longitude + TILE_SIZE_DEGREES / RIVER_RASTER_SIZE)
		);
		int radiusX = Math.max(1, (int)Math.ceil(halfWidthMeters / Math.max(lonMetersPerCell, 1.0)));
		int radiusY = Math.max(1, (int)Math.ceil(halfWidthMeters / Math.max(latMetersPerCell, 1.0)));
		int minX = clamp((int)Math.floor(centerX) - radiusX, 0, RIVER_RASTER_SIZE - 1);
		int maxX = clamp((int)Math.floor(centerX) + radiusX, 0, RIVER_RASTER_SIZE - 1);
		int minY = clamp((int)Math.floor(centerY) - radiusY, 0, RIVER_RASTER_SIZE - 1);
		int maxY = clamp((int)Math.floor(centerY) + radiusY, 0, RIVER_RASTER_SIZE - 1);

		for (int y = minY; y <= maxY; y++) {
			double normalizedY = ((y + 0.5) - centerY) / Math.max(radiusY, 1);
			for (int x = minX; x <= maxX; x++) {
				double normalizedX = ((x + 0.5) - centerX) / Math.max(radiusX, 1);
				if (normalizedX * normalizedX + normalizedY * normalizedY > 1.0) {
					continue;
				}
				int cellIndex = y * RIVER_RASTER_SIZE + x;
				double existing = waterLevels[cellIndex];
				if (!Double.isFinite(existing) || waterLevelMeters < existing) {
					waterLevels[cellIndex] = waterLevelMeters;
				}
			}
		}
	}

	private static void smoothRiverWaterLevels(double[] waterLevels, int width, int height, int passes) {
		double[] scratch = new double[waterLevels.length];
		for (int pass = 0; pass < passes; pass++) {
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int index = y * width + x;
					double value = waterLevels[index];
					if (!Double.isFinite(value)) {
						scratch[index] = Double.NaN;
						continue;
					}

					double total = 0.0;
					int count = 0;
					for (int dy = -1; dy <= 1; dy++) {
						int ny = y + dy;
						if (ny < 0 || ny >= height) {
							continue;
						}
						for (int dx = -1; dx <= 1; dx++) {
							int nx = x + dx;
							if (nx < 0 || nx >= width) {
								continue;
							}
							double neighbor = waterLevels[ny * width + nx];
							if (!Double.isFinite(neighbor)) {
								continue;
							}
							total += neighbor;
							count++;
						}
					}
					scratch[index] = count == 0 ? value : total / count;
				}
			}
			System.arraycopy(scratch, 0, waterLevels, 0, waterLevels.length);
		}
	}

	private static void fillRiverGaps(double[] waterLevels, int width, int height, int passes) {
		double[] scratch = waterLevels.clone();
		for (int pass = 0; pass < passes; pass++) {
			System.arraycopy(waterLevels, 0, scratch, 0, waterLevels.length);
			for (int y = 1; y < height - 1; y++) {
				for (int x = 1; x < width - 1; x++) {
					int index = y * width + x;
					if (Double.isFinite(waterLevels[index])) {
						continue;
					}

					double left = waterLevels[index - 1];
					double right = waterLevels[index + 1];
					double up = waterLevels[index - width];
					double down = waterLevels[index + width];
					boolean horizontalBridge = Double.isFinite(left) && Double.isFinite(right);
					boolean verticalBridge = Double.isFinite(up) && Double.isFinite(down);
					if (!horizontalBridge && !verticalBridge) {
						continue;
					}

					double total = 0.0;
					int count = 0;
					if (horizontalBridge) {
						total += left + right;
						count += 2;
					}
					if (verticalBridge) {
						total += up + down;
						count += 2;
					}
					scratch[index] = total / count;
				}
			}
			System.arraycopy(scratch, 0, waterLevels, 0, waterLevels.length);
		}
	}

	private static void pruneIsolatedRiverCells(double[] waterLevels, int width, int height, int passes) {
		double[] scratch = waterLevels.clone();
		for (int pass = 0; pass < passes; pass++) {
			System.arraycopy(waterLevels, 0, scratch, 0, waterLevels.length);
			for (int y = 1; y < height - 1; y++) {
				for (int x = 1; x < width - 1; x++) {
					int index = y * width + x;
					if (!Double.isFinite(waterLevels[index])) {
						continue;
					}

					int cardinalNeighbors = 0;
					if (Double.isFinite(waterLevels[index - 1])) {
						cardinalNeighbors++;
					}
					if (Double.isFinite(waterLevels[index + 1])) {
						cardinalNeighbors++;
					}
					if (Double.isFinite(waterLevels[index - width])) {
						cardinalNeighbors++;
					}
					if (Double.isFinite(waterLevels[index + width])) {
						cardinalNeighbors++;
					}
					if (cardinalNeighbors >= 2) {
						continue;
					}

					int neighborhoodCount = 0;
					for (int dy = -1; dy <= 1; dy++) {
						for (int dx = -1; dx <= 1; dx++) {
							if (dx == 0 && dy == 0) {
								continue;
							}
							if (Double.isFinite(waterLevels[(y + dy) * width + (x + dx)])) {
								neighborhoodCount++;
							}
						}
					}
					if (neighborhoodCount <= 2) {
						scratch[index] = Double.NaN;
					}
				}
			}
			System.arraycopy(scratch, 0, waterLevels, 0, waterLevels.length);
		}
	}

	private static double sampleRiverWaterLevelMeters(FeatureHit hit) {
		GeoPoint closestPoint = hit.closestPoint();
		return TerrainTileService.sampleMeters(closestPoint.latitude(), closestPoint.longitude());
	}

	private static double riverHalfWidthMeters(WaterBodyKind kind, double downstreamProgress) {
		double progress = clamp(downstreamProgress, 0.0, 1.0);
		double shapedProgress = Math.pow(progress, 1.35);
		return switch (kind) {
			case STREAM -> lerp(4.0, 10.0, shapedProgress);
			case DRAINAGE -> lerp(3.0, 8.0, shapedProgress);
			case CANAL -> lerp(6.0, 14.0, shapedProgress);
			case RIVER, RIVERBANK -> lerp(5.0, 42.0, shapedProgress);
			default -> lerp(4.0, 12.0, shapedProgress);
		};
	}

	private static boolean isClosed(List<GeoPoint> points) {
		GeoPoint first = points.getFirst();
		GeoPoint last = points.getLast();
		return Math.abs(first.latitude() - last.latitude()) < 1.0e-7
			&& Math.abs(first.longitude() - last.longitude()) < 1.0e-7;
	}

	private static double latitudeToMeters(double latitude) {
		return Math.toRadians(latitude) * EARTH_RADIUS_METERS;
	}

	private static double longitudeToMeters(double longitude, double referenceLatitudeRadians) {
		return Math.toRadians(longitude) * EARTH_RADIUS_METERS * Math.cos(referenceLatitudeRadians);
	}

	private static double metersToLatitude(double meters) {
		return Math.toDegrees(meters / EARTH_RADIUS_METERS);
	}

	private static double metersToLongitude(double meters, double referenceLatitudeRadians) {
		double cos = Math.cos(referenceLatitudeRadians);
		if (Math.abs(cos) < 1.0e-9) {
			return 0.0;
		}
		return Math.toDegrees(meters / (EARTH_RADIUS_METERS * cos));
	}

	private static double haversineMeters(GeoPoint start, GeoPoint end) {
		double lat1 = Math.toRadians(start.latitude());
		double lat2 = Math.toRadians(end.latitude());
		double deltaLat = lat2 - lat1;
		double deltaLon = Math.toRadians(end.longitude() - start.longitude());
		double sinLat = Math.sin(deltaLat / 2.0);
		double sinLon = Math.sin(deltaLon / 2.0);
		double a = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
		return EARTH_RADIUS_METERS * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
	}

	private static double lerp(double start, double end, double delta) {
		return start + (end - start) * clamp(delta, 0.0, 1.0);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double finiteOrNegativeOne(double value) {
		return Double.isFinite(value) ? value : -1.0;
	}

	private static String stringTag(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	public record HydroSample(
		double latitude,
		double longitude,
		boolean water,
		boolean lake,
		boolean river,
		double nearestWaterDistanceMeters,
		double nearestLakeDistanceMeters,
		double nearestRiverDistanceMeters,
		WaterBodyKind nearestFeatureKind,
		String nearestFeatureName,
		int featureCount,
		double lakeWaterLevelMeters,
		double riverWaterLevelMeters,
		double riverHalfWidthMeters
	) {
	}

	public record LakeSample(
		double latitude,
		double longitude,
		boolean lake,
		double nearestLakeDistanceMeters,
		double lakeWaterLevelMeters
	) {
	}

	public record RiverSample(
		double latitude,
		double longitude,
		boolean river,
		double riverWaterLevelMeters
	) {
	}

	public enum WaterBodyKind {
		LAKE,
		RESERVOIR,
		POND,
		RIVER,
		RIVERBANK,
		STREAM,
		CANAL,
		DRAINAGE,
		OTHER_WATER,
		UNKNOWN;

		public boolean isStandingWater() {
			return this == LAKE || this == RESERVOIR || this == POND || this == OTHER_WATER;
		}

		public boolean isRiverLike() {
			return this == RIVER || this == RIVERBANK || this == STREAM || this == CANAL || this == DRAINAGE;
		}
	}

	private record HydroTileKey(double southLatitude, double westLongitude) {
		static HydroTileKey from(double latitude, double longitude) {
			double south = Math.floor(latitude / TILE_SIZE_DEGREES) * TILE_SIZE_DEGREES;
			double west = Math.floor(longitude / TILE_SIZE_DEGREES) * TILE_SIZE_DEGREES;
			return new HydroTileKey(south, west);
		}

		double northLatitude() {
			return southLatitude + TILE_SIZE_DEGREES;
		}

		double eastLongitude() {
			return westLongitude + TILE_SIZE_DEGREES;
		}

		String cacheStem() {
			return String.format(Locale.ROOT, "tile_%+.4f_%+.4f", southLatitude, westLongitude)
				.replace('+', 'p')
				.replace('-', 'm');
		}
	}

	private record HydroTile(HydroTileKey key, List<HydroFeature> features) {
	}

	private record HydroFeature(
		long id,
		WaterBodyKind kind,
		boolean area,
		String name,
		String sourceTag,
		List<GeoPoint> points
	) {
		String displayName() {
			if (name != null && !name.isBlank()) {
				return name;
			}
			if (sourceTag != null && !sourceTag.isBlank()) {
				return sourceTag;
			}
			return kind.name().toLowerCase(Locale.ROOT);
		}

		HydroFeatureCacheKey cacheKey() {
			GeoPoint first = points.getFirst();
			return new HydroFeatureCacheKey(
				id,
				kind,
				points.size(),
				quantizeCoordinate(first.latitude()),
				quantizeCoordinate(first.longitude())
			);
		}
	}

	private record GeoPoint(double latitude, double longitude) {
	}

	private record HydroFeatureCacheKey(long id, WaterBodyKind kind, int pointCount, int firstLatE7, int firstLonE7) {
	}

	private record LakeRaster(int width, int height, int[] featureIndices, List<LakeRasterFeature> features) {
		int featureIndex(int x, int y) {
			return featureIndices[y * width + x];
		}
	}

	private record LakeRasterFeature(HydroFeature feature, double waterLevelMeters) {
	}

	private record RiverRaster(int width, int height, double[] waterLevels) {
		double waterLevelMeters(int x, int y) {
			return waterLevels[y * width + x];
		}
	}

	private record SegmentProjection(double distanceMeters, double segmentFraction, GeoPoint closestPoint) {
	}

	private record SegmentHit(double distanceMeters, double downstreamProgress, GeoPoint closestPoint) {
	}

	private record FeatureHit(
		HydroFeature feature,
		double distanceMeters,
		boolean contains,
		double downstreamProgress,
		GeoPoint closestPoint
	) {
	}

	private static int quantizeCoordinate(double coordinate) {
		return (int)Math.round(coordinate * 10_000_000.0);
	}
}
