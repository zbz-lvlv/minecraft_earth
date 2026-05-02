package dev.mearth.earth.client;

import dev.mearth.earth.EarthMod;
import dev.mearth.earth.climate.EarthClimateService;
import dev.mearth.earth.config.EarthConfig;
import dev.mearth.earth.geo.EarthProjection;
import dev.mearth.earth.terrain.TerrainTileService;
import dev.mearth.earth.world.EarthChunkGenerator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

import java.util.concurrent.ConcurrentHashMap;

public final class EarthModClient implements ClientModInitializer {
	private static final double DRY_GROWTH_THRESHOLD = 0.18;
	private static final double MIN_COLOR_GROWTH_THRESHOLD = 0.48;
	private static final double LUSH_GROWTH_THRESHOLD = 0.68;
	private static final int DRY_GRASS_COLOR = 0xfae8c0;
	private static final int LUSH_GRASS_COLOR = 0x3f822f;
	private static final int DEFAULT_GRASS_COLOR = 0x91BD59;
	private static final ConcurrentHashMap<Long, Integer> GRASS_COLOR_CACHE = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Long, Integer> LEAF_COLOR_CACHE = new ConcurrentHashMap<>();

	@Override
	public void onInitializeClient() {
		ColorProviderRegistry.BLOCK.register(
			this::colorGrassByClimate,
			Blocks.GRASS_BLOCK,
			Blocks.SHORT_GRASS,
			Blocks.FERN,
			Blocks.TALL_GRASS,
			Blocks.LARGE_FERN
		);
		ColorProviderRegistry.BLOCK.register(
			this::colorLeavesByClimate,
			Blocks.OAK_LEAVES,
			Blocks.ACACIA_LEAVES,
			Blocks.JUNGLE_LEAVES,
			Blocks.VINE
		);
	}

	private int colorGrassByClimate(BlockState state, BlockRenderView view, BlockPos pos, int tintIndex) {
		if (tintIndex != 0 || pos == null) {
			return DEFAULT_GRASS_COLOR;
		}

		ClientWorld world = MinecraftClient.getInstance().world;
		if (world == null || !isEarthWorld(world)) {
			return DEFAULT_GRASS_COLOR;
		}

		return GRASS_COLOR_CACHE.computeIfAbsent(colorCacheKey(pos), ignored -> loadGroundClimateColor(pos.getX(), pos.getZ(), false));
	}

	private int colorLeavesByClimate(BlockState state, BlockRenderView view, BlockPos pos, int tintIndex) {
		if (tintIndex != 0 || pos == null) {
			return DEFAULT_GRASS_COLOR;
		}

		ClientWorld world = MinecraftClient.getInstance().world;
		if (world == null || !isEarthWorld(world)) {
			return DEFAULT_GRASS_COLOR;
		}

		return LEAF_COLOR_CACHE.computeIfAbsent(colorCacheKey(pos), ignored -> loadGroundClimateColor(pos.getX(), pos.getZ(), true));
	}

	private int loadGroundClimateColor(int blockX, int blockZ, boolean leafTint) {
		try {
			double eastWestMetersPerBlock = EarthConfig.metersPerBlock() * 30.0 / 25.0;
			double northSouthMetersPerBlock = EarthConfig.metersPerBlock();
			double latitude = EarthProjection.blockZToLatitude(blockZ, northSouthMetersPerBlock);
			double longitude = EarthProjection.blockXToLongitude(blockX, eastWestMetersPerBlock);
			double altitudeMeters = TerrainTileService.sampleMeters(latitude, longitude);
			EarthClimateService.EffectiveClimate climate = EarthClimateService.sampleEffective(
				latitude,
				longitude,
				altitudeMeters,
				EarthChunkGenerator.climateStartYear(),
				EarthChunkGenerator.climateEndYear()
			);
			double tintGrowth = EarthClimateService.colorTintGrowthScore(
				climate.averageTemperature(),
				climate.averageRainfall()
			);
			return leafTint ? colorForLeafGrowth(tintGrowth) : colorForGrassGrowth(tintGrowth);
		} catch (Exception ignored) {
			return DEFAULT_GRASS_COLOR;
		}
	}

	private int colorForGrassGrowth(double growth) {
		double clampedGrowth = clamp(growth, DRY_GROWTH_THRESHOLD, LUSH_GROWTH_THRESHOLD);
		if (clampedGrowth <= DRY_GROWTH_THRESHOLD) {
			return DRY_GRASS_COLOR;
		}
		if (clampedGrowth >= LUSH_GROWTH_THRESHOLD) {
			return LUSH_GRASS_COLOR;
		}
		double gradientDelta = (clampedGrowth - DRY_GROWTH_THRESHOLD)
			/ (LUSH_GROWTH_THRESHOLD - DRY_GROWTH_THRESHOLD);
		return lerpColor(DRY_GRASS_COLOR, LUSH_GRASS_COLOR, gradientDelta);
	}

	private int colorForLeafGrowth(double growth) {
		double clampedGrowth = clamp(growth, MIN_COLOR_GROWTH_THRESHOLD, LUSH_GROWTH_THRESHOLD);
		if (clampedGrowth >= LUSH_GROWTH_THRESHOLD) {
			return LUSH_GRASS_COLOR;
		}
		double gradientDelta = (clampedGrowth - DRY_GROWTH_THRESHOLD)
			/ (LUSH_GROWTH_THRESHOLD - DRY_GROWTH_THRESHOLD);
		return lerpColor(DRY_GRASS_COLOR, LUSH_GRASS_COLOR, gradientDelta);
	}

	private double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private int lerpColor(int startColor, int endColor, double delta) {
		int startRed = (startColor >> 16) & 0xFF;
		int startGreen = (startColor >> 8) & 0xFF;
		int startBlue = startColor & 0xFF;
		int endRed = (endColor >> 16) & 0xFF;
		int endGreen = (endColor >> 8) & 0xFF;
		int endBlue = endColor & 0xFF;

		int red = (int)Math.round(startRed + (endRed - startRed) * delta);
		int green = (int)Math.round(startGreen + (endGreen - startGreen) * delta);
		int blue = (int)Math.round(startBlue + (endBlue - startBlue) * delta);
		return (red << 16) | (green << 8) | blue;
	}

	private boolean isEarthWorld(ClientWorld world) {
		return EarthMod.id("earth_overworld").equals(world.getDimensionEntry().getKey().map(key -> key.getValue()).orElse(null))
			|| (world.getBottomY() == -512 && world.getHeight() == 1024);
	}

	private long colorCacheKey(BlockPos pos) {
		return BlockPos.asLong(pos.getX(), 0, pos.getZ());
	}
}
