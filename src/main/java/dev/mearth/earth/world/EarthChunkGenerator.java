package dev.mearth.earth.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.mearth.earth.climate.EarthClimateService;
import dev.mearth.earth.config.EarthConfig;
import dev.mearth.earth.geo.EarthProjection;
import dev.mearth.earth.hydro.EarthHydroService;
import dev.mearth.earth.terrain.TerrainTileService;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.ChunkRegion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class EarthChunkGenerator extends ChunkGenerator {
	private static final int CLIMATE_START_YEAR = 2018;
	private static final int CLIMATE_END_YEAR = 2020;
	private static final int WORLD_Y_SHIFT = -40;
	private static final double LOCAL_SLOPE_SAMPLE_DISTANCE_METERS = 35.0;
	private static final double STONE_SLOPE_START_DEGREES = 15.0;
	private static final double STONE_SLOPE_MIDPOINT_DEGREES = 50.0;
	private static final double STONE_SLOPE_FULL_DEGREES = 60.0;
	private static final double STONE_PROBABILITY_NOISE_AMPLITUDE = 0.08;
	private static final double STONE_PATCH_SCALE_BLOCKS = 42.0;
	private static final double SNOW_SLOPE_THRESHOLD = 40.0 / 90.0;
	private static final double SNOW_SLOPE_NOISE_AMPLITUDE = 0.05;
	private static final double SNOW_TEMP_NOISE_AMPLITUDE_C = 0.65;
	private static final double SNOW_BASE_START_C = 1.0;
	private static final double SNOW_ASPECT_AMPLITUDE_C = 2.5;
	private static final double UNDERGROWTH_START_GROWTH = 0.60;
	private static final double UNDERGROWTH_FULL_GROWTH = 0.80;
	private static final int SURFACE_COLUMN_CACHE_LIMIT = 32_768;
	public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
		Codec.DOUBLE.optionalFieldOf("meters_per_block", 25.0).forGetter(EarthChunkGenerator::metersPerBlock),
		Codec.INT.optionalFieldOf("min_y", -512).forGetter(EarthChunkGenerator::getMinimumY),
		Codec.INT.optionalFieldOf("world_height", 1024).forGetter(EarthChunkGenerator::getWorldHeight),
		Codec.INT.optionalFieldOf("sea_level", 0).forGetter(EarthChunkGenerator::getSeaLevel)
	).apply(instance, EarthChunkGenerator::new));

	private static final BlockState AIR = Blocks.AIR.getDefaultState();
	private static final BlockState STONE = Blocks.STONE.getDefaultState();
	private static final BlockState SAND = Blocks.SAND.getDefaultState();
	private static final BlockState GRASS_BLOCK = Blocks.GRASS_BLOCK.getDefaultState();
	private static final BlockState SHORT_GRASS = Blocks.SHORT_GRASS.getDefaultState();
	private static final BlockState FERN = Blocks.FERN.getDefaultState();
	private static final BlockState DANDELION = Blocks.DANDELION.getDefaultState();
	private static final BlockState POPPY = Blocks.POPPY.getDefaultState();
	private static final BlockState AZURE_BLUET = Blocks.AZURE_BLUET.getDefaultState();
	private static final BlockState OXEYE_DAISY = Blocks.OXEYE_DAISY.getDefaultState();
	private static final BlockState SNOW_BLOCK = Blocks.SNOW_BLOCK.getDefaultState();
	private static final BlockState ACACIA_LEAVES = Blocks.ACACIA_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
	private static final BlockState OAK_LEAVES = Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
	private static final BlockState JUNGLE_LEAVES = Blocks.JUNGLE_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
	private static final BlockState WATER = Blocks.WATER.getDefaultState();
	private static final BlockState ICE = Blocks.ICE.getDefaultState();
	private static final int VEGETATION_PLACE_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
	private final double metersPerBlock;
	private final int minY;
	private final int worldHeight;
	private final int seaLevel;
	private final Map<SurfaceColumnCacheKey, SurfaceColumn> surfaceColumnCache = Collections.synchronizedMap(
		new LinkedHashMap<>(SURFACE_COLUMN_CACHE_LIMIT, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<SurfaceColumnCacheKey, SurfaceColumn> eldest) {
				return size() > SURFACE_COLUMN_CACHE_LIMIT;
			}
		}
	);

	public EarthChunkGenerator(BiomeSource biomeSource, double metersPerBlock, int minY, int worldHeight, int seaLevel) {
		super(biomeSource);
		this.metersPerBlock = metersPerBlock;
		this.minY = minY;
		this.worldHeight = worldHeight;
		this.seaLevel = seaLevel;
	}

	public double metersPerBlock() {
		return this.metersPerBlock;
	}

	public double eastWestMetersPerBlock() {
		return effectiveMetersPerBlock() * 30.0 / 25.0;
	}

	public double northSouthMetersPerBlock() {
		return effectiveMetersPerBlock();
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> getCodec() {
		return CODEC;
	}

	@Override
	public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) {
	}

	@Override
	public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess, StructureAccessor structureAccessor, Chunk chunk, GenerationStep.Carver carverStep) {
	}

	@Override
	public void populateEntities(ChunkRegion region) {
	}

	@Override
	public void generateFeatures(StructureWorldAccess world, Chunk chunk, StructureAccessor structureAccessor) {
		BlockPos.Mutable mutable = new BlockPos.Mutable();
		int chunkStartX = chunk.getPos().getStartX();
		int chunkStartZ = chunk.getPos().getStartZ();
		int maxY = this.minY + this.worldHeight - 1;
		SurfaceColumn[] columns = buildChunkSurfaceColumns(chunkStartX, chunkStartZ, maxY);

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int worldX = chunkStartX + localX;
				int worldZ = chunkStartZ + localZ;
				SurfaceColumn surface = columns[columnIndex(localX, localZ)];
				int surfaceY = surface.surfaceY();
				if (surfaceY < shiftedSeaLevel()) {
					continue;
				}

				if (!surface.supportsVegetation()) {
					continue;
				}

				mutable.set(worldX, surfaceY + 1, worldZ);
				if (!world.getBlockState(mutable).isAir()) {
					continue;
				}

				placeVegetation(world, mutable, surfaceY, surface.surfaceBlock(), surface.growth(), worldX, worldZ);
			}
		}
	}

	@Override
	public void setStructureStarts(
		DynamicRegistryManager registryManager,
		StructurePlacementCalculator placementCalculator,
		StructureAccessor structureAccessor,
		Chunk chunk,
		StructureTemplateManager structureTemplateManager
	) {
		// Intentionally empty: disable villages, temples, lava lakes, and other vanilla structure starts.
	}

	@Override
	public void addStructureReferences(StructureWorldAccess world, StructureAccessor structureAccessor, Chunk chunk) {
		// Intentionally empty because structure generation is disabled.
	}

	@Override
	public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig, StructureAccessor structureAccessor, Chunk chunk) {
		BlockPos.Mutable mutable = new BlockPos.Mutable();
		int chunkStartX = chunk.getPos().getStartX();
		int chunkStartZ = chunk.getPos().getStartZ();
		int maxY = this.minY + this.worldHeight - 1;
		SurfaceColumn[] columns = buildChunkSurfaceColumns(chunkStartX, chunkStartZ, maxY);

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int worldX = chunkStartX + localX;
				int worldZ = chunkStartZ + localZ;
				SurfaceColumn surface = columns[columnIndex(localX, localZ)];
				int surfaceY = surface.surfaceY();

				for (int y = this.minY; y <= surfaceY; y++) {
					mutable.set(worldX, y, worldZ);
					chunk.setBlockState(mutable, pickGroundBlock(y, surfaceY, surface.surfaceBlock()), false);
				}

				Integer lakeWaterSurfaceY = surface.waterSurfaceY();
				if (lakeWaterSurfaceY != null) {
					for (int y = surfaceY + 1; y <= lakeWaterSurfaceY && y <= maxY; y++) {
						mutable.set(worldX, y, worldZ);
						chunk.setBlockState(mutable, surface.lakeSurfaceBlock(), false);
					}
				} else if (surfaceY < shiftedSeaLevel()) {
					for (int y = surfaceY + 1; y <= shiftedSeaLevel() && y <= maxY; y++) {
						mutable.set(worldX, y, worldZ);
						chunk.setBlockState(mutable, WATER, false);
					}
				}
			}
		}

		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
		SurfaceColumn surface = getSurfaceColumn(x, z, this.minY + this.worldHeight - 1);
		int topY = surface.waterSurfaceY() != null ? surface.waterSurfaceY() : surface.surfaceY();
		return topY + 1;
	}

	@Override
	public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
		BlockState[] states = new BlockState[this.worldHeight];
		SurfaceColumn surface = getSurfaceColumn(x, z, this.minY + this.worldHeight - 1);
		int surfaceY = surface.surfaceY();

		for (int index = 0; index < states.length; index++) {
			int y = this.minY + index;
			if (y <= surfaceY) {
				states[index] = pickGroundBlock(y, surfaceY, surface.surfaceBlock());
			} else if (surface.waterSurfaceY() != null && y <= surface.waterSurfaceY()) {
				states[index] = surface.lakeSurfaceBlock();
			} else if (y <= shiftedSeaLevel()) {
				states[index] = WATER;
			} else {
				states[index] = AIR;
			}
		}

		return new VerticalBlockSample(this.minY, states);
	}

	@Override
	public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
		double eastWestMetersPerBlock = eastWestMetersPerBlock();
		double northSouthMetersPerBlock = northSouthMetersPerBlock();
		double verticalMetersPerBlock = effectiveMetersPerBlock();
		double latitude = EarthProjection.blockZToLatitude(pos.getZ(), northSouthMetersPerBlock);
		double longitude = EarthProjection.blockXToLongitude(pos.getX(), eastWestMetersPerBlock);
		double equivalentAltitudeMeters = (pos.getY() - WORLD_Y_SHIFT) * verticalMetersPerBlock;
		double groundAltitudeMeters = TerrainTileService.sampleMeters(latitude, longitude);
		text.add(
			"Earth lat/lon/alt: %.5f, %.5f, %.0f m (ground: %.0f m)".formatted(
				latitude,
				longitude,
				equivalentAltitudeMeters,
				groundAltitudeMeters
			)
		);
		text.add(
			"Earth scale: E/W 1:%.1f m, N/S 1:%.1f m, Y 1:%.1f m".formatted(
				eastWestMetersPerBlock,
				northSouthMetersPerBlock,
				verticalMetersPerBlock
			)
		);
		addEffectiveClimateDebugText(text, latitude, longitude, equivalentAltitudeMeters, groundAltitudeMeters);
		addClimateDebugText(text, latitude, longitude);
	}

	@Override
	public int getMinimumY() {
		return this.minY;
	}

	@Override
	public int getWorldHeight() {
		return this.worldHeight;
	}

	@Override
	public int getSeaLevel() {
		return shiftedSeaLevel();
	}

	@Override
	public int getSpawnHeight(HeightLimitView world) {
		return shiftedSeaLevel() + 1;
	}

	private SurfaceColumn getSurfaceColumn(int blockX, int blockZ, int maxY) {
		SurfaceColumnCacheKey cacheKey = new SurfaceColumnCacheKey(blockX, blockZ, maxY);
		SurfaceColumn cached = this.surfaceColumnCache.get(cacheKey);
		if (cached != null) {
			return cached;
		}

		double latitude = EarthProjection.blockZToLatitude(blockZ, northSouthMetersPerBlock());
		double longitude = EarthProjection.blockXToLongitude(blockX, eastWestMetersPerBlock());
		double metersPerBlock = effectiveMetersPerBlock();
		double elevationMeters = TerrainTileService.sampleMeters(latitude, longitude);
		int terrainY = Math.max(this.minY, Math.min(maxY, (int)Math.round(elevationMeters / metersPerBlock) - 1 + WORLD_Y_SHIFT));
		EarthClimateService.EffectiveClimate climate = sampleEffectiveClimate(latitude, longitude, elevationMeters);
		double growth = climate != null ? climate.plantGrowthScore() : 0.0;
		LocalTerrainAnalysis localTerrain = sampleLocalTerrain(latitude, longitude);
		EarthHydroService.LakeSample lake = sampleLakeHydro(latitude, longitude);
		int adjustedTerrainY = isLakeTile(lake) ? Math.max(this.minY, terrainY - 1) : terrainY;
		Integer lakeWaterSurfaceY = lakeWaterSurfaceY(adjustedTerrainY, maxY, metersPerBlock, lake);
		BlockState lakeSurfaceBlock = lakeWaterSurfaceY != null && climate != null && climate.averageTemperature() <= -1.0 ? ICE : WATER;
		BlockState surfaceBlock = lakeWaterSurfaceY != null || adjustedTerrainY < shiftedSeaLevel()
			? SAND
			: pickSurfaceBlock(blockX, blockZ, latitude, climate, localTerrain);
		boolean supportsVegetation = lakeWaterSurfaceY == null && (surfaceBlock == GRASS_BLOCK || surfaceBlock == SNOW_BLOCK);
		SurfaceColumn computed = new SurfaceColumn(adjustedTerrainY, surfaceBlock, growth, supportsVegetation, lakeWaterSurfaceY, lakeSurfaceBlock);
		this.surfaceColumnCache.put(cacheKey, computed);
		return computed;
	}

	private SurfaceColumn[] buildChunkSurfaceColumns(int chunkStartX, int chunkStartZ, int maxY) {
		SurfaceColumn[] columns = new SurfaceColumn[16 * 16];
		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				columns[columnIndex(localX, localZ)] = getSurfaceColumn(chunkStartX + localX, chunkStartZ + localZ, maxY);
			}
		}
		return columns;
	}

	private int columnIndex(int localX, int localZ) {
		return localX * 16 + localZ;
	}

	private double effectiveMetersPerBlock() {
		double configured = EarthConfig.metersPerBlock();
		return configured > 0.0 ? configured : this.metersPerBlock;
	}

	private int shiftedSeaLevel() {
		return this.seaLevel + WORLD_Y_SHIFT;
	}

	private void addClimateDebugText(List<String> text, double latitude, double longitude) {
		double southLatitude = Math.floor(latitude);
		double northLatitude = Math.ceil(latitude);
		double westLongitude = Math.floor(longitude);
		double eastLongitude = Math.ceil(longitude);

		text.add("Earth climate corners (%d-%d):".formatted(CLIMATE_START_YEAR, CLIMATE_END_YEAR));
		text.add(formatClimateCorner("NW", northLatitude, westLongitude));
		text.add(formatClimateCorner("NE", northLatitude, eastLongitude));
		text.add(formatClimateCorner("SW", southLatitude, westLongitude));
		text.add(formatClimateCorner("SE", southLatitude, eastLongitude));
	}

	private void addEffectiveClimateDebugText(
		List<String> text,
		double latitude,
		double longitude,
		double altitudeMeters,
		double groundAltitudeMeters
	) {
		LocalTerrainAnalysis localTerrain = sampleLocalTerrain(latitude, longitude);
		EarthClimateService.EffectiveClimate climate = EarthClimateService.getEffectiveCachedOrFetchAsync(
			latitude,
			longitude,
			altitudeMeters,
			CLIMATE_START_YEAR,
			CLIMATE_END_YEAR
		);
		EarthClimateService.EffectiveClimate groundClimate = EarthClimateService.getEffectiveCachedOrFetchAsync(
			latitude,
			longitude,
			groundAltitudeMeters,
			CLIMATE_START_YEAR,
			CLIMATE_END_YEAR
		);
		if (groundClimate == null) {
			text.add("Earth climate point: loading...");
			return;
		}
		text.add(
			"Earth climate point: temp %.1f C | rain %.2f mm/day | growth %s | ref alt %.0f m".formatted(
				groundClimate.averageTemperature(),
				groundClimate.averageRainfall(),
				"%.2f".formatted(groundClimate.plantGrowthScore()),
				groundClimate.referenceAltitude()
			)
		);
		if (climate == null) {
			text.add("Earth climate terrain: loading...");
			text.add("Earth climate vectors: loading...");
			return;
		}
		text.add(
			"Earth climate terrain: localSlope %.3f (%.1f deg) | slopeForClimate %.3f (%.1f deg) | uphill aspect %.1f deg | windward %.2f | oro x%.2f".formatted(
				localTerrain.normalizedSlope(),
				localTerrain.slopeDegrees(),
				climate.slopeForClimate(),
				climate.slopeDegrees(),
				climate.aspectDegrees(),
				climate.windwardness(),
				climate.orographicMultiplier()
			)
		);
		text.add(
			"Earth climate vectors: moisture flow(E %.2f, N %.2f) | source %.1f deg | uphill(E %.2f, N %.2f)".formatted(
				climate.moistureVectorEast(),
				climate.moistureVectorNorth(),
				climate.moistureSourceDegrees(),
				climate.uphillVectorEast(),
				climate.uphillVectorNorth()
			)
		);
	}

	private String formatClimateCorner(String label, double latitude, double longitude) {
		EarthClimateService.ClimateSummary climate = EarthClimateService.getCachedOrFetchAsync(
			latitude,
			longitude,
			CLIMATE_START_YEAR,
			CLIMATE_END_YEAR
		);
		if (climate == null) {
			return "%s %.1f, %.1f | climate loading...".formatted(label, latitude, longitude);
		}
		return "%s %.1f, %.1f | temp %.1f C | rain %.2f mm/day | alt %.0f m".formatted(
			label,
			climate.latitude(),
			climate.longitude(),
			climate.averageTemperature(),
			climate.averageRainfall(),
			climate.altitude()
		);
	}

	private BlockState pickGroundBlock(int y, int surfaceY, BlockState surfaceBlock) {
		if (surfaceBlock == STONE) {
			return STONE;
		}
		if (surfaceBlock == SAND) {
			if (y == surfaceY) {
				return SAND;
			}
			return STONE;
		}
		if (surfaceBlock == SNOW_BLOCK) {
			if (y == surfaceY) {
				return SNOW_BLOCK;
			}
			return STONE;
		}
		if (y == surfaceY) {
			return surfaceBlock;
		}
		return STONE;
	}

	private EarthClimateService.EffectiveClimate sampleEffectiveClimate(double latitude, double longitude, double elevationMeters) {
		try {
			return EarthClimateService.sampleEffective(
				latitude,
				longitude,
				elevationMeters,
				CLIMATE_START_YEAR,
				CLIMATE_END_YEAR
			);
		} catch (Exception exception) {
			return null;
		}
	}

	private EarthHydroService.LakeSample sampleLakeHydro(double latitude, double longitude) {
		try {
			return EarthHydroService.sampleLakes(latitude, longitude);
		} catch (Exception exception) {
			return null;
		}
	}

	private Integer lakeWaterSurfaceY(int terrainY, int maxY, double metersPerBlock, EarthHydroService.LakeSample lake) {
		if (!isLakeTile(lake) || lake.lakeWaterLevelMeters() < 0.0) {
			return null;
		}

		int waterSurfaceY = Math.min(maxY, metersToWorldY(lake.lakeWaterLevelMeters(), metersPerBlock, maxY));
		return terrainY < waterSurfaceY ? waterSurfaceY : null;
	}

	private boolean isLakeTile(EarthHydroService.LakeSample lake) {
		return lake != null && lake.lake();
	}

	private int metersToWorldY(double elevationMeters, double metersPerBlock, int maxY) {
		int y = (int)Math.round(elevationMeters / metersPerBlock) - 1 + WORLD_Y_SHIFT;
		return Math.max(this.minY, Math.min(maxY, y));
	}

	private BlockState pickSurfaceBlock(
		int blockX,
		int blockZ,
		double latitude,
		EarthClimateService.EffectiveClimate climate,
		LocalTerrainAnalysis localTerrain
	) {
		if (isStonySurface(blockX, blockZ, localTerrain.slopeDegrees())) {
			return STONE;
		}
		if (climate != null && shouldSnowCover(blockX, blockZ, latitude, climate.averageTemperature(), localTerrain)) {
			return SNOW_BLOCK;
		}
		return GRASS_BLOCK;
	}

	private boolean isStonySurface(int blockX, int blockZ, double slopeDegrees) {
		double normalizedSlope = clamp(
			(slopeDegrees - STONE_SLOPE_START_DEGREES) / (STONE_SLOPE_FULL_DEGREES - STONE_SLOPE_START_DEGREES),
			0.0,
			1.0
		);
		double midpointFraction = (STONE_SLOPE_MIDPOINT_DEGREES - STONE_SLOPE_START_DEGREES)
			/ (STONE_SLOPE_FULL_DEGREES - STONE_SLOPE_START_DEGREES);
		double curveExponent = Math.log(0.5) / Math.log(midpointFraction);
		double baseRockProbability = Math.pow(normalizedSlope, curveExponent);
		double noise = signedNoise(blockX, blockZ, 37, 18.0) * STONE_PROBABILITY_NOISE_AMPLITUDE;
		double rockProbability = clamp(baseRockProbability + noise, 0.0, 1.0);
		double rockPatchNoise = valueNoise2d(blockX / STONE_PATCH_SCALE_BLOCKS, blockZ / STONE_PATCH_SCALE_BLOCKS, 53);
		return rockPatchNoise < rockProbability;
	}

	private boolean shouldSnowCover(
		int blockX,
		int blockZ,
		double latitude,
		double averageTemperature,
		LocalTerrainAnalysis localTerrain
	) {
		double slopeNoise = signedNoise(blockX, blockZ, 71, 22.0);
		double snowSlopeLimit = SNOW_SLOPE_THRESHOLD + slopeNoise * SNOW_SLOPE_NOISE_AMPLITUDE;
		if (localTerrain.normalizedSlope() > snowSlopeLimit) {
			return false;
		}

		double poleFacingness = poleFacingness(latitude, localTerrain.downhillAspectDegrees());
		double snowStartTemperature = SNOW_BASE_START_C + SNOW_ASPECT_AMPLITUDE_C * poleFacingness;
		double tempNoise = signedNoise(blockX, blockZ, 113, 28.0) * SNOW_TEMP_NOISE_AMPLITUDE_C;
		return averageTemperature <= snowStartTemperature + tempNoise;
	}

	private LocalTerrainAnalysis sampleLocalTerrain(double latitude, double longitude) {
		double latitudeRadians = Math.toRadians(latitude);
		double latitudeDelta = Math.toDegrees(LOCAL_SLOPE_SAMPLE_DISTANCE_METERS / 6_378_137.0);
		double eastWestMetersPerDegree = Math.cos(latitudeRadians) * 6_378_137.0 * Math.PI / 180.0;
		double longitudeDelta = eastWestMetersPerDegree > 1.0
			? LOCAL_SLOPE_SAMPLE_DISTANCE_METERS / eastWestMetersPerDegree
			: latitudeDelta;

		double north = TerrainTileService.sampleMeters(latitude + latitudeDelta, longitude);
		double south = TerrainTileService.sampleMeters(latitude - latitudeDelta, longitude);
		double east = TerrainTileService.sampleMeters(latitude, longitude + longitudeDelta);
		double west = TerrainTileService.sampleMeters(latitude, longitude - longitudeDelta);

		double dzdx = (east - west) / (2.0 * LOCAL_SLOPE_SAMPLE_DISTANCE_METERS);
		double dzdy = (north - south) / (2.0 * LOCAL_SLOPE_SAMPLE_DISTANCE_METERS);
		double slopeGrade = Math.hypot(dzdx, dzdy);
		double slopeDegrees = slopeDegrees(slopeGrade);
		double normalizedSlope = normalizedSlopeFromDegrees(slopeDegrees);
		if (slopeGrade < 1.0e-9) {
			return new LocalTerrainAnalysis(normalizedSlope, slopeDegrees, Double.NaN);
		}
		double downhillAspectDegrees = normalizeDegrees(Math.toDegrees(Math.atan2(-dzdx, -dzdy)));
		return new LocalTerrainAnalysis(normalizedSlope, slopeDegrees, downhillAspectDegrees);
	}

	private double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private double slopeDegrees(double slopeGrade) {
		return Math.toDegrees(Math.atan(slopeGrade));
	}

	private double normalizedSlopeFromDegrees(double slopeDegrees) {
		return clamp(slopeDegrees / 90.0, 0.0, 1.0);
	}

	private double normalizeDegrees(double degrees) {
		double normalized = degrees % 360.0;
		return normalized < 0.0 ? normalized + 360.0 : normalized;
	}

	private double poleFacingness(double latitude, double downhillAspectDegrees) {
		if (Double.isNaN(downhillAspectDegrees) || Math.abs(latitude) < 1.0e-6) {
			return 0.0;
		}
		double northFacingness = Math.cos(Math.toRadians(downhillAspectDegrees));
		double hemisphereSign = Math.signum(latitude);
		double latitudeWeight = Math.sin(Math.toRadians(Math.abs(latitude)));
		return clamp(northFacingness * hemisphereSign * latitudeWeight, -1.0, 1.0);
	}

	private void placeVegetation(
		StructureWorldAccess world,
		BlockPos.Mutable plantPos,
		int surfaceY,
		BlockState surfaceBlock,
		double growth,
		int worldX,
		int worldZ
	) {
		boolean snowySurface = surfaceBlock == SNOW_BLOCK;
		double coverNoise = hashToUnitDouble(worldX, worldZ, 11);
		double treeNoise = hashToUnitDouble(worldX, worldZ, 29);
		double flowerNoise = hashToUnitDouble(worldX, worldZ, 47);
		double undergrowthNoise = hashToUnitDouble(worldX, worldZ, 59);

		if (growth < 0.18) {
			return;
		}

		double treeProbability = treeProbabilityForGrowth(growth);

		if (growth < 0.32) {
			if (!snowySurface && coverNoise < 0.14) {
				world.setBlockState(plantPos, SHORT_GRASS, VEGETATION_PLACE_FLAGS);
			} else if (!snowySurface && coverNoise < 0.18) {
				world.setBlockState(plantPos, pickFlower(flowerNoise), VEGETATION_PLACE_FLAGS);
			}
			return;
		}

		if (growth < 0.48) {
			if (treeNoise < treeProbability && placeTree(
				world,
					plantPos.toImmutable(),
					4,
					Blocks.ACACIA_LOG.getDefaultState(),
					ACACIA_LEAVES,
					false
				)) {
				return;
			}
			if (!snowySurface && coverNoise < 0.22) {
				world.setBlockState(plantPos, SHORT_GRASS, VEGETATION_PLACE_FLAGS);
			} else if (!snowySurface && coverNoise < 0.26) {
				world.setBlockState(plantPos, pickFlower(flowerNoise), VEGETATION_PLACE_FLAGS);
			} else if (!snowySurface && coverNoise < 0.28) {
				world.setBlockState(plantPos, FERN, VEGETATION_PLACE_FLAGS);
			}
			return;
		}

		if (growth < 0.68) {
			if (treeNoise < treeProbability && placeTree(
				world,
					plantPos.toImmutable(),
					5,
					Blocks.OAK_LOG.getDefaultState(),
					OAK_LEAVES,
					false
				)) {
				return;
			}
			if (!snowySurface && growth >= UNDERGROWTH_START_GROWTH && placeUndergrowth(
				world,
				plantPos,
				growth,
				coverNoise,
				flowerNoise,
				undergrowthNoise
			)) {
				return;
			}
			if (!snowySurface && coverNoise < 0.30) {
				world.setBlockState(plantPos, SHORT_GRASS, VEGETATION_PLACE_FLAGS);
			} else if (!snowySurface && coverNoise < 0.36) {
				world.setBlockState(plantPos, pickFlower(flowerNoise), VEGETATION_PLACE_FLAGS);
			} else if (!snowySurface && coverNoise < 0.42) {
				world.setBlockState(plantPos, FERN, VEGETATION_PLACE_FLAGS);
			}
			return;
		}

		if (treeNoise < treeProbability && placeTree(
			world,
				plantPos.toImmutable(),
				6,
				Blocks.JUNGLE_LOG.getDefaultState(),
				JUNGLE_LEAVES,
				true
			)) {
			return;
		}
		if (!snowySurface && placeUndergrowth(
			world,
			plantPos,
			growth,
			coverNoise,
			flowerNoise,
			undergrowthNoise
		)) {
			return;
		}
		if (!snowySurface && coverNoise < 0.36) {
			world.setBlockState(plantPos, SHORT_GRASS, VEGETATION_PLACE_FLAGS);
		} else if (!snowySurface && coverNoise < 0.44) {
			world.setBlockState(plantPos, pickFlower(flowerNoise), VEGETATION_PLACE_FLAGS);
		} else if (!snowySurface && coverNoise < 0.66) {
			world.setBlockState(plantPos, FERN, VEGETATION_PLACE_FLAGS);
		}
	}

	private double treeProbabilityForGrowth(double growth) {
		if (growth < 0.32) {
			return 0.0;
		}
		if (growth < 0.48) {
			return lerp(0.01, 0.04, (growth - 0.32) / 0.16);
		}
		if (growth < 0.70) {
			return lerp(0.04, 0.20, (growth - 0.48) / 0.22);
		}
		return lerp(0.20, 0.32, clamp((growth - 0.70) / 0.30, 0.0, 1.0));
	}

	private boolean placeUndergrowth(
		StructureWorldAccess world,
		BlockPos.Mutable plantPos,
		double growth,
		double coverNoise,
		double flowerNoise,
		double undergrowthNoise
	) {
		double density = undergrowthDensityForGrowth(growth);
		if (density <= 0.0) {
			return false;
		}

		double undergrowthChance = lerp(0.22, 0.72, density);
		if (coverNoise >= undergrowthChance) {
			return false;
		}

		if (undergrowthNoise < lerp(0.25, 0.12, density)) {
			world.setBlockState(plantPos, pickFlower(flowerNoise), VEGETATION_PLACE_FLAGS);
			return true;
		}
		if (undergrowthNoise < lerp(0.70, 0.30, density)) {
			world.setBlockState(plantPos, SHORT_GRASS, VEGETATION_PLACE_FLAGS);
			return true;
		}

		world.setBlockState(plantPos, FERN, VEGETATION_PLACE_FLAGS);
		return true;
	}

	private double undergrowthDensityForGrowth(double growth) {
		if (growth <= UNDERGROWTH_START_GROWTH) {
			return 0.0;
		}
		if (growth >= UNDERGROWTH_FULL_GROWTH) {
			return 1.0;
		}
		return clamp(
			(growth - UNDERGROWTH_START_GROWTH) / (UNDERGROWTH_FULL_GROWTH - UNDERGROWTH_START_GROWTH),
			0.0,
			1.0
		);
	}

	private boolean placeTree(
		StructureWorldAccess world,
		BlockPos basePos,
		int trunkHeight,
		BlockState logState,
		BlockState leafState,
		boolean addVines
	) {
		int canopyBaseY = basePos.getY() + trunkHeight - 2;
		int canopyTopY = basePos.getY() + trunkHeight + 1;
		for (int y = basePos.getY(); y <= canopyTopY; y++) {
			if (y >= this.minY + this.worldHeight) {
				return false;
			}
			if (y < canopyBaseY) {
				if (!world.getBlockState(new BlockPos(basePos.getX(), y, basePos.getZ())).isAir()) {
					return false;
				}
				continue;
			}
			int radius = y == canopyTopY ? 1 : 2;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					BlockPos leafPos = new BlockPos(basePos.getX() + dx, y, basePos.getZ() + dz);
					if (!world.getBlockState(leafPos).isAir() && !world.getBlockState(leafPos).isOf(Blocks.VINE)) {
						return false;
					}
				}
			}
		}

		for (int offset = 0; offset < trunkHeight; offset++) {
			world.setBlockState(basePos.up(offset), logState, VEGETATION_PLACE_FLAGS);
		}
		for (int y = canopyBaseY; y <= canopyTopY; y++) {
			int radius = y == canopyTopY ? 1 : 2;
			if (y == canopyBaseY) {
				radius = 1;
			}
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.abs(dx) == radius && Math.abs(dz) == radius && y != canopyTopY) {
						continue;
					}
					BlockPos leafPos = new BlockPos(basePos.getX() + dx, y, basePos.getZ() + dz);
					if (world.getBlockState(leafPos).isAir()) {
						world.setBlockState(leafPos, leafState, VEGETATION_PLACE_FLAGS);
						if (addVines && y < canopyTopY && Math.abs(dx) + Math.abs(dz) == radius) {
							placeVineCurtain(world, leafPos, dx, dz);
						}
					}
				}
			}
		}
		return true;
	}

	private void placeVineCurtain(StructureWorldAccess world, BlockPos anchorPos, int dx, int dz) {
		BlockState vineState = Blocks.VINE.getDefaultState();
		if (Math.abs(dx) >= Math.abs(dz)) {
			vineState = vineState.with(dx > 0 ? VineBlock.EAST : VineBlock.WEST, true);
		} else {
			vineState = vineState.with(dz > 0 ? VineBlock.SOUTH : VineBlock.NORTH, true);
		}
		for (int drop = 1; drop <= 3; drop++) {
			BlockPos vinePos = anchorPos.down(drop);
			if (!world.getBlockState(vinePos).isAir()) {
				break;
			}
			world.setBlockState(vinePos, vineState, VEGETATION_PLACE_FLAGS);
		}
	}

	private double hashToUnitDouble(int x, int z, int salt) {
		long hash = 0x9E3779B97F4A7C15L ^ ((long)x * 0x632BE59BD9B4E019L) ^ ((long)z * 0x85157AF5L) ^ salt;
		hash ^= hash >>> 33;
		hash *= 0xff51afd7ed558ccdL;
		hash ^= hash >>> 33;
		hash *= 0xc4ceb9fe1a85ec53L;
		hash ^= hash >>> 33;
		return (hash & 0x1FFFFFFFFFFFFFL) / (double)0x20000000000000L;
	}

	private double signedNoise(int worldX, int worldZ, int salt, double scaleBlocks) {
		return valueNoise2d(worldX / scaleBlocks, worldZ / scaleBlocks, salt) * 2.0 - 1.0;
	}

	private double valueNoise2d(double x, double z, int salt) {
		int x0 = fastFloor(x);
		int z0 = fastFloor(z);
		int x1 = x0 + 1;
		int z1 = z0 + 1;
		double xFraction = x - x0;
		double zFraction = z - z0;
		double sx = smoothstep(xFraction);
		double sz = smoothstep(zFraction);

		double south = lerp(hashToUnitDouble(x0, z0, salt), hashToUnitDouble(x1, z0, salt), sx);
		double north = lerp(hashToUnitDouble(x0, z1, salt), hashToUnitDouble(x1, z1, salt), sx);
		return lerp(south, north, sz);
	}

	private int fastFloor(double value) {
		int truncated = (int)value;
		return value < truncated ? truncated - 1 : truncated;
	}

	private double smoothstep(double value) {
		double clamped = clamp(value, 0.0, 1.0);
		return clamped * clamped * (3.0 - 2.0 * clamped);
	}

	private BlockState pickFlower(double flowerNoise) {
		if (flowerNoise < 0.25) {
			return DANDELION;
		}
		if (flowerNoise < 0.5) {
			return POPPY;
		}
		if (flowerNoise < 0.75) {
			return AZURE_BLUET;
		}
		return OXEYE_DAISY;
	}

	private double lerp(double start, double end, double delta) {
		return start + (end - start) * clamp(delta, 0.0, 1.0);
	}

	public static int climateStartYear() {
		return CLIMATE_START_YEAR;
	}

	public static int climateEndYear() {
		return CLIMATE_END_YEAR;
	}

	public static int worldYShift() {
		return WORLD_Y_SHIFT;
	}

	private record SurfaceColumn(
		int surfaceY,
		BlockState surfaceBlock,
		double growth,
		boolean supportsVegetation,
		Integer waterSurfaceY,
		BlockState lakeSurfaceBlock
	) {
	}

	private record SurfaceColumnCacheKey(int blockX, int blockZ, int maxY) {
	}

	private record LocalTerrainAnalysis(double normalizedSlope, double slopeDegrees, double downhillAspectDegrees) {
	}
}
