package net.flameslight.zones;

import net.flameslight.zones.logger.ModLogger;
import net.flameslight.zones.types.ZoneInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.levelgen.Heightmap;

public class SpawningInsideZoneHandler {
    private static final float RADIUS_STEP_PERCENTAGE = 0.1f;
    private static final int TRIES_PER_SEARCHED_RADIUS = 16;

    public static BlockPos findSafeSpawnInsideZone(ServerLevel level,
                                              ZoneInstance zone) {
        int centerX = zone.centerX;
        int centerZ = zone.centerZ;
        BlockPos direct = trySpawnColumn(level, centerX, centerZ);

        if (direct != null) {
            return direct;
        }

        int maxRadius = zone.radius;

        for (float p = RADIUS_STEP_PERCENTAGE; p <= 1; p += RADIUS_STEP_PERCENTAGE) {
            int radius = (int) (maxRadius * p);

            for (int i = 0; i < TRIES_PER_SEARCHED_RADIUS; i++) {
                double angle = (Math.PI * 2 * i) / TRIES_PER_SEARCHED_RADIUS;
                int x = centerX + (int) Math.round(Math.cos(angle) * radius);
                int z = centerZ + (int) Math.round(Math.sin(angle) * radius);
                BlockPos candidate = trySpawnColumn(level, x, z);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        ModLogger.warn("Could not find an open-sky spawn position within {} blocks of [{}, {}]; "
                        + "falling back to the raw heightmap position, which may be underground.",
                maxRadius, centerX, centerZ);
        int fallbackY = level.getHeight(Heightmap.Types.WORLD_SURFACE, centerX, centerZ);
        return new BlockPos(centerX, fallbackY, centerZ);
    }

    private static BlockPos trySpawnColumn(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.canSeeSky(pos)) {
            return null;
        }
        if (level.getFluidState(pos.below()).is(FluidTags.LAVA)) {
            return null;
        }
        return pos;
    }
}
