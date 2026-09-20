package net.flameslight.zones.mixins;

import net.flameslight.zones.ZoneManager;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {
    @Inject(method = "getNoiseBiome*", at = @At("HEAD"), cancellable = true)
    private void structurezones$forceZoneBiome(int x, int y, int z, Climate.Sampler sampler,
                                               CallbackInfoReturnable<Holder<Biome>> cir) {
        if (!ZoneManager.hasAnyForcedBiomeZone()) {
            return;
        }

        BiomeSource self = (BiomeSource) (Object) this;
        ResourceLocation dimension = ZoneManager.getDimensionForBiomeSource(self);
        if (dimension == null) {
            return;
        }

        // x/y/z here are quart (4-block) coordinates; convert to a representative block position.
        int blockX = (x << 2) + 2;
        int blockY = (y << 2) + 2;
        int blockZ = (z << 2) + 2;

        ZoneManager.getForcedBiome(dimension, blockX, blockY, blockZ).ifPresent(cir::setReturnValue);
    }
}
