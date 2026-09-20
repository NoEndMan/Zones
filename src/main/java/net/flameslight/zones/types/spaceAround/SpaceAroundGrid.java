package net.flameslight.zones.types.spaceAround;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transient spatial index over one root zone's SpaceAroundRecords: 32-block XZ cells, each holding
 * copies of the piece boxes whose padded footprint touches it. Never persisted and rebuilt lazily
 * after load, dropped on retirement.
 *
 * Reads are lock-free (the jigsaw placer queries it thousands of times per village); writes happen
 * only under the owning root zone's spaceAroundLock and are copy-on-write per cell, so a reader
 * always sees a complete array.
 *
 * Cell entries are 7 ints: minX, minY, minZ, maxX, maxY, maxZ, spaceAround.
 */
public final class SpaceAroundGrid {
    private static final int KEY_X_SHIFT = 32;
    private static final int CELL_SHIFT = 5; // 32 blocks
    private static final int STRIDE = 7;
    /** Slot 0 of every cell array holds how many ints are actually in use. */
    private static final int HEADER = 1;
    private static final int INITIAL_ENTRIES = 4;

    private final ConcurrentHashMap<Long, int[]> cells = new ConcurrentHashMap<>();

    /** Caller holds the owning root zone's spaceAroundLock, so this is the only writer. The
     *  used-length in slot 0 is bumped last and the array re-published through the map, so a reader
     *  that fetches it afterward sees everything. */
    public void add(SpaceAroundRecord record) {
        int[] p = record.pieces;
        if (p == null) {
            return;
        }
        int pad = record.spaceAround;
        for (int i = 0; i + 5 < p.length; i += 6) {
            // Filed over the piece's PADDED footprint, so any candidate within `pad` of it lands in
            // a shared cell (see conflicts()).
            int cx0 = (p[i] - pad) >> CELL_SHIFT;
            int cz0 = (p[i + 2] - pad) >> CELL_SHIFT;
            int cx1 = (p[i + 3] + pad) >> CELL_SHIFT;
            int cz1 = (p[i + 5] + pad) >> CELL_SHIFT;
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    long key = key(cx, cz);
                    int[] cell = cells.get(key);
                    int used;
                    if (cell == null) {
                        cell = new int[HEADER + STRIDE * INITIAL_ENTRIES];
                        used = HEADER;
                    } else {
                        used = cell[0];
                        if (used + STRIDE > cell.length) {
                            cell = Arrays.copyOf(cell, Math.max(cell.length << 1, used + STRIDE));
                        }
                    }
                    cell[used] = p[i];
                    cell[used + 1] = p[i + 1];
                    cell[used + 2] = p[i + 2];
                    cell[used + 3] = p[i + 3];
                    cell[used + 4] = p[i + 4];
                    cell[used + 5] = p[i + 5];
                    cell[used + 6] = pad;
                    cell[0] = used + STRIDE;
                    cells.put(key, cell);
                }
            }
        }
    }

    /**
     * True if a candidate box is too close to any stored piece. "Stored piece padded by its own
     * spaceAround overlaps the candidate" OR "candidate padded by ownPad overlaps the stored piece"
     * is exactly "per-axis gap <= max(storedPad, ownPad)", so one test with the larger pad covers
     * both directions. Querying cells over the candidate padded by ownPad is sufficient because
     * stored pieces are filed over their own padded footprint.
     */
    public boolean conflicts(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int ownPad) {
        int cx0 = (minX - ownPad) >> CELL_SHIFT;
        int cz0 = (minZ - ownPad) >> CELL_SHIFT;
        int cx1 = (maxX + ownPad) >> CELL_SHIFT;
        int cz1 = (maxZ + ownPad) >> CELL_SHIFT;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                int[] e = cells.get(key(cx, cz));
                if (e == null) {
                    continue;
                }
                int used = e[0];
                for (int i = HEADER; i < used; i += STRIDE) {
                    int pad = Math.max(ownPad, e[i + 6]);
                    if (e[i + 3] + pad >= minX && e[i] - pad <= maxX
                            && e[i + 4] + pad >= minY && e[i + 1] - pad <= maxY
                            && e[i + 5] + pad >= minZ && e[i + 2] - pad <= maxZ) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << KEY_X_SHIFT) ^ (cz & 0xffffffffL);
    }
}
