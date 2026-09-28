package net.flameslight.zones.mixins;

import net.flameslight.zones.ZoneManager;
import net.flameslight.zones.ZoneOnlyBiomeFilter;
import net.flameslight.zones.types.biome.ZoneBiomeSourceContext;
import net.flameslight.zones.types.biome.ZoneBiomeSourceHolder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Priority below the default 1000 so this HEAD callback is applied, and therefore runs, before
 * TerraBlender's: TerraBlender also injects at HEAD of getNoiseBiome and always cancels with its own
 * region biome, which would otherwise return before a zone's forced biome is ever applied.
 */
@Mixin(value = MultiNoiseBiomeSource.class, priority = 900)
public abstract class MultiNoiseBiomeSourceMixin implements ZoneBiomeSourceHolder {
    /** Set once at level load (ZoneManager), read by every worldgen thread; null = not ours. */
    @Unique
    private volatile ZoneBiomeSourceContext structurezones$context;

    @Override
    public ZoneBiomeSourceContext structurezones$getContext() {
        return structurezones$context;
    }

    @Override
    public void structurezones$setContext(ZoneBiomeSourceContext context) {
        structurezones$context = context;
    }

    @Inject(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
            at = @At("HEAD"), cancellable = true)
    private void structurezones$forceZoneBiome(int x, int y, int z, Climate.Sampler sampler,
                                               CallbackInfoReturnable<Holder<Biome>> cir) {
        ZoneBiomeSourceContext context = structurezones$context;
        if (context == null) {
            return;
        }
        ZoneOnlyBiomeFilter filter = context.filter();
        boolean forced = ZoneManager.hasAnyForcedBiomeZone();
        if (!forced && filter == null) {
            return;
        }
        if (filter != null && ZoneOnlyBiomeFilter.isInNaturalLookup()) {
            return; // the nested natural lookup below: let TerraBlender/vanilla answer
        }

        if (forced) {
            // x/y/z here are quart (4-block) coordinates; convert to a representative block position.
            Holder<Biome> forcedBiome = ZoneManager.getForcedBiome(context.dimension(), (x << 2) + 2, (y << 2) + 2, (z << 2) + 2);
            if (forcedBiome != null) {
                cir.setReturnValue(forcedBiome);
                return;
            }
        }
        if (filter != null) {
            cir.setReturnValue(filter.naturalBiome((BiomeSource) (Object) this, x, y, z, sampler));
        }
    }
}
