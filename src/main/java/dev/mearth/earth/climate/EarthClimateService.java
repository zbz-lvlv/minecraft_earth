package dev.mearth.earth.climate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EarthClimateService {
	public static final String TEMPERATURE_2M = "T2M";
	public static final String TEMPERATURE_2M_MAX = "T2M_MAX";
	public static final String TEMPERATURE_2M_MIN = "T2M_MIN";
	public static final String PRECIPITATION = "PRECTOTCORR";
	public static final String WIND_SPEED_2M = "WS2M";
	public static final String WIND_DIRECTION_2M = "WD2M";
	private static final double ENVIRONMENTAL_LAPSE_RATE_C_PER_KM = 6.5;
	private static final double EARTH_RADIUS_METERS = 6_378_137.0;
	private static final double CLIMATE_SLOPE_SAMPLE_DISTANCE_METERS = 5000.0;
	private static final double RAINFALL_SLOPE_INFLUENCE = 1.0; // increase to make steeper terrain change rainfall more
	private static final double RAINFALL_ASPECT_INFLUENCE = 2.0; // increase to make windward/leeward aspect matter more
	private static final double MAX_OROGRAPHIC_MULTIPLIER = 4.0;
	private static final double MIN_OROGRAPHIC_MULTIPLIER = 0.25;
	private static final double MOISTURE_FLOW_STRONG_MPS = 4.0;
	private static final double BASE_SLOPE_FOR_CLIMATE_STRONG_GRADE = 0.25;
	private static final double BASE_OROGRAPHIC_RESPONSE_STRENGTH = 0.6;
	private static final double LEEWARD_RESPONSE_FACTOR = 0.5; // lower values reduce drying on leeward slopes
	private static final double PLANT_TEMP_OPTIMAL_C = 22.0;
	private static final double PLANT_TEMP_COOL_SIGMA_C = 12.0;
	private static final double PLANT_TEMP_HOT_SIGMA_C = 9.0;
	private static final double PLANT_RAIN_HALF_SAT_MM_PER_DAY = 1.5;
	private static final double PLANT_LIMITING_FACTOR_FLOOR = 0.35;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final Map<ClimateRequestKey, ClimateSummary> CACHE = new ConcurrentHashMap<>();
	private static final Map<ClimateRequestKey, CompletableFuture<ClimateSummary>> IN_FLIGHT = new ConcurrentHashMap<>();
	private static final Path CACHE_DIR = FabricLoader.getInstance().getConfigDir().resolve("earthmod").resolve("climate");
	private static final String CACHE_VERSION = "v2";
	private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "earthmod-climate");
		thread.setDaemon(true);
		return thread;
	});

	private EarthClimateService() {
	}

	public static ClimateSummary sample(double latitude, double longitude, int startYear, int endYear) throws IOException, InterruptedException {
		return sample(latitude, longitude, startYear, endYear, List.of(
			TEMPERATURE_2M,
			PRECIPITATION,
			WIND_SPEED_2M,
			WIND_DIRECTION_2M
		));
	}

	public static ClimateSummary sample(
		double latitude,
		double longitude,
		int startYear,
		int endYear,
		Collection<String> parameters
	) throws IOException, InterruptedException {
		ClimateRequestKey key = createRequestKey(latitude, longitude, startYear, endYear, parameters.stream().distinct().sorted().toList());

		ClimateSummary cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		ClimateSummary diskCached = readCacheFile(key);
		if (diskCached != null) {
			CACHE.put(key, diskCached);
			return diskCached;
		}

		ClimateSummary fetched = fetchFromApi(key);
		CACHE.put(key, fetched);
		writeCacheFile(key, fetched);
		return fetched;
	}

	public static ClimateSummary getCachedOrFetchAsync(double latitude, double longitude, int startYear, int endYear) {
		ClimateRequestKey key = createRequestKey(
			latitude,
			longitude,
			startYear,
			endYear,
			List.of(TEMPERATURE_2M, PRECIPITATION, WIND_SPEED_2M, WIND_DIRECTION_2M)
		);
		return getCachedOrFetchAsync(key);
	}

	public static ClimateSummary getCachedOrFetchAsync(
		double latitude,
		double longitude,
		int startYear,
		int endYear,
		Collection<String> parameters
	) {
		ClimateRequestKey key = createRequestKey(latitude, longitude, startYear, endYear, parameters.stream().distinct().sorted().toList());
		return getCachedOrFetchAsync(key);
	}

	public static EffectiveClimate getEffectiveCachedOrFetchAsync(
		double latitude,
		double longitude,
		double altitudeMeters,
		int startYear,
		int endYear
	) {
		double southLatitude = Math.floor(latitude);
		double northLatitude = Math.ceil(latitude);
		double westLongitude = Math.floor(longitude);
		double eastLongitude = Math.ceil(longitude);

		ClimateSummary northWest = getCachedOrFetchAsync(northLatitude, westLongitude, startYear, endYear);
		ClimateSummary northEast = getCachedOrFetchAsync(northLatitude, eastLongitude, startYear, endYear);
		ClimateSummary southWest = getCachedOrFetchAsync(southLatitude, westLongitude, startYear, endYear);
		ClimateSummary southEast = getCachedOrFetchAsync(southLatitude, eastLongitude, startYear, endYear);
		if (northWest == null || northEast == null || southWest == null || southEast == null) {
			return null;
		}

		double longitudeFraction = fraction(longitude, westLongitude, eastLongitude);
		double latitudeFraction = fraction(latitude, southLatitude, northLatitude);
		return computeEffectiveClimate(
			latitude,
			longitude,
			altitudeMeters,
			longitudeFraction,
			latitudeFraction,
			northWest,
			northEast,
			southWest,
			southEast
		);
	}

	public static EffectiveClimate sampleEffective(
		double latitude,
		double longitude,
		double altitudeMeters,
		int startYear,
		int endYear
	) throws IOException, InterruptedException {
		double southLatitude = Math.floor(latitude);
		double northLatitude = Math.ceil(latitude);
		double westLongitude = Math.floor(longitude);
		double eastLongitude = Math.ceil(longitude);

		ClimateSummary northWest = sample(northLatitude, westLongitude, startYear, endYear);
		ClimateSummary northEast = sample(northLatitude, eastLongitude, startYear, endYear);
		ClimateSummary southWest = sample(southLatitude, westLongitude, startYear, endYear);
		ClimateSummary southEast = sample(southLatitude, eastLongitude, startYear, endYear);

		double longitudeFraction = fraction(longitude, westLongitude, eastLongitude);
		double latitudeFraction = fraction(latitude, southLatitude, northLatitude);
		return computeEffectiveClimate(
			latitude,
			longitude,
			altitudeMeters,
			longitudeFraction,
			latitudeFraction,
			northWest,
			northEast,
			southWest,
			southEast
		);
	}

	private static EffectiveClimate computeEffectiveClimate(
		double latitude,
		double longitude,
		double altitudeMeters,
		double longitudeFraction,
		double latitudeFraction,
		ClimateSummary northWest,
		ClimateSummary northEast,
		ClimateSummary southWest,
		ClimateSummary southEast
	) {
		double interpolatedReferenceAltitude = bilerp(
			southWest.altitude(),
			southEast.altitude(),
			northWest.altitude(),
			northEast.altitude(),
			longitudeFraction,
			latitudeFraction
		);
		double interpolatedTemperature = bilerp(
			southWest.averageTemperature(),
			southEast.averageTemperature(),
			northWest.averageTemperature(),
			northEast.averageTemperature(),
			longitudeFraction,
			latitudeFraction
		);
		double interpolatedRainfall = bilerp(
			southWest.averageRainfall(),
			southEast.averageRainfall(),
			northWest.averageRainfall(),
			northEast.averageRainfall(),
			longitudeFraction,
			latitudeFraction
		);
		Vector2 moistureFlowVector = moistureFlowVector(
			southWest,
			southEast,
			northWest,
			northEast,
			longitudeFraction,
			latitudeFraction
		);
		TerrainGradient terrainGradient = sampleTerrainGradient(latitude, longitude);
		double altitudeDeltaMeters = altitudeMeters - interpolatedReferenceAltitude;
		double adjustedTemperature = interpolatedTemperature - altitudeDeltaMeters * ENVIRONMENTAL_LAPSE_RATE_C_PER_KM / 1000.0;
		double windwardness = dot(moistureFlowVector, terrainGradient.uphillUnitVector());
		double moistureStrength = clamp(moistureFlowVector.magnitude() / MOISTURE_FLOW_STRONG_MPS, 0.0, 1.5);
		double slopeStrength = clamp(
			terrainGradient.slopeForClimate() / BASE_SLOPE_FOR_CLIMATE_STRONG_GRADE * RAINFALL_SLOPE_INFLUENCE,
			0.0,
			2.0
		);
		double asymmetricWindwardness = windwardness >= 0.0 ? windwardness : windwardness * LEEWARD_RESPONSE_FACTOR;
		double orographicMultiplier = clamp(
			Math.exp(asymmetricWindwardness * RAINFALL_ASPECT_INFLUENCE * moistureStrength * slopeStrength * BASE_OROGRAPHIC_RESPONSE_STRENGTH),
			MIN_OROGRAPHIC_MULTIPLIER,
			MAX_OROGRAPHIC_MULTIPLIER
		);
		double adjustedRainfall = interpolatedRainfall * orographicMultiplier;
		double plantGrowthScore = plantGrowthScore(adjustedTemperature, adjustedRainfall);

		return new EffectiveClimate(
			latitude,
			longitude,
			altitudeMeters,
			interpolatedReferenceAltitude,
			adjustedTemperature,
			adjustedRainfall,
			plantGrowthScore,
			moistureFlowVector.x(),
			moistureFlowVector.y(),
			sourceDegrees(moistureFlowVector),
			terrainGradient.uphillUnitVector().x(),
			terrainGradient.uphillUnitVector().y(),
			windwardness,
			terrainGradient.slopeForClimate(),
			slopeDegrees(terrainGradient.slopeForClimate()),
			aspectDegrees(terrainGradient.uphillUnitVector()),
			orographicMultiplier
		);
	}

	private static ClimateSummary getCachedOrFetchAsync(ClimateRequestKey key) {
		ClimateSummary cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		ClimateSummary diskCached = readCacheFile(key);
		if (diskCached != null) {
			CACHE.put(key, diskCached);
			return diskCached;
		}

		IN_FLIGHT.computeIfAbsent(key, ignored -> CompletableFuture.supplyAsync(() -> {
			try {
				ClimateSummary fetched = fetchFromApi(key);
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
				EarthMod.LOGGER.warn("Async climate fetch failed for {}, {}: {}", key.latitude(), key.longitude(), throwable.toString());
			}
		}));

		return null;
	}

	private static ClimateSummary fetchFromApi(ClimateRequestKey key) throws IOException, InterruptedException {
		String encodedParameters = URLEncoder.encode(String.join(",", key.parameters()), StandardCharsets.UTF_8);
		String url = EarthConfig.nasaPowerMonthlyEndpoint()
			+ "?parameters=" + encodedParameters
			+ "&community=AG"
			+ "&longitude=" + key.longitude()
			+ "&latitude=" + key.latitude()
			+ "&start=" + key.startYear()
			+ "&end=" + key.endYear()
			+ "&format=JSON";

		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(15))
			.header("User-Agent", "earthmod/1.0 (Fabric Minecraft mod)")
			.GET()
			.build();
		HttpResponse<java.io.InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() >= 400) {
			throw new IOException("NASA POWER climate request returned HTTP " + response.statusCode());
		}

		try (Reader reader = new InputStreamReader(response.body(), StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			ClimateSummary data = parseClimateData(root, key);
			EarthMod.LOGGER.info(
				"Fetched NASA POWER climate data for {}, {} ({}-{})",
				key.latitude(),
				key.longitude(),
				key.startYear(),
				key.endYear()
			);
			return data;
		}
	}

	private static ClimateSummary parseClimateData(JsonObject root, ClimateRequestKey key) throws IOException {
		JsonObject geometry = getObject(root, "geometry");
		double altitude = readAltitude(geometry);
		JsonObject properties = getObject(root, "properties");
		JsonObject parameterObject = getObject(properties, "parameter");
		JsonObject temperatureValues = getObject(parameterObject, TEMPERATURE_2M);
		JsonObject rainfallValues = getObject(parameterObject, PRECIPITATION);
		JsonObject windSpeedValues = getObject(parameterObject, WIND_SPEED_2M);
		JsonObject windDirectionValues = getObject(parameterObject, WIND_DIRECTION_2M);
		double averageTemperature = averageAnnualValues(temperatureValues, key.startYear(), key.endYear(), TEMPERATURE_2M);
		double averageRainfall = averageAnnualValues(rainfallValues, key.startYear(), key.endYear(), PRECIPITATION);
		List<Double> averageMonthlyRainfall = averageMonthlyValues(rainfallValues, key.startYear(), key.endYear(), PRECIPITATION);
		List<Double> averageMonthlyWindSpeed = averageMonthlyValues(windSpeedValues, key.startYear(), key.endYear(), WIND_SPEED_2M);
		List<Double> averageMonthlyWindDirection = averageMonthlyDirectionValues(
			windDirectionValues,
			key.startYear(),
			key.endYear(),
			WIND_DIRECTION_2M
		);

		return new ClimateSummary(
			key.latitude(),
			key.longitude(),
			altitude,
			averageTemperature,
			averageRainfall,
			averageMonthlyRainfall,
			averageMonthlyWindSpeed,
			averageMonthlyWindDirection
		);
	}

	private static JsonObject getObject(JsonObject parent, String member) throws IOException {
		JsonElement element = parent.get(member);
		if (element == null || !element.isJsonObject()) {
			throw new IOException("Missing JSON object '" + member + "'");
		}
		return element.getAsJsonObject();
	}

	private static double readAltitude(JsonObject geometry) throws IOException {
		JsonElement coordinatesElement = geometry.get("coordinates");
		if (coordinatesElement == null || !coordinatesElement.isJsonArray() || coordinatesElement.getAsJsonArray().size() < 3) {
			throw new IOException("Missing geometry.coordinates altitude");
		}
		return coordinatesElement.getAsJsonArray().get(2).getAsDouble();
	}

	private static double averageAnnualValues(JsonObject valuesObject, int startYear, int endYear, String parameterName) throws IOException {
		double total = 0.0;
		int count = 0;
		for (int year = startYear; year <= endYear; year++) {
			String annualKey = year + "13";
			JsonElement valueElement = valuesObject.get(annualKey);
			if (valueElement == null || !valueElement.isJsonPrimitive()) {
				continue;
			}
			double value = valueElement.getAsDouble();
			if (Double.isNaN(value) || value <= -999.0) {
				continue;
			}
			total += value;
			count++;
		}
		if (count == 0) {
			throw new IOException("No annual climate values found for parameter '" + parameterName + "'");
		}
		return total / count;
	}

	private static List<Double> averageMonthlyValues(JsonObject valuesObject, int startYear, int endYear, String parameterName) throws IOException {
		Double[] monthlyAverages = new Double[12];
		for (int month = 1; month <= 12; month++) {
			double total = 0.0;
			int count = 0;
			for (int year = startYear; year <= endYear; year++) {
				Double value = readMonthlyValue(valuesObject, year, month);
				if (value == null) {
					continue;
				}
				total += value;
				count++;
			}
			if (count == 0) {
				throw new IOException("No monthly climate values found for parameter '" + parameterName + "' month " + month);
			}
			monthlyAverages[month - 1] = total / count;
		}
		return List.of(monthlyAverages);
	}

	private static List<Double> averageMonthlyDirectionValues(
		JsonObject valuesObject,
		int startYear,
		int endYear,
		String parameterName
	) throws IOException {
		Double[] monthlyAverages = new Double[12];
		for (int month = 1; month <= 12; month++) {
			double sinTotal = 0.0;
			double cosTotal = 0.0;
			int count = 0;
			for (int year = startYear; year <= endYear; year++) {
				Double value = readMonthlyValue(valuesObject, year, month);
				if (value == null) {
					continue;
				}
				double radians = Math.toRadians(value);
				sinTotal += Math.sin(radians);
				cosTotal += Math.cos(radians);
				count++;
			}
			if (count == 0) {
				throw new IOException("No monthly climate values found for parameter '" + parameterName + "' month " + month);
			}
			monthlyAverages[month - 1] = normalizeDegrees(Math.toDegrees(Math.atan2(sinTotal / count, cosTotal / count)));
		}
		return List.of(monthlyAverages);
	}

	private static Double readMonthlyValue(JsonObject valuesObject, int year, int month) {
		String monthlyKey = "%d%02d".formatted(year, month);
		JsonElement valueElement = valuesObject.get(monthlyKey);
		if (valueElement == null || !valueElement.isJsonPrimitive()) {
			return null;
		}
		double value = valueElement.getAsDouble();
		if (Double.isNaN(value) || value <= -999.0) {
			return null;
		}
		return value;
	}

	private static ClimateSummary readCacheFile(ClimateRequestKey key) {
		Path path = cachePath(key);
		if (!Files.exists(path)) {
			return null;
		}

		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			ClimateSummary cached = GSON.fromJson(reader, ClimateSummary.class);
			return cached;
		} catch (Exception exception) {
			EarthMod.LOGGER.warn("Failed to read climate cache {}: {}", path, exception.toString());
			return null;
		}
	}

	private static void writeCacheFile(ClimateRequestKey key, ClimateSummary data) {
		Path path = cachePath(key);
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(data, writer);
			}
		} catch (IOException exception) {
			EarthMod.LOGGER.warn("Failed to write climate cache {}: {}", path, exception.toString());
		}
	}

	private static Path cachePath(ClimateRequestKey key) {
		return CACHE_DIR.resolve(
			"%s_%s_%d_%d_%s_%s.json".formatted(
				formatCoordinateKey(key.latitudeKey()),
				formatCoordinateKey(key.longitudeKey()),
				key.startYear(),
				key.endYear(),
				formatParameterKey(key.parameters()),
				CACHE_VERSION
			)
		);
	}

	private static String formatCoordinateKey(int coordinateKey) {
		return coordinateKey < 0 ? "m" + -coordinateKey : Integer.toString(coordinateKey);
	}

	private static int snapLatitudeKey(double latitude) {
		double clamped = Math.max(-90.0, Math.min(90.0, latitude));
		return (int)Math.round(clamped);
	}

	private static int snapLongitudeKey(double longitude) {
		double normalized = longitude;
		while (normalized < -180.0) {
			normalized += 360.0;
		}
		while (normalized > 180.0) {
			normalized -= 360.0;
		}
		return (int)Math.round(normalized);
	}

	private static String formatParameterKey(List<String> parameters) {
		return String.join("-", parameters).replace(',', '-');
	}

	private static double keyToCoordinate(int coordinateKey) {
		return coordinateKey;
	}

	private static Vector2 moistureFlowVector(
		ClimateSummary southWest,
		ClimateSummary southEast,
		ClimateSummary northWest,
		ClimateSummary northEast,
		double xFraction,
		double yFraction
	) {
		double weightedEast = 0.0;
		double weightedNorth = 0.0;
		double totalWeight = 0.0;

		for (int monthIndex = 0; monthIndex < 12; monthIndex++) {
			double monthlyRainfall = bilerp(
				southWest.averageMonthlyRainfall().get(monthIndex),
				southEast.averageMonthlyRainfall().get(monthIndex),
				northWest.averageMonthlyRainfall().get(monthIndex),
				northEast.averageMonthlyRainfall().get(monthIndex),
				xFraction,
				yFraction
			);
			if (monthlyRainfall <= 0.0) {
				continue;
			}

			double monthlyWindSpeed = bilerp(
				southWest.averageMonthlyWindSpeed().get(monthIndex),
				southEast.averageMonthlyWindSpeed().get(monthIndex),
				northWest.averageMonthlyWindSpeed().get(monthIndex),
				northEast.averageMonthlyWindSpeed().get(monthIndex),
				xFraction,
				yFraction
			);
			double monthlyWindDirection = bilerpAngleDegrees(
				southWest.averageMonthlyWindDirection().get(monthIndex),
				southEast.averageMonthlyWindDirection().get(monthIndex),
				northWest.averageMonthlyWindDirection().get(monthIndex),
				northEast.averageMonthlyWindDirection().get(monthIndex),
				xFraction,
				yFraction
			);
			Vector2 monthlyFlowVector = windFlowVector(monthlyWindSpeed, monthlyWindDirection);
			weightedEast += monthlyFlowVector.x() * monthlyRainfall;
			weightedNorth += monthlyFlowVector.y() * monthlyRainfall;
			totalWeight += monthlyRainfall;
		}

		if (totalWeight <= 1.0e-9) {
			return new Vector2(0.0, 0.0);
		}
		return new Vector2(weightedEast / totalWeight, weightedNorth / totalWeight);
	}

	private static TerrainGradient sampleTerrainGradient(double latitude, double longitude) {
		double latitudeRadians = Math.toRadians(latitude);
		double latitudeDelta = Math.toDegrees(CLIMATE_SLOPE_SAMPLE_DISTANCE_METERS / EARTH_RADIUS_METERS);
		double eastWestMetersPerDegree = Math.cos(latitudeRadians) * EARTH_RADIUS_METERS * Math.PI / 180.0;
		double longitudeDelta = eastWestMetersPerDegree > 1.0
			? CLIMATE_SLOPE_SAMPLE_DISTANCE_METERS / eastWestMetersPerDegree
			: latitudeDelta;

		double north = TerrainTileService.sampleMeters(latitude + latitudeDelta, longitude);
		double south = TerrainTileService.sampleMeters(latitude - latitudeDelta, longitude);
		double east = TerrainTileService.sampleMeters(latitude, longitude + longitudeDelta);
		double west = TerrainTileService.sampleMeters(latitude, longitude - longitudeDelta);

		double dzdx = (east - west) / (2.0 * CLIMATE_SLOPE_SAMPLE_DISTANCE_METERS);
		double dzdy = (north - south) / (2.0 * CLIMATE_SLOPE_SAMPLE_DISTANCE_METERS);
		double slopeForClimate = Math.hypot(dzdx, dzdy);
		return new TerrainGradient(normalize(new Vector2(dzdx, dzdy)), slopeForClimate);
	}

	private static double fraction(double value, double min, double max) {
		if (Double.compare(min, max) == 0) {
			return 0.0;
		}
		return clamp((value - min) / (max - min), 0.0, 1.0);
	}

	private static double bilerp(
		double southWest,
		double southEast,
		double northWest,
		double northEast,
		double xFraction,
		double yFraction
	) {
		double south = lerp(southWest, southEast, xFraction);
		double north = lerp(northWest, northEast, xFraction);
		return lerp(south, north, yFraction);
	}

	private static double bilerpAngleDegrees(
		double southWest,
		double southEast,
		double northWest,
		double northEast,
		double xFraction,
		double yFraction
	) {
		Vector2 south = lerpUnitVector(directionUnitVector(southWest), directionUnitVector(southEast), xFraction);
		Vector2 north = lerpUnitVector(directionUnitVector(northWest), directionUnitVector(northEast), xFraction);
		Vector2 blended = lerpUnitVector(south, north, yFraction);
		if (blended.magnitude() < 1.0e-9) {
			return 0.0;
		}
		return normalizeDegrees(Math.toDegrees(Math.atan2(blended.x(), blended.y())));
	}

	private static double lerp(double start, double end, double delta) {
		return start + (end - start) * delta;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double plantGrowthScore(double temperatureC, double rainfallMmPerDay) {
		double temperatureScore = temperaturePlantSuitability(temperatureC);
		double rainfallScore = rainfallPlantSuitability(rainfallMmPerDay);
		double limitingFactor = Math.min(temperatureScore, rainfallScore);
		double balancedMean = Math.sqrt(temperatureScore * rainfallScore);

		// Plants are limited by the weaker factor, but not so harshly that one
		// middling variable collapses the score when the other is excellent.
		return clamp(
			balancedMean * (PLANT_LIMITING_FACTOR_FLOOR + (1.0 - PLANT_LIMITING_FACTOR_FLOOR) * limitingFactor),
			0.0,
			1.0
		);
	}

	private static double temperaturePlantSuitability(double temperatureC) {
		double delta = temperatureC - PLANT_TEMP_OPTIMAL_C;
		double sigma = delta < 0.0 ? PLANT_TEMP_COOL_SIGMA_C : PLANT_TEMP_HOT_SIGMA_C;
		return Math.exp(-(delta * delta) / (2.0 * sigma * sigma));
	}

	private static double rainfallPlantSuitability(double rainfallMmPerDay) {
		double positiveRainfall = Math.max(0.0, rainfallMmPerDay);
		double moistureAvailability = positiveRainfall / (positiveRainfall + PLANT_RAIN_HALF_SAT_MM_PER_DAY);
		return clamp(moistureAvailability, 0.0, 1.0);
	}

	private static double dot(Vector2 left, Vector2 right) {
		return left.x() * right.x() + left.y() * right.y();
	}

	private static Vector2 lerpUnitVector(Vector2 start, Vector2 end, double delta) {
		return normalize(new Vector2(
			lerp(start.x(), end.x(), delta),
			lerp(start.y(), end.y(), delta)
		));
	}

	private static double slopeDegrees(double slopeGrade) {
		return Math.toDegrees(Math.atan(slopeGrade));
	}

	private static double aspectDegrees(Vector2 uphillUnitVector) {
		if (uphillUnitVector.magnitude() < 1.0e-9) {
			return Double.NaN;
		}
		double radians = Math.atan2(uphillUnitVector.x(), uphillUnitVector.y());
		double degrees = Math.toDegrees(radians);
		return degrees < 0.0 ? degrees + 360.0 : degrees;
	}

	private static double sourceDegrees(Vector2 flowVector) {
		if (flowVector.magnitude() < 1.0e-9) {
			return Double.NaN;
		}
		Vector2 sourceVector = normalize(new Vector2(-flowVector.x(), -flowVector.y()));
		double radians = Math.atan2(sourceVector.x(), sourceVector.y());
		return normalizeDegrees(Math.toDegrees(radians));
	}

	private static double normalizeDegrees(double degrees) {
		double normalized = degrees % 360.0;
		return normalized < 0.0 ? normalized + 360.0 : normalized;
	}

	private static Vector2 directionUnitVector(double directionDegrees) {
		double radians = Math.toRadians(directionDegrees);
		return new Vector2(Math.sin(radians), Math.cos(radians));
	}

	private static Vector2 windFlowVector(double windSpeed, double windDirectionDegrees) {
		Vector2 sourceUnitVector = directionUnitVector(windDirectionDegrees);
		return new Vector2(-sourceUnitVector.x() * windSpeed, -sourceUnitVector.y() * windSpeed);
	}

	private static Vector2 normalize(Vector2 vector) {
		double magnitude = vector.magnitude();
		if (magnitude < 1.0e-9) {
			return new Vector2(0.0, 0.0);
		}
		return new Vector2(vector.x() / magnitude, vector.y() / magnitude);
	}

	private static ClimateRequestKey createRequestKey(
		double latitude,
		double longitude,
		int startYear,
		int endYear,
		List<String> sortedParameters
	) {
		if (sortedParameters.isEmpty()) {
			throw new IllegalArgumentException("At least one NASA POWER parameter is required");
		}
		if (startYear > endYear) {
			throw new IllegalArgumentException("Start year must be <= end year");
		}

		int latitudeKey = snapLatitudeKey(latitude);
		int longitudeKey = snapLongitudeKey(longitude);
		return new ClimateRequestKey(latitudeKey, longitudeKey, startYear, endYear, sortedParameters);
	}

	public record ClimateSummary(
		double latitude,
		double longitude,
		double altitude,
		double averageTemperature,
		double averageRainfall,
		List<Double> averageMonthlyRainfall,
		List<Double> averageMonthlyWindSpeed,
		List<Double> averageMonthlyWindDirection
	) {
	}

	public record EffectiveClimate(
		double latitude,
		double longitude,
		double altitude,
		double referenceAltitude,
		double averageTemperature,
		double averageRainfall,
		double plantGrowthScore,
		double moistureVectorEast,
		double moistureVectorNorth,
		double moistureSourceDegrees,
		double uphillVectorEast,
		double uphillVectorNorth,
		double windwardness,
		double slopeForClimate,
		double slopeDegrees,
		double aspectDegrees,
		double orographicMultiplier
	) {
	}

	private record Vector2(double x, double y) {
		private double magnitude() {
			return Math.hypot(this.x, this.y);
		}
	}

	private record TerrainGradient(Vector2 uphillUnitVector, double slopeForClimate) {
	}

	private record ClimateRequestKey(
		int latitudeKey,
		int longitudeKey,
		int startYear,
		int endYear,
		List<String> parameters
	) {
		private double latitude() {
			return keyToCoordinate(this.latitudeKey);
		}

		private double longitude() {
			return keyToCoordinate(this.longitudeKey);
		}
	}
}
