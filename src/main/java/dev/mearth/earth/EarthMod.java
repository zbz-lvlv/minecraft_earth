package dev.mearth.earth;

import dev.mearth.earth.command.GeoTpCommand;
import dev.mearth.earth.config.EarthConfig;
import dev.mearth.earth.world.EarthChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EarthMod implements ModInitializer {
	public static final String MOD_ID = "earthmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		EarthConfig.load();
		Registry.register(Registries.CHUNK_GENERATOR, id("earth"), EarthChunkGenerator.CODEC);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> GeoTpCommand.register(dispatcher));
		LOGGER.info("Earth mod initialized");
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}
}
