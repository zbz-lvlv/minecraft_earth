package dev.mearth.earth.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.mearth.earth.climate.EarthClimateService;
import dev.mearth.earth.config.EarthConfig;
import dev.mearth.earth.geo.EarthProjection;
import dev.mearth.earth.terrain.TerrainTileService;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.math.BlockPos;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class EarthChunkGenerator extends ChunkGenerator {
	private static final int CLIMATE_START_YEAR = 2010;
	private static final int CLIMATE_END_YEAR = 2020;
	private static final int WORLD_Y_SHIFT = -40;
	public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
		Codec.DOUBLE.optionalFieldOf("meters_per_block", 25.0).forGetter(EarthChunkGenerator::metersPerBlock),
		Codec.INT.optionalFieldOf("min_y", -512).forGetter(EarthChunkGenerator::getMinimumY),
		Codec.INT.optionalFieldOf("world_height", 1024).forGetter(EarthChunkGenerator::getWorldHeight),
		Codec.INT.optionalFieldOf("sea_level", 0).forGetter(EarthChunkGenerator::getSeaLevel)
	).apply(instance, EarthChunkGenerator::new));

	private static final BlockState AIR = Blocks.AIR.getDefaultState();
	private static final BlockState STONE = Blocks.STONE.getDefaultState();
	private static final BlockState WATER = Blocks.WATER.getDefaultState();
	private static final BlockState[] PLANT_GROWTH_SURFACE_BLOCKS = new BlockState[] {
		Blocks.RED_CONCRETE.getDefaultState(),
		Blocks.ORANGE_CONCRETE.getDefaultState(),
		Blocks.YELLOW_CONCRETE.getDefaultState(),
		Blocks.LIME_CONCRETE.getDefaultState(),
		Blocks.GREEN_CONCRETE.getDefaultState(),
		Blocks.CYAN_CONCRETE.getDefaultState(),
		Blocks.LIGHT_BLUE_CONCRETE.getDefaultState(),
		Blocks.BLUE_CONCRETE.getDefaultState(),
		Blocks.MAGENTA_CONCRETE.getDefaultState(),
		Blocks.PINK_CONCRETE.getDefaultState()
	};
	private final double metersPerBlock;
	private final int minY;
	private final int worldHeight;
	private final int seaLevel;

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
		// Intentionally empty: the Earth preset uses imported terrain only.
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

		for (int localX = 0; localX < 16; localX++) {
			for (int localZ = 0; localZ < 16; localZ++) {
				int worldX = chunkStartX + localX;
				int worldZ = chunkStartZ + localZ;
				SurfaceColumn surface = getSurfaceColumn(worldX, worldZ, maxY);
				int surfaceY = surface.surfaceY();

				for (int y = this.minY; y <= surfaceY; y++) {
					mutable.set(worldX, y, worldZ);
					chunk.setBlockState(mutable, pickGroundBlock(y, surfaceY, surface.surfaceBlock()), false);
				}

				if (surfaceY < shiftedSeaLevel()) {
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
		return getSurfaceColumn(x, z, this.minY + this.worldHeight - 1).surfaceY() + 1;
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
		double latitude = EarthProjection.blockZToLatitude(blockZ, northSouthMetersPerBlock());
		double longitude = EarthProjection.blockXToLongitude(blockX, eastWestMetersPerBlock());
		double metersPerBlock = effectiveMetersPerBlock();
		double elevationMeters = TerrainTileService.sampleMeters(latitude, longitude);
		int y = (int)Math.round(elevationMeters / metersPerBlock) - 1 + WORLD_Y_SHIFT;
		int surfaceY = Math.max(this.minY, Math.min(maxY, y));
		BlockState surfaceBlock = pickRainfallSurfaceBlock(latitude, longitude, elevationMeters);
		return new SurfaceColumn(surfaceY, surfaceBlock);
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
			"Earth climate terrain: slopeForClimate %.3f (%.1f deg) | uphill aspect %.1f deg | windward %.2f | oro x%.2f".formatted(
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
		if (y >= surfaceY - 2) {
			return surfaceBlock;
		}
		return STONE;
	}

	private BlockState pickRainfallSurfaceBlock(double latitude, double longitude, double elevationMeters) {
		try {
			EarthClimateService.EffectiveClimate climate = EarthClimateService.sampleEffective(
				latitude,
				longitude,
				elevationMeters,
				CLIMATE_START_YEAR,
				CLIMATE_END_YEAR
			);
			double normalized = climate.plantGrowthScore();
			int index = Math.min(
				PLANT_GROWTH_SURFACE_BLOCKS.length - 1,
				(int)Math.round(normalized * (PLANT_GROWTH_SURFACE_BLOCKS.length - 1))
			);
			return PLANT_GROWTH_SURFACE_BLOCKS[index];
		} catch (Exception exception) {
			return Blocks.GRAY_CONCRETE.getDefaultState();
		}
	}

	private double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private record SurfaceColumn(int surfaceY, BlockState surfaceBlock) {
	}
}
