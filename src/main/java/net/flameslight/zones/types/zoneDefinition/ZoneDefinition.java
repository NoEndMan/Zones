package net.flameslight.zones.types.zoneDefinition;

import net.flameslight.zones.types.BiomeMatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.MobSpawnSettings;

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
    /** Explicit biome id given as the ensureBiomeForTheWholeZone value; null = use the zone's center biome. */
    public ResourceLocation ensureBiomeId = null;
    public FlattenMode shouldFlattenTerrain = FlattenMode.OFF;
    public boolean obeyParent = false;
    public boolean hasChildZones = false;
    /** Set by ZoneManager.recomputeDerivedState: does anything in this zone's own structure list
     *  produce per-instance data that retirement could free? */
    public boolean hasReclaimableData = false;
    /** Set by WorldZoneConfig.build, on TOP-LEVEL definitions only: some entry anywhere in this
     *  zone tree has spaceAround > 0. False lets spaceAround skip its lock and index entirely. */
    public boolean treeHasSpaceAround = false;
    public List<MobEntry> mobs = List.of();
    /**
     * Set by WorldZoneConfig.build: this zone's effective 'mobs' (own entries, plus the parent's
     * via obeyParent) as ready-made spawn entries per category, empty if it has none. The same
     * instances are handed to every spawn attempt, which vanilla's re-check (canSpawnMobAt) needs.
     */
    public MobSpawnSettings.SpawnerData[][] zoneSpawns = new MobSpawnSettings.SpawnerData[MobCategory.values().length][];
    /**
     * Entity types in zoneSpawns: their biome entries are replaced inside this zone. A plain array:
     * a handful of entries compared by identity beats hashing through a Set on the spawn hot path.
     */
    public EntityType<?>[] zoneSpawnTypes = new EntityType<?>[0];

    /** This zone's entries for a category (indexed by MobCategory.ordinal()), or null if none. */
    public MobSpawnSettings.SpawnerData[] zoneSpawnsFor(MobCategory category) {
        return zoneSpawns[category.ordinal()];
    }

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

    public static class MobEntry {
        public ResourceLocation id;
        public int weight;
        public int minGroupSize = 1;
        public int maxGroupSize = 1;
    }
}
