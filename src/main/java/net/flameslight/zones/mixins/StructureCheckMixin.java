package net.flameslight.zones.mixins;

import net.flameslight.zones.ZoneManager;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * StructureCheck predicts whether a structure would start in a chunk without generating it (vanilla
 * /locate and explorer maps, and other mods' structure predictions, e.g. RoadWeaver's road
 * destinations). Vanilla only tests biomes there. This mixin adds the same zone gate Structure#generate
 * applies (StructureMixin), at the same position, so a structure the zones forbid is never predicted.
 * Actual generation is untouched: ChunkGenerator.createStructures doesn't go through StructureCheck.
 */
@Mixin(StructureCheck.class)
public abstract class StructureCheckMixin {
    @Shadow
    @Final
    private ResourceKey<Level> dimension;

    @Shadow
    @Final
    private Registry<Structure> structureConfigs;

    @Shadow
    @Final
    private long seed;

    @Inject(method = "canCreateStructure", at = @At("RETURN"), cancellable = true)
    private void structurezones$rejectZoneForbiddenStart(ChunkPos chunkPos, Structure structure,
                                                         CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        ResourceLocation dim = this.dimension.location();
        if (!ZoneManager.isGatingActive(dim)) {
            return;
        }
        ResourceLocation structureId = this.structureConfigs.getKey(structure);
        if (structureId == null) {
            return;
        }
        // Same block Structure#generate gates on (StructureMixin): the chunk's center.
        if (!ZoneManager.isStructureAllowed(dim, chunkPos.getMinBlockX() + 8, chunkPos.getMinBlockZ() + 8,
                structureId, this.seed)) {
            cir.setReturnValue(false);
        }
    }
}
