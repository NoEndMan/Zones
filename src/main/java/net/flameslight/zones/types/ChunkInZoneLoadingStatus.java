package net.flameslight.zones.types;

public enum ChunkInZoneLoadingStatus {
    UNCHANGED(0), COUNTED(1), RETIRED(2);

    public final int value;

    ChunkInZoneLoadingStatus(int value) {
        this.value = value;
    }
}
