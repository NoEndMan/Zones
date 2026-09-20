package net.flameslight.zones.mixins;

import net.flameslight.zones.TerrainFlatteningHandler;
import net.flameslight.zones.ZoneManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    /**
     * shouldFlattenTerrain support. Runs right after vanilla's own raw-noise terrain fill and BEFORE
     * surface materials, carvers, or features, so the flattened terrain is what surface painting,
     * cave carving, and (crucially) tree/feature placement all see from that point on.
     */
    @Inject(method = "fillFromNoise", at = @At("RETURN"), cancellable = true)
    private void structurezones$flattenAfterNoise(Executor executor, Blender blender, RandomState randomState,
                                                  StructureManager structureManager, ChunkAccess chunk,
                                                  CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        ResourceLocation dimension = ZoneManager.getDimensionForGenerator(self);
        if (dimension == null) {
            return;
        }
        CompletableFuture<ChunkAccess> original = cir.getReturnValue();

        cir.setReturnValue(original.thenApply(result -> {
            TerrainFlatteningHandler.applyTerrainFlattening(dimension, result);
            ZoneManager.recordChunkGenerated(dimension, result.getPos().x, result.getPos().z);
            return result;
        }));
    }

    /**
     * shouldFlattenTerrain support: without this, structures still decide their OWN placement
     * height during STRUCTURE_STARTS, which runs BEFORE the NOISE stage and therefore, before
     * ChunkGeneratorFlattenMixin's own block-level flattening ever executes, so a structure's
     * internal piece coordinates would still be baked in relative to the ORIGINAL, un-flattened
     * terrain prediction. This forces getBaseHeight (the exact function structures query for their own
     * placement Y) to report the zone's flattenY for any column inside a shouldFlattenTerrain zone, so
     * structures and terrain agree on one consistent Y from the start.
     */
    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void structurezones$overrideForFlattenedZones(int x, int z, Heightmap.Types type,
                                                          LevelHeightAccessor level, RandomState randomState,
                                                          CallbackInfoReturnable<Integer> cir) {
        if (ZoneManager.COMPUTING_REAL_SURFACE.get() || !ZoneManager.hasAnyFlattenZone()) {
            return;
        }

        ChunkGenerator self = (ChunkGenerator) (Object) this;
        ResourceLocation dimension = ZoneManager.getDimensionForGenerator(self);
        if (dimension == null) {
            return;
        }
        int flattenY = ZoneManager.getFlattenTargetYCached(dimension, x, z);
        if (flattenY == Integer.MIN_VALUE) {
            return;
        }

        cir.setReturnValue(flattenY + 1);
    }
}
