package dev.mearth.earth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EarthConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("earthmod.json");
	private static Data data = new Data();

	private EarthConfig() {
	}

	public static void load() {
		try {
			Files.createDirectories(PATH.getParent());
			if (Files.exists(PATH)) {
				try (Reader reader = Files.newBufferedReader(PATH)) {
					Data loaded = GSON.fromJson(reader, Data.class);
					if (loaded != null) {
						data = loaded;
					}
				}
			}
			save();
		} catch (IOException exception) {
			throw new RuntimeException("Failed to load earthmod config", exception);
		}
	}

	public static void save() throws IOException {
		try (Writer writer = Files.newBufferedWriter(PATH)) {
			GSON.toJson(data, writer);
		}
	}

	public static double metersPerBlock() {
		return Math.max(1.0, data.metersPerBlock);
	}

	public static int minY() {
		return data.minY;
	}

	public static int worldHeight() {
		return data.worldHeight;
	}

	public static int seaLevel() {
		return data.seaLevel;
	}

	public static int terrainZoom() {
		return Math.max(0, Math.min(15, data.terrainZoom));
	}

	public static String nominatimEndpoint() {
		return data.nominatimEndpoint;
	}

	public static String nasaPowerMonthlyEndpoint() {
		return data.nasaPowerMonthlyEndpoint;
	}

	public static String hydroOverpassEndpoint() {
		return data.hydroOverpassEndpoint;
	}

	public static final class Data {
		public double metersPerBlock = 25.0;
		public int seaLevel = 0;
		public int minY = -512;
		public int worldHeight = 1024;
		public int terrainZoom = 13;
		public String nominatimEndpoint = "https://nominatim.openstreetmap.org/search";
		public String nasaPowerMonthlyEndpoint = "https://power.larc.nasa.gov/api/temporal/monthly/point";
		public String hydroOverpassEndpoint = "https://overpass-api.de/api/interpreter";
	}
}
