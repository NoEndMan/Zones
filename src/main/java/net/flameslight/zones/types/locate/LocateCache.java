package net.flameslight.zones.types.locate;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Short-lived results of a LocateStore scan, so for example - an eye of ender, thrown repeatedly doesn't
 * re-read every bucket each time. Entries expire on their own and are dropped lazily on the next lookup,
 * and an append for a structure invalidates its entry immediately.
 *
 * Small and fully synchronized: it is touched a handful of times per session by /locate, plus once
 * per recorded placement for the invalidation.
 */
public final class LocateCache {
    private static final long TTL_MILLIS = 10_000L; // 10 seconds
    /** Bounds the list if something scripts /locate; oldest entries are evicted first. */
    private static final int MAX_ENTRIES = 32;

    private final List<Entry> entries = new ArrayList<>();

    private static final class Entry {
        final ResourceLocation dimension;
        final String structureId;
        final long[] positions;
        final long expiresAt;

        Entry(ResourceLocation dimension, String structureId, long[] positions, long expiresAt) {
            this.dimension = dimension;
            this.structureId = structureId;
            this.positions = positions;
            this.expiresAt = expiresAt;
        }
    }

    /** Null when nothing usable is cached; an empty array is a real cached answer. */
    public synchronized long[] get(ResourceLocation dimension, String structureId) {
        long now = System.currentTimeMillis();
        long[] hit = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.expiresAt <= now) {
                entries.remove(i);
                continue;
            }
            if (hit == null && entry.structureId.equals(structureId) && entry.dimension.equals(dimension)) {
                hit = entry.positions;
            }
        }
        return hit;
    }

    public synchronized void put(ResourceLocation dimension, String structureId, long[] positions) {
        invalidate(dimension, structureId);
        if (entries.size() >= MAX_ENTRIES) {
            entries.remove(0);
        }
        entries.add(new Entry(dimension, structureId, positions, System.currentTimeMillis() + TTL_MILLIS));
    }

    public synchronized void invalidate(ResourceLocation dimension, String structureId) {
        entries.removeIf(e -> e.structureId.equals(structureId) && e.dimension.equals(dimension));
    }

    public synchronized void clear(ResourceLocation dimension) {
        entries.removeIf(e -> e.dimension.equals(dimension));
    }

    public synchronized void clear() {
        entries.clear();
    }
}
