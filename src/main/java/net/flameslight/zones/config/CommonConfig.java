package net.flameslight.zones.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mojang.logging.LogUtils;
import net.flameslight.zones.Zones;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class CommonConfig {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final boolean DEFAULT_DEBUG_MOD = false;
    public static final int DEFAULT_STRUCTURE_DENSITY_SPACING = 20;
    public static final int DEFAULT_BASE_ZONE_PLACEMENT_TRIES = 30;
    public static final int DEFAULT_ZONE_STEP_DIFFERENCE = 256;

    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec.IntValue BASE_ZONE_TRIES_PER_RING;
    public static final ForgeConfigSpec.IntValue ZONE_STEP_DIFFERENCE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ZONE_SPAWN_WHITELIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ZONE_ONLY_STRUCTURES;
    public static final ForgeConfigSpec.IntValue STRUCTURE_DENSITY_SPACING;
    public static final ForgeConfigSpec.BooleanValue DEBUG_MOD;

    private static volatile boolean isDebugMod = DEFAULT_DEBUG_MOD;
    private static volatile boolean isDebugModResolved = false;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment(
                "Zones configuration.",
                "zoneDefinitions folder:",
                "Each file in 'zoneDefinitions' folder is a single zone TYPE, in a compact key:value format:",
                "  id:myZone,radius:300,count:2,structures:[{id:modid:structure_name,density:1.0}],biomes:[minecraft:plains]",
                "",
                "A zone representing an area with different world generation rules. It could mean for example,",
                "an area with different biome or an area with structures placed with unique placement rules.",
                "Top-level zones (zones that have no parentZone configured, see below) are placed randomly",
                "across the whole world. Read here for per-zone fields usage and examples.",
                "IMPORTANT: this whole configuration, every setting below AND every zone definition file,",
                "is FROZEN into <world>/data/zones/zone-config.json when a world is first created. Editing",
                "this file or the zoneDefinitions folder afterward only affects NEW worlds. To change the",
                "rules of an existing world, edit the matching field inside that world's own",
                "data/zones/zone-config.json instead.",
                "",
                "zone-level fields:",
                "  id (REQUIRED) - the zone's own id, referenced by parentZone and zoneSpawnWhitelist. An",
                "    entry with no id (or an empty one) is skipped entirely and logged as a warning.",
                "    Example: id:dungeon_world",
                "  radius (optional) - horizontal radius in blocks. If omitted: defaults to 64.",
                "    Example: radius:300",
                "  count (optional) - how many independent instances to place. If omitted: unlimited",
                "    (as many as fit given biome/spacing/density constraints). Ignored entirely if",
                "    parentZone is set: one child is always placed per parent instance instead.",
                "    Example: count:4",
                "  density (optional) - if omitted: defaults to 1.0 (not doing anything), must be a positive number.",
                "    Controls how RARE this zone type is. At 1.0 or above, every search ring is attempted with",
                "    (baseZonePlacementTries * density) candidate points, so larger values pack zone instances",
                "    closer together. BELOW 1.0 two things happen together: first, some rings are skipped entirely.",
                "    Secondly, each candidate point is attempted by a random chance equal to the density. The",
                "    result is that instances end up much further apart the lower this goes.",
                "    If a zones spawns too close to the spawn, use minDistanceFromSpawn to push",
                "    zones further from the world spawn. Ignored entirely if parentZone is set.",
                "    Example: density:0.25",
                "  structures (optional) - list of {id:...,density:...,...} entries, each representing a structure",
                "    to be placed inside the zone perimeter. See below for per-structure fields usage and examples",
                "    If omitted or given as an empty list []: the zone doesn't accept any structures.",
                "    if obeyParent is enabled and has a parent - it would inherit structures entries from a parent zone.",
                "    you may re-define a structure entry to overwrite a parent inherited one for this zone.",
                "    Example: structures:[{id:minecraft:mineshaft,density:20.0}]",
                "  biomes (optional) - list of biome ids and/or biome tags the zone CENTER may spawn in.",
                "    Use '#' prefix for a biome tag. If omitted or empty: the center may land in ANY biome.",
                "    While inside the zone, every structure registered for the zone may also generate on any biome in",
                "    this list, even biomes that structure normally rejects. If the list is empty, no such",
                "    biome widening happening. Ignored (with a warning) if parentZone is set: a child zone is always",
                "    centered on its parent and inherits its TOP-LEVEL zone's biomes instead.",
                "    Example: biomes:[minecraft:desert,#minecraft:is_overworld]",
                "  parentZone (optional) - id of another zone. If omitted: this is a top-level zone placed",
                "    independently. If set: one instance of this zone is centered on every instance of the",
                "    named parent instead of being placed independently, and 'count'/'density' are ignored.",
                "    Example: parentZone:dungeon_world",
                "  dimension (optional) - if omitted: defaults to minecraft:overworld.",
                "    Example: dimension:minecraft:the_nether",
                "  minDistanceFromOtherZones (optional) - extra gap (blocks), on top of both zones' own",
                "    radiuses, enforced between TOP-LEVEL zones only. If omitted: defaults to 0. Please be",
                "    aware that even at 0, this still keeps zones from overlapping, since it's added to both",
                "    radiuses regardless. Nested (parentZone) zones are always exempt.",
                "    Example: minDistanceFromOtherZones:200",
                "  minDistanceFromSpawn (optional, per zone) - minimum distance (blocks) from the world",
                "    origin a TOP-LEVEL zone's center may be placed at. If omitted: no minimum enforced.",
                "    Example: minDistanceFromSpawn:5000",
                "  ensureBiomeForTheWholeZone (optional) - disabled if omitted. If true:",
                "    forces the zone's own center biome to read as the biome for the whole zone circle,",
                "    only for the terrain surface - never affects biome underground.",
                "    May instead be a biome id, forcing that biome rather than the center one.",
                "    Ignored for a child zone if any of its parent zones already enables it (the parent's",
                "    forced biome already covers the child zones too).",
                "    Example 1#: ensureBiomeForTheWholeZone:true",
                "    Example 2#: ensureBiomeForTheWholeZone:minecraft:plains",
                "  shouldFlattenTerrain (optional) - either 'surface', 'underwater_surface' or 'off'. if omitted:",
                "    defaults to off. If true: Samples 30 random points inside the zone at generation time and flattens",
                "    the whole zone to one Y BEFORE surface/carvers/features/structures run, so everything in the zone",
                "    (terrain and structures alike) ends up on one consistent level.",
                "    'surface' picks a low percentile of those samples and never goes below sea level, so a",
                "    single point on a seabed or in a ravine can't sink the whole zone into a pit. Use this",
                "    to get a smooth flat terrain.",
                "    'underwater_surface' uses the LOWEST sample with no sea-level limit, which lets a zone",
                "    flatten below water. Only use it for zones meant to sit underwater or in a dug-out basin.",
                "    This option is ignored for a child zone if any of its parent zones already flattens it",
                "    (the parent's flattened Y already covers the child).",
                "    Example: shouldFlattenTerrain:surface",
                "  obeyParent (optional) - if omitted: defaults to false/off, meaning this zone only ever",
                "    sees its OWN 'structures' list. If true: any structure id NOT in this zone's own",
                "    'structures' list falls back to the parent zone's entry for that id (inheriting its",
                "    density/maxCount/spaceAround/etc.), while an entry in this zone's own list fully",
                "    replaces the parent's entry for that id. Ignored if this zone has no parentZone.",
                "    Example: obeyParent:true",
                "",
                "per-structure fields (in 'structures: [{...}]'):",
                "  id (REQUIRED) - an entry with no id is skipped and logged as a warning. A warning is",
                "    also logged if the id doesn't match any known structure (typo, or that structure's",
                "    own mod isn't installed).",
                "    Example: id:minecraft:desert_pyramid",
                "  density (optional) - if omitted: defaults to 1.0 (unchanged).",
                "    Divided by baseDensity the final density result to get this",
                "    zone's own search-region size in blocks (region side = baseDensity * density)",
                "    Example: density:20.0",
                "  guaranteePlacement (optional) - if omitted: defaults to false/off. If true: forces",
                "    this structure eligible outright wherever the zone's own chunks are generating,",
                "    bypassing vanilla's own spacing grid, until one copy has placed. Gives up (and logs",
                "    a warning) after 100 failed attempts.",
                "    Example: guaranteePlacement:true",
                "  maxCount (optional) - if omitted: defaults to 0, which allows unlimited copies of this structure",
                "    per zone instance. If set: further copies are rejected once that many have been placed.",
                "    Example: maxCount:1",
                "  spaceAround (optional) - if omitted: defaults to 0, which makes no extra spacing enforcing beyond",
                "    vanilla's own. If set: In short - used to keep structures to be placed too close/inside",
                "    each other inside the zone boundaries. Happening by keeping a structure from generating",
                "    within, its own footprint + this many blocks in every direction, of ANY other structure,",
                "    and keeps any OTHER structure from generating within that area around THIS one, after",
                "    it was placed. For jigsaw structures (villages, ancient cities, etc.) this works PER PIECE: each",
                "    piece keeps that distance from other structures' pieces while assembling around each other.",
                "    while assembling around each other. Pieces lying fully outside the top-level zone are ignored.",
                "    Please be aware: if configured for a jigsaw structure, it may cause it to not generate in fully",
                "    if they do not have the required spacing to do so.",
                "    Example: spaceAround:32",
                "  spreadDistance (optional) - if omitted: defaults to 0, which disables this option.",
                "    Represents a cell size, in blocks, for spreading copies of THIS structure apart inside",
                "    a zone. The zone is divided into cells of this size and each cell gets one pseudo-random",
                "    candidate position, so copies of the same structure scatter across the zone instead of clustering.",
                "    Seed-based: the same world seed and config always produce the same layout.",
                "    Larger values spread same structure types further apart.",
                "    Example: spreadDistance:128",
                "  blockLeakingOutsideZone (optional) - if omitted: defaults to false. If true: no part of",
                "    this structure may lie outside the zone it starts in. For a nested zone, that also keeps",
                "    it out of the parent zone's area around it.",
                "    Jigsaw structures (villages, ancient cities, etc.) are TRIMMED while they assemble: any",
                "    piece that would cross the edge is dropped and that branch ends there, so the structure",
                "    still generates, just smaller. Every other structure has a fixed shape with nothing to",
                "    trim, so it is REJECTED outright and simply doesn't generate at that spot.",
                "    This feature is meant for more restrictive placing rules for structures",
                "    Please be aware: for jigsaw structures, it may cause them to not generate in fully if they do not",
                "    have the required spacing to do so.",
                "    Example: blockLeakingOutsideZone:true",
                "  blockLeakingIntoNestedZones (optional) - if omitted: defaults to false. If true: no part of",
                "    this structure may enter an inner zone nested inside this one that doesn't itself allow",
                "    the structure, by having it in its own 'structures' list or inheriting it via obeyParent.",
                "    Ignored if no zone names this zone as its parentZone.",
                "    Trimmed for jigsaw structures and rejected for everything else, exactly as",
                "    blockLeakingOutsideZone above, with the same caveats.",
                "    Example: blockLeakingIntoNestedZones:true",
                "",
                "Unknown fields and malformed structure entries are logged and skipped.",
                "check the log for warnings if a zone isn't behaving as configured.",
                "",
                "Full example combining several options:",
                "  id:dungeon_world,radius:512,count:4,density:0.5,minDistanceFromOtherZones:200,",
                "  minDistanceFromSpawn:2000,structures:[{id:minecraft:mineshaft,density:20.0}]",
                "  id:dungeon_world_heart,parentZone:dungeon_world,radius:48,obeyParent:true,",
                "  structures:[{id:minecraft:stronghold,guaranteePlacement:true,maxCount:1,spaceAround:32}]",
                ""
        ).push("general zones configuration");

        STRUCTURE_DENSITY_SPACING = builder
                .comment("Reference spacing (chunks) used to convert a structure's density value above 1.0",
                        "into an extra per-chunk attempt chance. Smaller values make each unit of density",
                        "above 1.0 stronger.")
                .defineInRange("structureDensitySpacing", DEFAULT_STRUCTURE_DENSITY_SPACING, 4, 256);

        BASE_ZONE_TRIES_PER_RING = builder
                .comment("Base number of times to place all zones per region, affecting their rarity of zones after multiplied",
                        "by each zone density: larger values would cause all zones to be commoner and closer to",
                        "each other, where smaller values would cause all zones to be rarer and farther from each other.",
                        "Default is 15 tries per region (region size is affected by zone step)")
                .defineInRange("baseZonePlacementTries", DEFAULT_BASE_ZONE_PLACEMENT_TRIES, 1, 1000);

        ZONE_STEP_DIFFERENCE = builder
                .comment("Default to 256. Fixed distance, in blocks, between each top-level zone search ring:",
                        "ring 1 sits this many blocks from world origin, ring 2 at 2x that distance, ring 3 at 3x,",
                        "and so on. Nothing is ever placed at the world origin itself; Use this option to",
                        "increase or lower all zones grouping and rarity. Must be a positive whole number.")
                .defineInRange("zoneStepDifference", DEFAULT_ZONE_STEP_DIFFERENCE, 1, 50_000_000);

        ZONE_SPAWN_WHITELIST = builder
                .comment("Zone ids, in priority order. If non-empty, the world's initial spawn point is forced",
                        "to land inside the first listed id that has a generated instance in the Overworld —",
                        "checked ONLY on brand-new worlds. Existing worlds and server restarts are never touched,",
                        "even if you edit this list afterward. Empty by default (vanilla spawn behavior).",
                        "Example: zoneSpawnWhitelist = [\"dungeon_world\"]")
                .defineList("zoneSpawnWhitelist", new ArrayList<>(), o -> o instanceof String);

        ZONE_ONLY_STRUCTURES = builder
                .comment("Structure ids that are ONLY allowed to generate inside a zone that registers them",
                        "(the old, always-on behavior). A structure assigned to a zone's 'structures' list but",
                        "NOT listed here is still gated by that zone's own density/maxCount/guaranteePlacement",
                        "rules WHILE inside the zone, but is also free to generate completely normally anywhere",
                        "outside it, unaffected.",
                        "Frozen per world: to change this for a world that already exists, edit the",
                        "'zoneOnlyStructures' field in that world's data/zones/zone-config.json.",
                        "Example: zoneOnlyStructures = [\"minecraft:mineshaft\",\"minecraft:stronghold\"]")
                .defineList("zoneOnlyStructures", List.of("minecraft:stronghold"), o -> o instanceof String);

        DEBUG_MOD = builder
                .comment("Enables this mod's own [DEBUG] log lines.")
                .define("debugMod", DEFAULT_DEBUG_MOD);

        builder.pop();
        COMMON_SPEC = builder.build();
    }

    /**
     * Forces the backing TOML file to be re-read from disk right now (see ClientEvents), rather
     * than waiting on Forge's own async file watcher, then invalidates our caches.
     */
    public static synchronized void forceReloadFromDisk() {
        try {
            ModConfig modConfig = ConfigTracker.INSTANCE.configSets().get(ModConfig.Type.COMMON).stream()
                    .filter(mc -> mc.getModId().equals(Zones.MOD_ID))
                    .findFirst()
                    .orElse(null);
            if (modConfig == null) {
                LOGGER.warn("Could not locate the zones common config to force-reload it before world "
                        + "creation; it will still pick up changes on its own once Forge's file watcher notices the edit.");
                return;
            }
            Object configData = modConfig.getConfigData();
            if (configData instanceof CommentedFileConfig fileConfig) {
                fileConfig.load();
            }

            // Reloading the backing file only refreshes the raw key/value data. each
            // ForgeConfigSpec.ConfigValue caches its OWN already-parsed value internally and won't
            // pick up the change until told to drop that cache too.
            ZONE_SPAWN_WHITELIST.clearCache();
            ZONE_ONLY_STRUCTURES.clearCache();
            STRUCTURE_DENSITY_SPACING.clearCache();
            BASE_ZONE_TRIES_PER_RING.clearCache();
            ZONE_STEP_DIFFERENCE.clearCache();
            DEBUG_MOD.clearCache();

            isDebugModResolved = false;

            LOGGER.debug("Reloaded zones config from disk before world creation.");
        } catch (Exception e) {
            LOGGER.warn("Failed to force-reload the zones config before world creation; proceeding with "
                    + "whatever was already loaded.", e);
        }
    }

    /**
     * Safe at any point in the lifecycle: returns the default until the config file has actually
     * been loaded, rather than throwing the way ConfigValue.get() does.
     */
    public static boolean isDebugMod() {
        if (!isDebugModResolved) {
            if (!COMMON_SPEC.isLoaded()) {
                return DEFAULT_DEBUG_MOD;
            }
            isDebugMod = DEBUG_MOD.get();
            isDebugModResolved = true;
        }

        return isDebugMod;
    }
}
