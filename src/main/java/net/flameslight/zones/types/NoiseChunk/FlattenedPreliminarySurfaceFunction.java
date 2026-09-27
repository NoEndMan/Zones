package net.flameslight.zones.types.NoiseChunk;

import net.flameslight.zones.ZoneManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import org.jetbrains.annotations.NotNull;

/**
 * shouldFlattenTerrain: wraps the router's initialDensityWithoutJaggedness, which vanilla only reads
 * through NoiseChunk.computePreliminarySurfaceLevel. That preliminary surface gates every overworld
 * grass/dirt rule (abovePreliminarySurface) and caps aquifer fluid levels, so leaving it unflattened
 * made the surface system think a cut-down zone was still buried under its original hill, leaving
 * bare stone floors. It is sampled only at chunk corners and interpolated between them, so this
 * flattens out to the zone radius + ZoneManager's preliminary surface margin.
 */
public final class FlattenedPreliminarySurfaceFunction implements DensityFunction {
    private static final int COORD_BITS = 26;
    private static final long COORD_MASK = (1L << COORD_BITS) - 1;
    private static final int Y_BITS = 12;
    private static final long Y_MASK = (1L << Y_BITS) - 1;
    /** Below the lowest possible build height (-2032), so it can never collide with a real flattenY. */
    private static final int NO_ZONE_Y = -(1 << (Y_BITS - 1));
    /** x = -2^25 lies outside the world border, so no real column ever matches the empty cache. */
    private static final long EMPTY = pack(-(1 << (COORD_BITS - 1)), 0, 0);

    private final DensityFunction original;
    private final RandomState owner;

    /**
     * RandomState is bound to a specific dimension when it is created, therefore the dimension
     * will never change for the lifespan of this function.
     */
    private ResourceLocation cachedDimension = null;
    private boolean dimensionCached = false;

    /**
     * Last column looked up: x, z and its target Y packed in one long. computePreliminarySurfaceLevel
     * walks a whole column top-down in cellHeight steps, asking the same (x, z) ~48 times in a row.
     * Volatile so the long can never be read torn if an instance is ever shared across threads.
     */
    private volatile long lastColumn = EMPTY;

    public FlattenedPreliminarySurfaceFunction(DensityFunction original, RandomState owner) {
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
        int targetY = targetY(dimension, context.blockX(), context.blockZ());
        if (targetY == Integer.MIN_VALUE) {
            return original.compute(context);
        }
        return targetY - context.blockY() + 1;
    }

    @Override
    public void fillArray(double[] array, ContextProvider contextProvider) {
        original.fillArray(array, contextProvider);

        if (!ZoneManager.hasAnyFlattenZone() || array.length == 0) {
            return;
        }
        ResourceLocation dimension = getDimension();
        if (dimension == null) {
            return;
        }
        for (int i = 0; i < array.length; i++) {
            FunctionContext context = contextProvider.forIndex(i);
            int targetY = targetY(dimension, context.blockX(), context.blockZ());
            if (targetY != Integer.MIN_VALUE) {
                array[i] = targetY - context.blockY() + 1;
            }
        }
    }

    @Override
    public @NotNull DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new FlattenedPreliminarySurfaceFunction(this.original.mapAll(visitor), this.owner));
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
        throw new UnsupportedOperationException("FlattenedPreliminarySurfaceFunction is runtime-only and has no codec");
    }

    private int targetY(ResourceLocation dimension, int blockX, int blockZ) {
        long cached = lastColumn;
        if ((cached >>> Y_BITS) == columnKey(blockX, blockZ)) {
            int y = unpackY(cached);
            return y == NO_ZONE_Y ? Integer.MIN_VALUE : y;
        }
        int targetY = ZoneManager.getPreliminarySurfaceFlattenY(dimension, blockX, blockZ);
        lastColumn = pack(blockX, blockZ, targetY == Integer.MIN_VALUE ? NO_ZONE_Y : targetY);
        return targetY;
    }

    private static long columnKey(int blockX, int blockZ) {
        return ((blockX & COORD_MASK) << COORD_BITS) | (blockZ & COORD_MASK);
    }

    private static long pack(int blockX, int blockZ, int y) {
        return (columnKey(blockX, blockZ) << Y_BITS) | (y & Y_MASK);
    }

    private static int unpackY(long packed) {
        return (int) (packed << (64 - Y_BITS) >> (64 - Y_BITS)); // sign-extend the low 12 bits
    }

    private ResourceLocation getDimension() {
        if (!dimensionCached) {
            cachedDimension = ZoneManager.getDimensionForRandomState(owner);
            dimensionCached = true;
        }
        return cachedDimension;
    }
}
