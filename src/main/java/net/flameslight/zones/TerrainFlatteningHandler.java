package net.flameslight.zones;

import net.flameslight.zones.types.ZoneInstance;
import net.flameslight.zones.types.zoneDefinition.FlattenMode;
import net.flameslight.zones.types.zoneDefinition.ZoneDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * handling shouldFlattenTerrain zone level option, called right after the raw noise fill (before surface,
 * carvers, or features run). Directly enforces solid/air relative to a fixed target Y for every
 * column in the zone, deciding fill material PER COLUMN from what that column's own natural
 * surface actually was, sampled once before any of our own edits touch it: a column whose natural
 * surface is water (part of a lake/ocean/river) gets filled with water, so a natural water body
 * inside the zone stays a water body at the new flattened level instead of being paved over with
 * stone; every other column gets solid stone. This is a single decision per column, applied
 * uniformly across the whole fill range.
 */
public final class TerrainFlatteningHandler {
    /** Random points sampled inside a zone to choose its flatten Y. */
    private static final int FLAT_Y_SAMPLES = 30;

    /** How many blocks, INCLUDING targetY itself, are forced solid/water at the top of a flattened
     *  column and patches any air pocket the noise stage left just under the new surface. */
    private static final int FILL_PATCH_DEPTH = 4;

    /** Which sorted sample the SURFACE mode targets. The minimum lets one point on a seabed or in
     *  a ravine cut the whole zone down to it; a low percentile still biases toward cutting rather
     *  than filling, without following a single outlier. */
    private static final float SURFACE_SAMPLE_PERCENTILE = 0.3f;

    /** How far ABOVE the target Y the clip pass keeps looking once the column's own recorded
     *  surface has been passed. Pure safety margin for blocks written without updating the
     *  WORLD_SURFACE_WG heightmap. */
    private static final int CLIP_MARGIN_ABOVE = 64;

    public static void applyTerrainFlattening(ResourceLocation dimension, ChunkAccess chunk) {
        if (!ZoneManager.hasAnyFlattenZone()) {
            return;
        }
        ChunkPos chunkPos = chunk.getPos();
        List<ZoneInstance> zones = ZoneManager.zonesNearChunk(
                dimension, chunkPos.getMinBlockX(), chunkPos.getMinBlockZ());
        if (zones.isEmpty()) {
            return;
        }

        // Which flatten zones touch this chunk is decided ONCE, not re-tested per column.
        List<ZoneInstance> touching = null;
        for (ZoneInstance zi : zones) {
            if (zi.flattenY == null || !circleTouchesChunk(zi, chunkPos)) {
                continue;
            }
            if (touching == null) {
                touching = new ArrayList<>(zones.size());
            }
            touching.add(zi);
        }
        if (touching == null) {
            return;
        }

        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;

                // One decision per column: the SMALLEST zone covering it wins, matching how
                // ZoneManager resolves overlapping zones everywhere else.
                ZoneInstance best = null;
                for (ZoneInstance zi : touching) {
                    if (zi.contains(wx, wz) && (best == null || zi.radius < best.radius)) {
                        best = zi;
                    }
                }
                if (best != null) {
                    flattenColumn(chunk, pos, wx, wz, best.flattenY);
                }
            }
        }
    }

    public static Integer computeFlattenY(ZoneDefinition def, int centerX, int centerZ, int radius,
                                          ChunkGenerator chunkGenerator, RandomState randomState,
                                          LevelHeightAccessor heightAccessor, RandomSource random,
                                          int seaLevel) {
        if (!def.shouldFlattenTerrain.isEnabled()) {
            return null;
        } else if (def.shouldFlattenTerrain == FlattenMode.SURFACE) {
            int[] samples = new int[FLAT_Y_SAMPLES];

            for (int i = 0; i < FLAT_Y_SAMPLES; i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double dist = Math.sqrt(random.nextDouble()) * radius;
                int x = centerX + (int) Math.round(Math.cos(angle) * dist);
                int z = centerZ + (int) Math.round(Math.sin(angle) * dist);
                samples[i] = chunkGenerator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                        heightAccessor, randomState);
            }

            Arrays.sort(samples);
            return Math.max(samples[(int) (FLAT_Y_SAMPLES * SURFACE_SAMPLE_PERCENTILE)], seaLevel);
        } else {
            // FlattenMode.UNDERWATER_SURFACE case

            int minY = Integer.MAX_VALUE;

            for (int i = 0; i < FLAT_Y_SAMPLES; i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                double dist = Math.sqrt(random.nextDouble()) * radius;
                int x = centerX + (int) Math.round(Math.cos(angle) * dist);
                int z = centerZ + (int) Math.round(Math.sin(angle) * dist);
                int y = chunkGenerator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState);
                minY = Math.min(minY, y);
            }

            return minY;
        }
    }

    private static void flattenColumn(ChunkAccess chunk,
                                      BlockPos.MutableBlockPos pos,
                                      int wx,
                                      int wz,
                                      int targetY) {
        // Decide BEFORE any modification: is this column's natural surface water, or land?
        int naturalTop = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, wx, wz) - 1;
        boolean waterColumn = false;
        if (naturalTop >= chunk.getMinBuildHeight()) {
            pos.set(wx, naturalTop, wz);
            waterColumn = !chunk.getBlockState(pos).getFluidState().isEmpty();
        }
        BlockState fillState = waterColumn ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState();

        int fillFrom = Math.max(chunk.getMinBuildHeight(), targetY - FILL_PATCH_DEPTH + 1);

        for (int y = fillFrom; y <= targetY; y++) {
            pos.set(wx, y, wz);
            BlockState state = chunk.getBlockState(pos);
            // Land columns: fill air AND any stray water pocket with solid. Water columns: only
            // fill genuine air gaps with water. existing water already at the right level is
            // left untouched rather than needlessly rewritten.
            boolean needsFill = state.isAir() || (!waterColumn && !state.getFluidState().isEmpty());
            if (needsFill) {
                chunk.setBlockState(pos, fillState, false);
            }
        }

        // The noise stage already produced air above targetY (FlattenedDensityFunction), so this
        // pass only mops up what slipped past it: aquifer fluid and other mods' own fillFromNoise
        // writes.
        int clipTo = Math.min(chunk.getMaxBuildHeight() - 1, Math.max(naturalTop, targetY + CLIP_MARGIN_ABOVE));

        for (int y = targetY + 1; y <= clipTo; y++) {
            pos.set(wx, y, wz);
            if (!chunk.getBlockState(pos).isAir()) {
                chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
            }
        }
    }

    private static boolean circleTouchesChunk(ZoneInstance zi, ChunkPos pos) {
        int closestX = Math.max(pos.getMinBlockX(), Math.min(zi.centerX, pos.getMaxBlockX()));
        int closestZ = Math.max(pos.getMinBlockZ(), Math.min(zi.centerZ, pos.getMaxBlockZ()));
        long dx = zi.centerX - closestX;
        long dz = zi.centerZ - closestZ;
        long r = zi.radius;
        return dx * dx + dz * dz <= r * r;
    }
}
