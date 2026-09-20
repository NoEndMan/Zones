package net.flameslight.zones.types.zoneDefinition;

public enum FlattenMode {
    OFF,
    /** Outlier-resistant target, never below sea level for a normal, dry flat zone. */
    SURFACE,
    /** Lowest sampled point, no sea-level clamp to lets a zone flatten below water. */
    UNDERWATER_SURFACE;

    public boolean isEnabled() {
        return this != OFF;
    }
}
