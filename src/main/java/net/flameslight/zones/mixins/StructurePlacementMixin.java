package net.flameslight.zones.mixins;

import net.flameslight.zones.ZoneManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(StructurePlacement.class)
public abstract class StructurePlacementMixin {
    @Inject(method = "isStructureChunk", at = @At("RETURN"), cancellable = true)
    private void structurezones$expandEligibility(ChunkGeneratorStructureState structureState, int chunkX, int chunkZ,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            return;
        }

        if (!ZoneManager.hasAnyGuaranteePlacement() && !ZoneManager.hasAnyIncreasedDensity()) {
            return;
        }

        StructurePlacement self = (StructurePlacement) (Object) this;
        List<ResourceLocation> structureIds = ZoneManager.getStructuresForPlacement(self);

        if (structureIds.isEmpty()) {
            return;
        }

        int blockX = (chunkX << 4) + 8;
        int blockZ = (chunkZ << 4) + 8;

        // ChunkGeneratorStructureState has no generator, but its RandomState is the level's own,
        // so this resolves the dimension exactly. Null only if the state isn't one we registered,
        // in which case ZoneManager falls back to scanning every loaded dimension.
        ResourceLocation dimension = ZoneManager.getDimensionForRandomState(structureState.randomState());

        if (ZoneManager.shouldForceGuaranteedEligibility(dimension, blockX, blockZ, structureIds)) {
            cir.setReturnValue(true);
            return;
        }

        if (!ZoneManager.hasAnyIncreasedDensity()) {
            return;
        }

        float extraChance = ZoneManager.getMaxExtraChance(dimension, blockX, blockZ, structureIds);
        if (extraChance <= 0f) {
            return;
        }

        long salt = ZoneManager.idString(structureIds.get(0)).hashCode();
        double roll = ZoneManager.deterministicRoll(salt, chunkX, chunkZ);
        double threshold = extraChance * ZoneManager.densityBaseChance();
        if (roll < threshold) {
            cir.setReturnValue(true);
        }
    }
}
