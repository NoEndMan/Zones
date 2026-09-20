package net.flameslight.zones.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.flameslight.zones.ZoneManager;
import net.flameslight.zones.Zones;
import net.flameslight.zones.logger.ModLogger;
import net.flameslight.zones.types.zoneDefinition.ZoneDefinition;
import net.flameslight.zones.types.zoneDefinition.ZoneDefinitionParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The configuration ONE world actually runs on, and whether this mod touches that world at all.
 *
 * When a world is CREATED with this mod installed, whatever is in config/zones/ at that moment is
 * copied into <world>/data/zones/zone-config.json, and that copy becomes the world's permanent
 * configuration: later edits to common-config.toml or to zoneDefinitions/ never change an existing
 * world's zone layout, densities, maxCounts or spacing.
 */
public final class WorldZoneConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "zone-config.json";
    private static final int FORMAT_VERSION = 1;

    private static final Snapshot EMPTY = new Snapshot(
            List.of(), Map.of(), Set.of(), List.of(),
            CommonConfig.DEFAULT_BASE_ZONE_PLACEMENT_TRIES,
            CommonConfig.DEFAULT_ZONE_STEP_DIFFERENCE,
            1f / ((float) CommonConfig.DEFAULT_STRUCTURE_DENSITY_SPACING
                    * CommonConfig.DEFAULT_STRUCTURE_DENSITY_SPACING));

    private static volatile Snapshot ACTIVE = EMPTY;
    private static volatile boolean managed = false;
    private static boolean loaded = false;


    public record Snapshot(List<ZoneDefinition> definitions,
                           Map<String, ZoneDefinition> byId,
                           Set<String> zoneOnlyStructures,
                           List<String> spawnWhitelist,
                           int baseZonePlacementTries,
                           int zoneStepDifference,
                           float densityBaseChance) {}

    // ---- Lifecycle ----

    /**
     * Idempotent per server session. Must run before ZoneManager.loadOrGenerate for any dimension;
     * returns whether this mod may do anything at all in this world.
     */
    public static synchronized boolean loadOrCreate(ServerLevel level) {
        ModLogger.debug("loadOrCreate for {}: isInitialized={}", level.dimension().location(),
                ((ServerLevelData) level.getLevelData()).isInitialized());

        if (loaded) {
            return managed;
        }
        loaded = true;

        Path file = configFile(level);

        if (Files.isRegularFile(file)) {
            Snapshot snapshot = read(file);
            if (snapshot != null) {
                managed = true;
                ACTIVE = snapshot;
                ZoneManager.onDefinitionsChanged();
                ModLogger.info("Using this world's saved zones configuration ({} zone definition(s)).",
                        snapshot.definitions().size());
                return true;
            }
            // The file exists but is unreadable, so the world IS ours; its zone instances still
            // reference definition ids by name and would be meaningless without any definitions.
            // Re-freezing from the live config keeps them working, but is not guaranteed to match
            // what this world was originally created with.
            ModLogger.warn("This world's saved zones configuration is unreadable; re-saving from the "
                    + "current config. Existing zone instances keep their positions, but their structure "
                    + "rules now come from whatever config/zones/ holds right now.");
            return freeze(file);
        }

        // No saved config. isInitialized() is false ONLY while a world is being created, so this
        // separates "brand-new world" from "world that existed before the mod was installed".
        if (((ServerLevelData) level.getLevelData()).isInitialized()) {
            ModLogger.info("This world was created without the zones mod installed. it is left completely "
                    + "untouched, and no zones will ever generate in it.");
            managed = false;
            ACTIVE = EMPTY;
            return false;
        }

        return freeze(file);
    }

    /** Called from ZoneManager.resetSessionState, so a second world in the same session re-decides. */
    public static synchronized void reset() {
        ACTIVE = EMPTY;
        managed = false;
        loaded = false;
    }

    // ---- Query API (hot path) ----

    /** False -> this mod applies no logic whatsoever to the loaded world. */
    public static boolean isManagedWorld() {
        return managed;
    }

    public static List<ZoneDefinition> getParsedDefinitions() {
        return ACTIVE.definitions();
    }

    public static ZoneDefinition findDefinition(String zoneId) {
        return zoneId == null ? null : ACTIVE.byId().get(zoneId);
    }

    public static Set<String> getZoneOnlyStructures() {
        return ACTIVE.zoneOnlyStructures();
    }

    public static List<String> getSpawnWhitelist() {
        return ACTIVE.spawnWhitelist();
    }

    public static int baseZonePlacementTries() {
        return ACTIVE.baseZonePlacementTries();
    }

    public static int zoneStepDifference() {
        return ACTIVE.zoneStepDifference();
    }

    public static float densityBaseChance() {
        return ACTIVE.densityBaseChance();
    }

    // ---- Snapshot building ----

    private static boolean freeze(Path file) {
        CommonConfig.forceReloadFromDisk();
        StoredConfig stored = snapshotLiveConfig();
        Snapshot snapshot = build(stored);

        managed = true;
        ACTIVE = snapshot;
        ZoneManager.onDefinitionsChanged();

        write(file, stored);
        ModLogger.debug("Froze {} zone definition(s) into {}. this world keeps using this configuration "
                + "regardless of any later config edits.", snapshot.definitions().size(), file);
        return true;
    }

    private static StoredConfig snapshotLiveConfig() {
        StoredConfig stored = new StoredConfig();
        stored.version = FORMAT_VERSION;
        stored.baseZonePlacementTries = CommonConfig.BASE_ZONE_TRIES_PER_RING.get();
        stored.zoneStepDifference = CommonConfig.ZONE_STEP_DIFFERENCE.get();
        stored.structureDensitySpacing = CommonConfig.STRUCTURE_DENSITY_SPACING.get();
        stored.zoneOnlyStructures = new ArrayList<>(CommonConfig.ZONE_ONLY_STRUCTURES.get());
        stored.zoneSpawnWhitelist = new ArrayList<>(CommonConfig.ZONE_SPAWN_WHITELIST.get());
        stored.zoneDefinitions = ZoneDefinitionFiles.readAll();
        return stored;
    }

    private static Snapshot build(StoredConfig stored) {
        List<String> raws = stored.zoneDefinitions == null ? List.of() : stored.zoneDefinitions;
        List<ZoneDefinition> defs = new ArrayList<>(raws.size());
        Map<String, ZoneDefinition> byId = new HashMap<>(Math.max(4, raws.size() * 2));

        for (String raw : raws) {
            ZoneDefinition def = ZoneDefinitionParser.parse(raw);
            if (def == null) {
                continue;
            }
            ZoneDefinition previous = byId.put(def.id, def);
            if (previous != null) {
                ModLogger.debug("Two zone definitions share the id '{}', so the later one wins.", def.id);
                defs.remove(previous);
            }
            defs.add(def);
        }

        // Pass 1: child zones inherit their TOP-LEVEL zone's 'biomes' (a child is always centered
        // on its parent, so a list of its own could never affect placement, it only drives biome
        // widening), and collect which ids are named as a parent.
        Set<String> parentIds = new HashSet<>();
        for (ZoneDefinition def : defs) {
            if (def.parentZone == null || def.parentZone.isEmpty()) {
                continue;
            }
            def.biomes = topLevelOf(def, byId).biomes;
            if (byId.containsKey(def.parentZone)) {
                parentIds.add(def.parentZone);
            }
        }

        // Pass 2: everything that needs pass 1's parentIds. Definitions are immutable per snapshot,
        // so these derived flags are computed once here.
        for (ZoneDefinition def : defs) {
            def.hasChildZones = parentIds.contains(def.id);
            for (ZoneDefinition.StructureEntry entry : def.structures) {
                if (entry == null) {
                    continue;
                }
                // Interned so findStructure's equals() hits the identity fast path against the
                // interned ids ZoneManager.idString hands out on the placement hot path.
                if (entry.id != null) {
                    entry.id = entry.id.intern();
                }
                if (entry.spaceAround > 0) {
                    topLevelOf(def, byId).treeHasSpaceAround = true;
                }
                if (entry.blockLeakingIntoNestedZones && !def.hasChildZones) {
                    ModLogger.debug("Zone '{}': blockLeakingIntoNestedZones on '{}', ignored! no zone "
                            + "names '{}' as its parentZone.", def.id, entry.id, def.id);
                }
            }
        }

        int spacing = Math.min(256, Math.max(4, stored.structureDensitySpacing));
        return new Snapshot(
                List.copyOf(defs),
                Map.copyOf(byId),
                stored.zoneOnlyStructures == null ? Set.of() : Set.copyOf(stored.zoneOnlyStructures),
                stored.zoneSpawnWhitelist == null ? List.of() : List.copyOf(stored.zoneSpawnWhitelist),
                Math.max(1, stored.baseZonePlacementTries),
                Math.max(1, stored.zoneStepDifference),
                1f / ((float) spacing * spacing));
    }

    /** Walks the parentZone chain to the top-level zone (cycle-safe; stops at a missing parent). */
    private static ZoneDefinition topLevelOf(ZoneDefinition def, Map<String, ZoneDefinition> byId) {
        ZoneDefinition current = def;
        Set<String> seen = new HashSet<>();
        while (current.parentZone != null && !current.parentZone.isEmpty() && seen.add(current.id)) {
            ZoneDefinition parent = byId.get(current.parentZone);
            if (parent == null) {
                break;
            }
            current = parent;
        }
        return current;
    }

    // ---- Persistence ----

    private static Path configFile(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve(Zones.MOD_ID).resolve(FILE_NAME);
    }

    private static Snapshot read(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StoredConfig stored = GSON.fromJson(reader, StoredConfig.class);
            if (stored == null) {
                return null;
            }
            if (stored.version > FORMAT_VERSION) {
                ModLogger.warn("{} was written by a newer version of this mod (format {}); reading it anyway.",
                        file, stored.version);
            }
            return build(stored);
        } catch (Exception e) {
            ModLogger.error("Could not read this world's saved zones configuration from {}", file, e);
            return null;
        }
    }

    private static void write(Path file, StoredConfig stored) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(stored, writer);
            }
        } catch (IOException e) {
            ModLogger.error("Could not write this world's saved zones configuration to {}; it will be "
                    + "re-created from the live config on the next load.", file, e);
        }
    }

    /** On-disk shape only: built, written and thrown away; never held while the world runs. */
    private static final class StoredConfig {
        int version = FORMAT_VERSION;
        int baseZonePlacementTries;
        int zoneStepDifference;
        int structureDensitySpacing;
        List<String> zoneOnlyStructures;
        List<String> zoneSpawnWhitelist;
        List<String> zoneDefinitions;
    }
}
