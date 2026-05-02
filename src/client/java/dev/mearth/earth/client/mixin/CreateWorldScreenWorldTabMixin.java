package dev.mearth.earth.client.mixin;

import dev.mearth.earth.world.EarthChunkGenerator;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.gui.tab.GridScreenTab;
import net.minecraft.text.Text;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Mixin(targets = "net.minecraft.client.gui.screen.world.CreateWorldScreen$WorldTab")
abstract class CreateWorldScreenWorldTabMixin extends GridScreenTab {
	@Shadow(aliases = "field_42182")
	@Final
	private CreateWorldScreen field_42182;

	@Unique
	private static final Text EARTHMOD_GENERATE_RIVERS_TEXT = Text.literal("Generate rivers (experimental)");

	@Unique
	private boolean earthmod$generateRivers = true;

	@Unique
	private boolean earthmod$updatingRivers;

	private CreateWorldScreenWorldTabMixin(Text title) {
		super(title);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void earthmod$addGenerateRiversToggle(CreateWorldScreen screen, CallbackInfo ci) {
		WorldCreator worldCreator = ((CreateWorldScreenAccessor)this.field_42182).earthmod$getWorldCreator();
		this.earthmod$generateRivers = earthmod$currentGenerateRivers(worldCreator);
		Object optionGrid = earthmod$buildGenerateRiversOptionGrid(worldCreator);
		worldCreator.addListener(creator -> {
			if (!this.earthmod$updatingRivers && earthmod$isEarthWorld(creator) && earthmod$currentGenerateRivers(creator) != this.earthmod$generateRivers) {
				this.earthmod$applyGenerateRivers(creator, this.earthmod$generateRivers);
			}
			earthmod$refreshOptionGrid(optionGrid);
		});
	}

	@Unique
	private void earthmod$setGenerateRivers(WorldCreator worldCreator, boolean generateRivers) {
		this.earthmod$generateRivers = generateRivers;
		if (earthmod$isEarthWorld(worldCreator)) {
			this.earthmod$applyGenerateRivers(worldCreator, generateRivers);
		}
	}

	@Unique
	private void earthmod$applyGenerateRivers(WorldCreator worldCreator, boolean generateRivers) {
		this.earthmod$updatingRivers = true;
		try {
			worldCreator.applyModifier((registries, selectedDimensions) -> earthmod$withGenerateRivers(registries, selectedDimensions, generateRivers));
		} finally {
			this.earthmod$updatingRivers = false;
		}
	}

	@Unique
	private static DimensionOptionsRegistryHolder earthmod$withGenerateRivers(
		net.minecraft.registry.DynamicRegistryManager.Immutable registries,
		DimensionOptionsRegistryHolder selectedDimensions,
		boolean generateRivers
	) {
		ChunkGenerator generator = selectedDimensions.getChunkGenerator();
		if (!(generator instanceof EarthChunkGenerator earthGenerator)) {
			return selectedDimensions;
		}
		return selectedDimensions.with(registries, earthGenerator.withGenerateRivers(generateRivers));
	}

	@Unique
	private static boolean earthmod$isEarthWorld(WorldCreator worldCreator) {
		return worldCreator.getGeneratorOptionsHolder().selectedDimensions().getChunkGenerator() instanceof EarthChunkGenerator;
	}

	@Unique
	private static boolean earthmod$currentGenerateRivers(WorldCreator worldCreator) {
		ChunkGenerator generator = worldCreator.getGeneratorOptionsHolder().selectedDimensions().getChunkGenerator();
		if (generator instanceof EarthChunkGenerator earthGenerator) {
			return earthGenerator.generateRivers();
		}
		return true;
	}

	@Unique
	private Object earthmod$buildGenerateRiversOptionGrid(WorldCreator worldCreator) {
		try {
			Class<?> optionGridClass = Class.forName("net.minecraft.client.gui.screen.world.WorldScreenOptionGrid");
			Class<?> builderClass = Class.forName("net.minecraft.client.gui.screen.world.WorldScreenOptionGrid$Builder");
			Class<?> optionBuilderClass = Class.forName("net.minecraft.client.gui.screen.world.WorldScreenOptionGrid$OptionBuilder");

			Method builderFactory = optionGridClass.getMethod("builder", int.class);
			Object builder = builderFactory.invoke(null, 310);

			Method add = builderClass.getMethod("add", Text.class, BooleanSupplier.class, Consumer.class);
			Object optionBuilder = add.invoke(
				builder,
				EARTHMOD_GENERATE_RIVERS_TEXT,
				(BooleanSupplier)() -> this.earthmod$generateRivers,
				(Consumer<Boolean>)value -> this.earthmod$setGenerateRivers(worldCreator, value)
			);

			Method toggleable = optionBuilderClass.getMethod("toggleable", BooleanSupplier.class);
			toggleable.invoke(optionBuilder, (BooleanSupplier)() -> earthmod$isEarthWorld(worldCreator));

			Method build = builderClass.getMethod("build", Consumer.class);
			Object optionGrid = build.invoke(
				builder,
				(Consumer<Object>)widget -> this.grid.add((net.minecraft.client.gui.widget.Widget)widget, 3, 0, 1, 2)
			);
			this.grid.refreshPositions();
			return optionGrid;
		} catch (ReflectiveOperationException exception) {
			throw new RuntimeException("Failed to build Earth world rivers toggle", exception);
		}
	}

	@Unique
	private static void earthmod$refreshOptionGrid(Object optionGrid) {
		try {
			optionGrid.getClass().getMethod("refresh").invoke(optionGrid);
		} catch (ReflectiveOperationException exception) {
			throw new RuntimeException("Failed to refresh Earth world rivers toggle", exception);
		}
	}
}
