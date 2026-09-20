package net.flameslight.zones.mixins;

import net.flameslight.zones.FlattenedDensityFunction;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * shouldFlattenTerrain: substitutes the router's finalDensity with a wrapper right after RandomState
 * finishes building its own router, so every chunk generated from this RandomState treats flattened-zone
 * columns as flat terrain from the noise stage itself, rather than patching blocks after the fact.
 *
 * `router` is assigned directly inside RandomState's private constructor (no intermediate local
 * variable of type NoiseRouter exists to @ModifyVariable), and the field is final, so this
 * shadows it with @Mutable (which strips the final flag on the merged field) and reassigns it at
 * RETURN, after vanilla's own assignment. Safe specifically because `sampler` (built just before
 * the constructor returns) is derived from the ORIGINAL router's temperature/vegetation/continents/
 * erosion/depth/ridges functions, captured independently before this overwrite, none of which
 * are touched here, so overwriting `router` afterward doesn't disturb it.
 */
@Mixin(RandomState.class)
public abstract class RandomStateMixin {

    @Shadow
    @Mutable
    private NoiseRouter router;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void structurezones$wrapFinalDensity(NoiseGeneratorSettings settings,
                                                 HolderGetter<NormalNoise.NoiseParameters> noises,
                                                 long seed, CallbackInfo ci) {
        RandomState self = (RandomState) (Object) this;
        this.router = new NoiseRouter(
                router.barrierNoise(), router.fluidLevelFloodednessNoise(), router.fluidLevelSpreadNoise(),
                router.lavaNoise(), router.temperature(), router.vegetation(), router.continents(),
                router.erosion(), router.depth(), router.ridges(), router.initialDensityWithoutJaggedness(),
                new FlattenedDensityFunction(router.finalDensity(), self),
                router.veinToggle(), router.veinRidged(), router.veinGap()
        );
    }
}
