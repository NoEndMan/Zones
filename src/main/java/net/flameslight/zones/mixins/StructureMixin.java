package net.flameslight.zones.mixins;

import net.flameslight.zones.ZoneLimiterHandler;
import net.flameslight.zones.SpaceAroundTracker;
import net.flameslight.zones.ZoneManager;
import net.flameslight.zones.types.zoneDefinition.ZoneDefinition;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(Structure.class)
public abstract class StructureMixin {
    @Inject(method = "generate", at = @At("HEAD"), cancellable = true)
    private void structurezones$gateGeneration(
            RegistryAccess registryAccess,
            ChunkGenerator chunkGenerator,
            BiomeSource biomeSource,
            RandomState randomState,
            StructureTemplateManager structureTemplateManager,
            long seed,
            ChunkPos chunkPos,
            int references,
            LevelHeightAccessor heightAccessor,
            Predicate<Holder<Biome>> validBiome,
            CallbackInfoReturnable<StructureStart> cir) {
        ZoneLimiterHandler.endHandling(); // never let a previous generate's context leak into this one
        ResourceLocation dimension = ZoneManager.getDimensionForGenerator(chunkGenerator);

        if (dimension == null || !ZoneManager.isGatingActive(dimension)) {
            return;
        }

        Structure self = (Structure) (Object) this;
        ResourceLocation structureId = registryAccess.registryOrThrow(Registries.STRUCTURE).getKey(self);
        if (structureId == null) {
            return;
        }

        int blockX = chunkPos.getMinBlockX() + 8;
        int blockZ = chunkPos.getMinBlockZ() + 8;

        if (!ZoneManager.isStructureAllowed(dimension, blockX, blockZ, structureId, seed)) {
            cir.setReturnValue(StructureStart.INVALID_START);
            return;
        }

        // The context is only worth building when something will actually read it: the placer hook
        // (jigsaw only) or a leak rule. Everything else skips it entirely.
        String idStr = ZoneManager.idString(structureId);
        ZoneDefinition.StructureEntry entry = ZoneManager.resolveEntryAt(dimension, blockX, blockZ, idStr);
        if (entry == null) {
            return;
        }
        boolean jigsaw = self instanceof JigsawStructure;
        if (jigsaw || entry.blockLeakingOutsideZone || entry.blockLeakingIntoNestedZones) {
            ZoneLimiterHandler.beginHandling(dimension, blockX, blockZ, idStr, entry, jigsaw);
        }
    }

    /**
     * Widen the structure's own biome check with whatever biomes its owning zone(s) allow.
     */
    @ModifyVariable(method = "generate", at = @At("HEAD"), argsOnly = true)
    private Predicate<Holder<Biome>> structurezones$widenBiomePredicate(
            Predicate<Holder<Biome>> validBiome,
            RegistryAccess registryAccess,
            ChunkGenerator chunkGenerator,
            BiomeSource biomeSource,
            RandomState randomState,
            StructureTemplateManager structureTemplateManager,
            long seed,
            ChunkPos chunkPos,
            int references,
            LevelHeightAccessor heightAccessor) {
        Structure self = (Structure) (Object) this;
        ResourceLocation structureId = registryAccess.registryOrThrow(Registries.STRUCTURE).getKey(self);
        ResourceLocation dimension = ZoneManager.getDimensionForGenerator(chunkGenerator);

        if (structureId == null || dimension == null || !ZoneManager.isGatingActive(dimension)) {
            return validBiome;
        }

        int blockX = chunkPos.getMinBlockX() + 8;
        int blockZ = chunkPos.getMinBlockZ() + 8;

        return holder -> validBiome.test(holder) ||
                ZoneManager.isBiomeWidenedForStructure(dimension, blockX, blockZ, structureId, holder);
    }

    @Inject(method = "generate", at = @At("RETURN"), cancellable = true)
    private void structurezones$afterGeneration(
            RegistryAccess registryAccess,
            ChunkGenerator chunkGenerator,
            BiomeSource biomeSource,
            RandomState randomState,
            StructureTemplateManager structureTemplateManager,
            long seed,
            ChunkPos chunkPos,
            int references,
            LevelHeightAccessor heightAccessor,
            Predicate<Holder<Biome>> validBiome,
            CallbackInfoReturnable<StructureStart> cir) {
        try {
            ResourceLocation dimension = ZoneManager.getDimensionForGenerator(chunkGenerator);

            if (dimension == null || !ZoneManager.isGatingActive(dimension)) {
                return;
            }
            Structure self = (Structure) (Object) this;
            ResourceLocation structureId = registryAccess.registryOrThrow(Registries.STRUCTURE).getKey(self);

            if (structureId == null) {
                return;
            }

            int blockX = chunkPos.getMinBlockX() + 8;
            int blockZ = chunkPos.getMinBlockZ() + 8;

            StructureStart placementResult = cir.getReturnValue();
            boolean succeeded = placementResult != null && placementResult != StructureStart.INVALID_START;
            boolean jigsaw = self instanceof JigsawStructure;

            // Leak rules, for every structure type. A jigsaw's child pieces were already trimmed
            // during assembly, so this catches its start piece (never trimmed) and any assembly
            // path that bypassed the placer hook; for anything else it is the only enforcement.
            if (succeeded && ZoneLimiterHandler.violatesLeakRules(placementResult)) {
                cir.setReturnValue(StructureStart.INVALID_START);
                succeeded = false;
            } else if (succeeded) {
                // spaceAround: atomic check + register (per piece for jigsaws).
                int ownSpaceAround = ZoneManager.resolveSpaceAround(dimension, blockX, blockZ, structureId);
                if (!SpaceAroundTracker.checkAndRegister(dimension,
                        blockX,
                        blockZ,
                        placementResult,
                        jigsaw,
                        ownSpaceAround)) {
                    cir.setReturnValue(StructureStart.INVALID_START);
                    succeeded = false;
                }
            }

            ZoneManager.recordPlacementOutcome(dimension, blockX, blockZ, structureId, succeeded);
        } finally {
            ZoneLimiterHandler.endHandling();
        }
    }
}
