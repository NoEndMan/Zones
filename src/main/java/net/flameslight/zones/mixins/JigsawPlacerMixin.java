package net.flameslight.zones.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.flameslight.zones.ZoneLimiterHandler;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Trimming for blockLeakingOutsideZone / blockLeakingIntoNestedZones / per-piece spaceAround, for
 * jigsaw structures. tryPlacingChildren asks joinIsNotEmpty(freeSpace, candidate, ONLY_SECOND)
 * whether a candidate piece collides; answering "collides" makes vanilla skip it and close that
 * branch naturally. WrapOperation chains with other mods' wraps of the same call instead of
 * conflicting.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public abstract class JigsawPlacerMixin {
    @WrapOperation(method = "tryPlacingChildren", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"))
    private boolean structurezones$applyZoneRules(VoxelShape freeSpace, VoxelShape candidate, BooleanOp op,
                                                  Operation<Boolean> original) {
        return original.call(freeSpace, candidate, op) || ZoneLimiterHandler.blocksCandidate(candidate);
    }
}
