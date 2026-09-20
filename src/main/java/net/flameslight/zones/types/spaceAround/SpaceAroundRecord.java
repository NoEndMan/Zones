package net.flameslight.zones.types.spaceAround;


/**
 * One placed structure's spaceAround footprint, stored on its ROOT (top-level) zone instance.
 * Non-jigsaw structures are a single piece (their overall box); jigsaw structures store one box
 * per piece. Persisted with the zone data; never mutated after creation.
 */
public final class SpaceAroundRecord {
    public int spaceAround;
    /** 6 ints per piece: minX, minY, minZ, maxX, maxY, maxZ. */
    public int[] pieces;

    public static SpaceAroundRecord of(int[] pieces, int spaceAround) {
        SpaceAroundRecord r = new SpaceAroundRecord();
        r.pieces = pieces;
        r.spaceAround = spaceAround;
        return r;
    }
}
