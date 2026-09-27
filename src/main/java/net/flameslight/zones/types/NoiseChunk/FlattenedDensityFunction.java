package net.flameslight.zones;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import org.jetbrains.annotations.NotNull;

public final class FlattenedDensityFunction implements DensityFunction {
    private final DensityFunction original;
    private final RandomState owner;

    /**
        RandomState is bound to a specific dimension when it is created, therefore the dimension
        will never change for the lifespan of this FlattenedDensityFunction.
     */
    private ResourceLocation cachedDimension = null;
    private boolean dimensionCached = false;

    public FlattenedDensityFunction(DensityFunction original, RandomState owner) {
        this.original = original;
        this.owner = owner;
    }

    @Override
    public double compute(FunctionContext context) {
        if (!ZoneManager.hasAnyFlattenZone()) {
            return original.compute(context);
        }
        ResourceLocation dimension = getDimension();
        if (dimension == null) {
            return original.compute(context);
        }
        int targetY = ZoneManager.getFlattenTargetYCached(dimension, context.blockX(), context.blockZ());
        if (targetY == Integer.MIN_VALUE) {
            return original.compute(context);
        }
        return targetY - context.blockY() + 1;
    }

    @Override
    public void fillArray(double[] array, ContextProvider contextProvider) {
        // Delegate to original's optimized array filling first
        original.fillArray(array, contextProvider);

        if (!ZoneManager.hasAnyFlattenZone() || array.length == 0) {
            return;
        }

        ResourceLocation dimension = getDimension();
        if (dimension == null) {
            return;
        }

        // One chunk-level test instead of a per-cell lookup: every context in a single fillArray
        // call belongs to the same chunk, so if no flatten zone touches that chunk, nothing in
        // this array can need overwriting.
        FunctionContext first = contextProvider.forIndex(0);
        if (!ZoneManager.chunkHasFlattenZone(dimension, first.blockX(), first.blockZ())) {
            return;
        }

        // Overwrite only the modified values
        for (int i = 0; i < array.length; i++) {
            FunctionContext context = contextProvider.forIndex(i);
            int targetY = ZoneManager.getFlattenTargetYCached(dimension, context.blockX(), context.blockZ());
            if (targetY != Integer.MIN_VALUE) {
                array[i] = targetY - context.blockY() + 1;
            }
        }
    }

    @Override
    public @NotNull DensityFunction mapAll(Visitor visitor) {
        // Properly pass the visitor down the tree to ensure chunk caches are created
        return visitor.apply(new FlattenedDensityFunction(this.original.mapAll(visitor), this.owner));
    }

    @Override
    public double minValue() {
        return Math.min(original.minValue(), -2032);
    }

    @Override
    public double maxValue() {
        return Math.max(original.maxValue(), 2032);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        throw new UnsupportedOperationException("FlattenedDensityFunction is runtime-only and has no codec");
    }

    private ResourceLocation getDimension() {
        if (!dimensionCached) {
            cachedDimension = ZoneManager.getDimensionForRandomState(owner);
            dimensionCached = true;
        }
        return cachedDimension;
    }
}
