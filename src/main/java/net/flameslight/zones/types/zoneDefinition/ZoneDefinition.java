package net.flameslight.zones.types.zoneDefinition;

import net.flameslight.zones.types.BiomeMatcher;

import java.util.List;

public class ZoneDefinition {
    public String id;
    public int radius = 64;
    public int count = Integer.MAX_VALUE;
    public float density = 1.0f;
    public List<StructureEntry> structures = List.of();
    public List<BiomeMatcher> biomes = List.of();
    public String parentZone = null;
    public String dimension = "minecraft:overworld";
    public int minDistanceFromOtherZones = 0;
    public int minDistanceFromSpawn = 0;
    public boolean ensureBiomeForTheWholeZone = false;
    public FlattenMode shouldFlattenTerrain = FlattenMode.OFF;
    public boolean obeyParent = false;
    public boolean hasChildZones = false;
    /** Set by ZoneManager.recomputeDerivedState: does anything in this zone's own structure list
     *  produce per-instance data that retirement could free? */
    public boolean hasReclaimableData = false;
    /** Set by WorldZoneConfig.build, on TOP-LEVEL definitions only: some entry anywhere in this
     *  zone tree has spaceAround > 0. False lets spaceAround skip its lock and index entirely. */
    public boolean treeHasSpaceAround = false;

    public StructureEntry findStructure(String structureId) {
        for (StructureEntry entry : structures) {
            if (entry != null && structureId.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }

    public static class StructureEntry {
        public String id;
        public float density = 1.0f;
        public boolean guaranteePlacement = false;
        public int maxCount = 0;    // 0 = disabled (unlimited)
        public int spaceAround = 0; // 0 = disabled
        public int spreadDistance = 0;
        public boolean blockLeakingOutsideZone = false;
        public boolean blockLeakingIntoNestedZones = false;
    }
}
