package dev.mearth.earth.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
	public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		BiomeSource.CODEC.fieldOf("biome_source").forGetter(generator -> generator.biomeSource),
		Codec.DOUBLE.optionalFieldOf("meters_per_block", 25.0).forGetter(EarthChunkGenerator::metersPerBlock),
		Codec.INT.optionalFieldOf("min_y", -512).forGetter(EarthChunkGenerator::getMinimumY),
		Codec.INT.optionalFieldOf("world_height", 1024).forGetter(EarthChunkGenerator::getWorldHeight),
		Codec.INT.optionalFieldOf("sea_level", 0).forGetter(EarthChunkGenerator::getSeaLevel)
	).apply(instance, EarthChunkGenerator::new));

	private static final BlockState AIR = Blocks.AIR.getDefaultState();
	private static final BlockState STONE = Blocks.STONE.getDefaultState();
	private static final BlockState DIRT = Blocks.DIRT.getDefaultState();
	private static final BlockState GRASS = Blocks.GRASS_BLOCK.getDefaultState();
	private static final BlockState SAND = Blocks.SAND.getDefaultState();
	private static final BlockState WATER = Blocks.WATER.getDefaultState();
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
				int surfaceY = getSurfaceY(worldX, worldZ, maxY);

				for (int y = this.minY; y <= surfaceY; y++) {
					mutable.set(worldX, y, worldZ);
					chunk.setBlockState(mutable, pickGroundBlock(y, surfaceY), false);
				}

				if (surfaceY < this.seaLevel) {
					for (int y = surfaceY + 1; y <= this.seaLevel && y <= maxY; y++) {
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
		return getSurfaceY(x, z, this.minY + this.worldHeight - 1) + 1;
	}

	@Override
	public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
		BlockState[] states = new BlockState[this.worldHeight];
		int surfaceY = getSurfaceY(x, z, this.minY + this.worldHeight - 1);

		for (int index = 0; index < states.length; index++) {
			int y = this.minY + index;
			if (y <= surfaceY) {
				states[index] = pickGroundBlock(y, surfaceY);
			} else if (y <= this.seaLevel) {
				states[index] = WATER;
			} else {
				states[index] = AIR;
			}
		}

		return new VerticalBlockSample(this.minY, states);
	}

	@Override
	public void getDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
		double latitude = EarthProjection.blockZToLatitude(pos.getZ(), effectiveMetersPerBlock());
		double longitude = EarthProjection.blockXToLongitude(pos.getX(), effectiveMetersPerBlock());
		text.add("Earth lat/lon: %.5f, %.5f".formatted(latitude, longitude));
		text.add("Earth scale: 1 block = %.1f m".formatted(effectiveMetersPerBlock()));
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
		return this.seaLevel;
	}

	@Override
	public int getSpawnHeight(HeightLimitView world) {
		return this.seaLevel + 1;
	}

	private int getSurfaceY(int blockX, int blockZ, int maxY) {
		double metersPerBlock = effectiveMetersPerBlock();
		double latitude = EarthProjection.blockZToLatitude(blockZ, metersPerBlock);
		double longitude = EarthProjection.blockXToLongitude(blockX, metersPerBlock);
		double elevationMeters = TerrainTileService.sampleMeters(latitude, longitude);
		int y = (int)Math.round(elevationMeters / metersPerBlock) - 1;
		return Math.max(this.minY, Math.min(maxY, y));
	}

	private double effectiveMetersPerBlock() {
		double configured = EarthConfig.metersPerBlock();
		return configured > 0.0 ? configured : this.metersPerBlock;
	}

	private BlockState pickGroundBlock(int y, int surfaceY) {
		if (surfaceY <= this.seaLevel && y >= surfaceY - 3) {
			return SAND;
		}
		if (y == surfaceY) {
			return surfaceY > this.seaLevel ? GRASS : SAND;
		}
		if (y >= surfaceY - 3) {
			return DIRT;
		}
		return STONE;
	}
}
