package dev.mearth.earth.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.mearth.earth.geo.EarthProjection;
import dev.mearth.earth.geo.GeoResult;
import dev.mearth.earth.geo.GeocodingService;
import dev.mearth.earth.world.EarthChunkGenerator;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.concurrent.CompletableFuture;

public final class GeoTpCommand {
	private static final int TELEPORT_Y = 320;

	private GeoTpCommand() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("geotp")
			.requires(source -> source.hasPermissionLevel(2))
			.then(CommandManager.argument("query", StringArgumentType.greedyString())
				.executes(context -> execute(context, StringArgumentType.getString(context, "query")))));
	}

	private static int execute(CommandContext<ServerCommandSource> context, String query) {
		ServerCommandSource source = context.getSource();
		ServerPlayerEntity player = source.getPlayer();
		ServerWorld world = source.getServer().getWorld(World.OVERWORLD);
		if (player == null || world == null) {
			source.sendError(Text.literal("This command must be run by a player in a server with an overworld."));
			return 0;
		}
		if (!(world.getChunkManager().getChunkGenerator() instanceof EarthChunkGenerator generator)) {
			source.sendError(Text.literal("The overworld is not using the Earth world preset."));
			return 0;
		}

		ParsedLocation parsed = ParsedLocation.tryParse(query);
		CompletableFuture
			.supplyAsync(() -> resolve(query, parsed))
			.whenComplete((result, throwable) -> world.getServer().execute(() -> {
				if (throwable != null) {
					String message = throwable.getCause() != null ? throwable.getCause().getMessage() : throwable.getMessage();
					source.sendError(Text.literal("Failed to resolve location: " + message));
					return;
				}

				double x = EarthProjection.longitudeToBlockX(result.longitude(), generator.eastWestMetersPerBlock());
				double z = EarthProjection.latitudeToBlockZ(result.latitude(), generator.northSouthMetersPerBlock());
				int blockX = MathHelper.floor(x);
				int blockZ = MathHelper.floor(z);
				int y = TELEPORT_Y;

				player.teleport(world, x + 0.5, y, z + 0.5, player.getYaw(), player.getPitch());
				source.sendFeedback(() -> Text.literal("Teleported to " + result.label() + " at %.5f, %.5f -> %d, %d".formatted(
					result.latitude(),
					result.longitude(),
					blockX,
					blockZ
				)), false);
			}));

		source.sendFeedback(() -> Text.literal("Resolving '" + query + "'..."), false);
		return 1;
	}

	private static GeoResult resolve(String query, ParsedLocation parsed) {
		try {
			if (parsed != null) {
				return new GeoResult(query, parsed.latitude(), parsed.longitude());
			}
			return GeocodingService.geocode(query);
		} catch (Exception exception) {
			throw new RuntimeException(exception);
		}
	}

	private record ParsedLocation(double latitude, double longitude) {
		private static ParsedLocation tryParse(String input) {
			String normalized = input.trim().replace(",", " ");
			String[] parts = normalized.split("\\s+");
			if (parts.length != 2) {
				return null;
			}

			try {
				double latitude = Double.parseDouble(parts[0]);
				double longitude = Double.parseDouble(parts[1]);
				if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
					return null;
				}
				return new ParsedLocation(latitude, longitude);
			} catch (NumberFormatException exception) {
				return null;
			}
		}
	}
}
