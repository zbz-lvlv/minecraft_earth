package dev.mearth.earth.geo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mearth.earth.EarthMod;
import dev.mearth.earth.config.EarthConfig;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GeocodingService {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final Map<String, GeoResult> CACHE = new ConcurrentHashMap<>();

	private GeocodingService() {
	}

	public static GeoResult geocode(String query) throws IOException, InterruptedException {
		String normalized = query.trim().toLowerCase();
		GeoResult cached = CACHE.get(normalized);
		if (cached != null) {
			return cached;
		}

		String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
		String url = EarthConfig.nominatimEndpoint() + "?format=jsonv2&limit=1&q=" + encoded;
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(10))
			.header("User-Agent", "earthmod/1.0 (Fabric Minecraft mod)")
			.GET()
			.build();
		HttpResponse<java.io.InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() >= 400) {
			throw new IOException("Geocoder returned HTTP " + response.statusCode());
		}

		try (Reader reader = new InputStreamReader(response.body(), StandardCharsets.UTF_8)) {
			JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
			if (array.isEmpty()) {
				throw new IOException("No geocoding result for '" + query + "'");
			}

			JsonObject first = array.get(0).getAsJsonObject();
			GeoResult result = new GeoResult(
				first.get("display_name").getAsString(),
				first.get("lat").getAsDouble(),
				first.get("lon").getAsDouble()
			);
			CACHE.put(normalized, result);
			EarthMod.LOGGER.info("Geocoded '{}' to {}, {}", query, result.latitude(), result.longitude());
			return result;
		}
	}
}
