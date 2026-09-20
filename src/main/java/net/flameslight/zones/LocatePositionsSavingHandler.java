package net.flameslight.zones;

import net.flameslight.zones.logger.ModLogger;
import net.flameslight.zones.types.locate.LocateCache;
import net.flameslight.zones.types.locate.LocateStore;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Everything to do with the positions /locate reads: one on-disk store per dimension, the
 * short-lived cache in front of it, and the policy for waiting on a read.
 *
 * Positions are recorded for every structure committed inside a zone and are only ever read back by
 * /locate (and by whatever else calls findNearestMapStructure, such as an eye of ender), so none of
 * it is kept in memory: appends land in the store's own buffer, that buffer reaches disk on the
 * usual flush interval, and a search streams the files back on the IO pool.
 */
public final class LocatePositionsSavingHandler {
    /** A read waits this long for the IO pool before giving up and leaving vanilla's own search to
     *  answer, so a slow disk can never cancel a /locate. */
    private static final long READ_TIMEOUT_MILLIS = 2_000L;

    private static final Map<ResourceLocation, LocateStore> STORES = new ConcurrentHashMap<>();
    private static final LocateCache CACHE = new LocateCache();

    // ---- Lifecycle ----

    public static void openDimension(ResourceLocation dimension, Path dataDir) {
        STORES.computeIfAbsent(dimension, k -> new LocateStore(dataDir, prefix(dimension)));
        CACHE.clear(dimension);
    }

    public static void closeDimension(ResourceLocation dimension) {
        LocateStore store = STORES.remove(dimension);
        if (store != null) {
            store.flush();
        }
        CACHE.clear(dimension);
    }

    public static void reset() {
        STORES.clear();
        CACHE.clear();
    }

    // ---- Writing ----

    /** Called from worldgen threads once a structure has been committed to its chunk. */
    public static void record(ResourceLocation dimension, String structureIdStr, long packedPos) {
        LocateStore store = STORES.get(dimension);
        if (store == null) {
            return;
        }
        store.append(structureIdStr, packedPos);
        CACHE.invalidate(dimension, structureIdStr);
    }

    /** Writes every store's buffered records. Safe from any thread. */
    public static void flushPending() {
        for (LocateStore store : STORES.values()) {
            store.flush();
        }
    }

    public static boolean hasPendingWrites() {
        for (LocateStore store : STORES.values()) {
            if (store.hasPending()) {
                return true;
            }
        }
        return false;
    }

    // ---- Reading ----

    /**
     * Nearest recorded placement of a structure in this dimension. The scan runs on the IO pool and
     * this waits for it, so the read never occupies the server thread's CPU. On a timeout or a read
     * failure this returns empty, which leaves vanilla's own search to answer.
     */
    public static Optional<BlockPos> findNearest(ResourceLocation dimension, String structureIdStr, BlockPos from) {
        long[] positions = CACHE.get(dimension, structureIdStr);

        if (positions == null) {
            LocateStore store = STORES.get(dimension);
            if (store == null) {
                return Optional.empty();
            }
            try {
                positions = CompletableFuture.supplyAsync(() -> {
                    store.flush(); // anything appended since the last flush isn't on disk yet
                    return store.scan(structureIdStr);
                }, Util.ioPool()).get(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // The scan keeps running and its result is discarded; the next call starts over.
                ModLogger.warn("Timed out reading recorded placements for '{}'; leaving this search to "
                        + "vanilla, which cannot see placements made off its own spacing grid.", structureIdStr);
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception e) {
                ModLogger.error("Failed to read recorded placements for '{}'", structureIdStr, e);
                return Optional.empty();
            }
            CACHE.put(dimension, structureIdStr, positions);
        }

        if (positions.length == 0) {
            return Optional.empty();
        }

        long bestPacked = 0L;
        long bestDistSq = Long.MAX_VALUE;
        for (long packed : positions) {
            long dx = (int) (packed >> 32) - from.getX();
            long dz = (int) packed - from.getZ();
            long distSq = dx * dx + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestPacked = packed;
            }
        }
        return Optional.of(new BlockPos((int) (bestPacked >> 32), from.getY(), (int) bestPacked));
    }

    private static String prefix(ResourceLocation dimensionId) {
        return dimensionId.toString().replace(':', '_') + "_locate";
    }
}
