package net.flameslight.zones.types;

import net.flameslight.zones.types.spaceAround.SpaceAroundGrid;
import net.flameslight.zones.types.spaceAround.SpaceAroundRecord;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ZoneInstance {
    private static final int MAX_TRACKED_CHUNK_BITS = 1 << 18;

    public String zoneType;
    public String dimension;
    public int centerX;
    public int centerZ;
    public int radius;
    public Integer flattenY = null;
    /** Persisted as a plain "namespace:path" string by ZoneManager.GSON's ResourceLocation adapter. */
    @Nullable
    public ResourceLocation forcedBiome = null;
    /**
     * forcedBiome resolved against the running world's biome registry, once per load by
     * ZoneManager.loadOrGenerate. Null if the zone forces nothing or the biome is missing.
     */
    @Nullable
    public transient Holder<Biome> forcedBiomeHolder = null;
    public int generatedChunks = 0;
    public boolean spaceAroundRetired = false;
    /**
     * One bit per chunk column in this zone's bounding grid. Persisted so progress survives a
     * restart; nulled on retirement, which is when it stops being worth anything.
     */
    public long[] generatedChunkBits = null;
    private transient int totalChunks = -1; // transient: recomputed on load, never persisted
    private transient boolean chunkGridReady = false;
    private transient int chunkGridMinCx, chunkGridMinCz, chunkGridWidth, chunkGridHeight;
    /**
     * Transient lookup index over spaceAroundRecords: rebuilt lazily after load, dropped on retirement.
     */
    private transient volatile SpaceAroundGrid spaceAroundGrid = null;
    /**
     * Serializes spaceAround check+register for every zone nested under this (top-level) instance.
     * Transient: Gson skips it, and the no-arg constructor Gson uses still runs this initializer.
     */
    public final transient Object spaceAroundLock = new Object();
    @Nullable
    public Map<String, Integer> structurePlacementCounts = null;
    @Nullable
    public Map<String, Integer> structureGuaranteeAttempts = null;
    /**
     * spaceAround footprints, kept on TOP-LEVEL (root) instances only.
     */
    @Nullable
    public Queue<SpaceAroundRecord> spaceAroundRecords = null;

    /**
     * Lazily builds (or returns) this root zone's spaceAround index. Null once retired.
     */
    public SpaceAroundGrid ensureSpaceAroundGrid() {
        SpaceAroundGrid grid = spaceAroundGrid;
        if (grid != null) {
            return grid;
        }
        synchronized (spaceAroundLock) {
            synchronized (this) {
                if (spaceAroundRetired) {
                    return null;
                }
                grid = spaceAroundGrid;
                if (grid == null) {
                    grid = new SpaceAroundGrid();
                    Queue<SpaceAroundRecord> records = spaceAroundRecords;
                    if (records != null) {
                        for (SpaceAroundRecord record : records) {
                            grid.add(record);
                        }
                    }
                    spaceAroundGrid = grid;
                }
                return grid;
            }
        }
    }

    /**
     * Caller holds spaceAroundLock. Atomic with retirement, so nothing lands in a retired zone.
     */
    public synchronized boolean addSpaceAroundRecordIfActive(SpaceAroundRecord record) {
        if (spaceAroundRetired) {
            return false;
        }
        Queue<SpaceAroundRecord> records = spaceAroundRecords;
        if (records == null) {
            records = new ConcurrentLinkedQueue<>();
            spaceAroundRecords = records;
        }
        records.add(record);
        SpaceAroundGrid grid = spaceAroundGrid;
        if (grid != null) {
            grid.add(record);
        }
        return true;
    }

    /**
     * All four corners inside the circle -> the whole XZ box is inside.
     */
    public boolean containsBox(int minX, int minZ, int maxX, int maxZ) {
        return contains(minX, minZ) && contains(maxX, minZ) && contains(minX, maxZ) && contains(maxX, maxZ);
    }

    /**
     * Circle-vs-AABB overlap on XZ.
     */
    public boolean intersectsBox(int minX, int minZ, int maxX, int maxZ) {
        int closestX = Math.max(minX, Math.min(centerX, maxX));
        int closestZ = Math.max(minZ, Math.min(centerZ, maxZ));
        return contains(closestX, closestZ);
    }

    public synchronized Map<String, Integer> initStructurePlacementCounts() {
        Map<String, Integer> map = structurePlacementCounts;
        if (map == null) {
            map = new ConcurrentHashMap<>();
            structurePlacementCounts = map;
        }
        return map;
    }

    public synchronized Map<String, Integer> initGuaranteeAttempts() {
        Map<String, Integer> map = structureGuaranteeAttempts;
        if (map == null) {
            map = new ConcurrentHashMap<>();
            structureGuaranteeAttempts = map;
        }
        return map;
    }

    /**
     * Marks one chunk of this zone as generated. Idempotent: re-generating the same chunk never
     * advances the count. Once every in-circle chunk has generated, nothing can be placed in this
     * zone again, so all of its placement bookkeeping is released.
     */
    public synchronized int noteChunkGenerated(int chunkX, int chunkZ) {
        if (spaceAroundRetired || !ensureChunkGrid()) {
            return ChunkInZoneLoadingStatus.UNCHANGED.value;
        }
        int ix = chunkX - chunkGridMinCx;
        int iz = chunkZ - chunkGridMinCz;
        if (ix < 0 || iz < 0 || ix >= chunkGridWidth || iz >= chunkGridHeight) {
            return ChunkInZoneLoadingStatus.UNCHANGED.value;
        }
        int bit = iz * chunkGridWidth + ix;
        long mask = 1L << (bit & 63);
        int word = bit >>> 6;
        if ((generatedChunkBits[word] & mask) != 0L) {
            return ChunkInZoneLoadingStatus.UNCHANGED.value; // already counted
        }
        generatedChunkBits[word] |= mask;
        generatedChunks++;
        if (generatedChunks < totalChunksInZone()) {
            return ChunkInZoneLoadingStatus.COUNTED.value;
        }
        retire();
        return ChunkInZoneLoadingStatus.RETIRED.value;
    }

    /**
     * Number of chunk columns whose CENTRE (the point placement is gated on) is inside the
     * circle. Counted row by row, so cost scales with radius/16.
     */
    public synchronized int totalChunksInZone() {
        if (totalChunks >= 0) {
            return totalChunks;
        }
        long r = radius;
        long count = 0;
        int minCz = Math.floorDiv(centerZ - radius - 8, 16);
        int maxCz = Math.floorDiv(centerZ + radius - 8, 16);
        for (int cz = minCz; cz <= maxCz; cz++) {
            long dz = (long) cz * 16 + 8 - centerZ;
            long remaining = r * r - dz * dz;
            if (remaining < 0) {
                continue;
            }
            double half = Math.sqrt((double) remaining);
            long lowCx = (long) Math.ceil((centerX - half - 8) / 16.0);
            long highCx = (long) Math.floor((centerX + half - 8) / 16.0);
            if (highCx >= lowCx) {
                count += highCx - lowCx + 1;
            }
        }
        totalChunks = (int) Math.min(count, Integer.MAX_VALUE);
        return totalChunks;
    }

    public int getStructurePlacementCount(String id) {
        return structurePlacementCounts == null ? 0 : structurePlacementCounts.getOrDefault(id, 0);
    }

    public int getGuaranteePlacementAttemptsCount(String id) {
        return structureGuaranteeAttempts == null ? 0 : structureGuaranteeAttempts.getOrDefault(id, 0);
    }

    public boolean contains(int blockX, int blockZ) {
        long dx = blockX - centerX;
        long dz = blockZ - centerZ;
        long r = radius;
        return dx * dx + dz * dz <= r * r;
    }

    /**
     * Gson writes plain collections into the fields; swap them for concurrent ones before any
     * worker thread can see this instance.
     */
    public void normalizeCollectionsAfterLoad() {
        if (structurePlacementCounts != null) {
            structurePlacementCounts = new ConcurrentHashMap<>(structurePlacementCounts);
        }
        if (structureGuaranteeAttempts != null) {
            structureGuaranteeAttempts = new ConcurrentHashMap<>(structureGuaranteeAttempts);
        }
        if (spaceAroundRecords != null) {
            spaceAroundRecords = new ConcurrentLinkedQueue<>(spaceAroundRecords);
        }
    }

    private void retire() {
        spaceAroundRetired = true;
        structurePlacementCounts = null;
        structureGuaranteeAttempts = null;
        generatedChunkBits = null;
        spaceAroundRecords = null;
        spaceAroundGrid = null;
        /*
            Recorded /locate positions are not held here at all: they live in the dimension's own
            bucket files, so retirement has nothing of theirs to release.
        */
    }

    /**
     * False if this zone is too large to track, in which case it never retires.
     */
    private boolean ensureChunkGrid() {
        if (!chunkGridReady) {
            chunkGridMinCx = Math.floorDiv(centerX - radius - 8, 16);
            chunkGridMinCz = Math.floorDiv(centerZ - radius - 8, 16);
            chunkGridWidth = Math.floorDiv(centerX + radius - 8, 16) - chunkGridMinCx + 1;
            chunkGridHeight = Math.floorDiv(centerZ + radius - 8, 16) - chunkGridMinCz + 1;
            chunkGridReady = true;
        }
        long bits = (long) chunkGridWidth * chunkGridHeight;
        if (bits <= 0 || bits > MAX_TRACKED_CHUNK_BITS) {
            return false;
        }
        int words = (int) ((bits + 63) >>> 6);
        if (generatedChunkBits == null || generatedChunkBits.length != words) {
            // Absent, or the zone's geometry changed under a hand-edited save: start over rather
            // than index into a mismatched bitmap.
            generatedChunkBits = new long[words];
            generatedChunks = 0;
        }
        return true;
    }
}
