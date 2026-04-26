package dev.mearth.earth.client.mixin;

import dev.mearth.earth.EarthMod;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.HeightLimitView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientWorld.Properties.class)
public abstract class ClientWorldPropertiesMixin {
	@Inject(method = "getSkyDarknessHeight", at = @At("HEAD"), cancellable = true)
	private void earthmod$useWorldBottomForSkyDarkness(HeightLimitView world, CallbackInfoReturnable<Double> cir) {
		if (world instanceof ClientWorld clientWorld && isEarthWorld(clientWorld)) {
			cir.setReturnValue((double)world.getBottomY());
		}
	}

	private boolean isEarthWorld(ClientWorld world) {
		Identifier effects = world.getDimension().effects();
		return EarthMod.id("earth_overworld").equals(world.getDimensionEntry().getKey().map(key -> key.getValue()).orElse(null))
			|| (Identifier.of("minecraft", "overworld").equals(effects) && world.getBottomY() == -512 && world.getHeight() == 1024);
	}
}
