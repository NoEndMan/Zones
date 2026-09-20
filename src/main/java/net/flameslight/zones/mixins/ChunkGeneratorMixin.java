package net.flameslight.zones.mixins;

import com.mojang.datafixers.util.Pair;
import net.flameslight.zones.ZoneManager;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    @Inject(method = "findNearestMapStructure", at = @At("HEAD"), cancellable = true)
    private void structurezones$redirectToZoneData(ServerLevel level, HolderSet<Structure> structures, BlockPos pos,
                                                   int searchRadius, boolean skipReferencedStructures,
                                                   CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> cir) {
        ResourceLocation dimension = level.dimension().location();

        for (Holder<Structure> holder : structures) {
            ResourceLocation structureId = holder.unwrapKey().map(k -> k.location()).orElse(null);
            if (structureId == null || !ZoneManager.isZoneRestrictedStructure(structureId)) {
                continue;
            }
            Optional<BlockPos> found = ZoneManager.findNearestZonePlacement(dimension, structureId, pos);
            if (found.isPresent()) {
                cir.setReturnValue(Pair.of(found.get(), holder));
                return;
            }
        }
    }

    @Inject(method = "findNearestMapStructure", at = @At("RETURN"), cancellable = true)
    private void structurezones$preferCloserZonePlacement(ServerLevel level, HolderSet<Structure> structures, BlockPos pos,
                                                          int searchRadius, boolean skipReferencedStructures,
                                                          CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> cir) {
        if (skipReferencedStructures) {
            return;
        }
        ResourceLocation dimension = level.dimension().location();
        Pair<BlockPos, Holder<Structure>> best = cir.getReturnValue();
        long bestDistSq = best == null ? Long.MAX_VALUE : structurezones$horizontalDistSq(best.getFirst(), pos);
        boolean improved = false;

        for (Holder<Structure> holder : structures) {
            ResourceLocation structureId = holder.unwrapKey().map(k -> k.location()).orElse(null);
            if (structureId == null || !ZoneManager.isZoneTrackedStructure(structureId)) {
                continue;
            }
            Optional<BlockPos> found = ZoneManager.findNearestZonePlacement(dimension, structureId, pos);
            if (found.isEmpty()) {
                continue;
            }
            long distSq = structurezones$horizontalDistSq(found.get(), pos);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = Pair.of(found.get(), holder);
                improved = true;
            }
        }

        if (improved) {
            cir.setReturnValue(best);
        }
    }

    /**
     * The commit point: tryGenerateStructure returns true only after setStartForStructure has put
     * the start into the chunk. Anything rejected earlier: by us, spaceAround, or another mod --
     * never reaches here.
     */
    @Inject(method = "tryGenerateStructure", at = @At("RETURN"))
    private void structurezones$onStructureCommitted(StructureSet.StructureSelectionEntry entry,
                                                     StructureManager structureManager, RegistryAccess registryAccess,
                                                     RandomState randomState, StructureTemplateManager templateManager,
                                                     long seed, ChunkAccess chunk, ChunkPos chunkPos,
                                                     SectionPos sectionPos, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        ResourceLocation dimension = ZoneManager.getDimensionForGenerator((ChunkGenerator) (Object) this);
        if (dimension == null || !ZoneManager.isGatingActive(dimension)) {
            return;
        }
        ResourceLocation structureId = entry.structure().unwrapKey().map(k -> k.location()).orElse(null);
        if (structureId != null) {
            ZoneManager.recordStructurePlaced(dimension, chunkPos, structureId);
        }
    }

    @Unique
    private static long structurezones$horizontalDistSq(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
