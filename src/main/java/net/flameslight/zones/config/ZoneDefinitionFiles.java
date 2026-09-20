package net.flameslight.zones.config;

import net.flameslight.zones.Zones;
import net.flameslight.zones.logger.ModLogger;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Owns config/zones/zoneDefinitions/. One *.txt file per zone TYPE, File names are never read and
 * the zone's id comes from its own 'id' field.
 * Read exactly once per world creation (WorldZoneConfig freezes the result), so this is cold code:
 * it favours clear errors over speed and holds nothing after returning.
 */
public final class ZoneDefinitionFiles {
    private static final String FOLDER_NAME = "zoneDefinitions";
    private static final String GLOB = "*.txt";
    private static final String COMMENT_PREFIX = "//";

    /** Written once, only if the folder doesn't exist at all. */
    private static final String[][] DEFAULT_FILES = {
            {"Desert Capital.txt",
                    "id:desert_capital,radius:106,count:15,density:0.7,"
                            + "structures:[{id:minecraft:village_desert,density:150.0,spaceAround:1,blockLeakingIntoNestedZones:true}],"
                            + "biomes:[minecraft:desert]"},
            {"Desert Capital Core.txt",
                    "id:desert_capital_core,radius:25,shouldFlattenTerrain:surface,ensureBiomeForTheWholeZone:true,"
                            + "structures:[{id:minecraft:desert_pyramid,density:150.0,guaranteePlacement:true,"
                            + "spaceAround:2,blockLeakingOutsideZone:true}],parentZone:desert_capital"},
            {"Dungeon World.txt",
                    "id:dungeon_world,radius:92,count:4,density:0.4,minDistanceFromSpawn:1000,"
                            + "structures:[{id:minecraft:mineshaft,density:35.0}]"},
            {"Dungeon World Stronghold.txt",
                    "id:dungeon_world_core,parentZone:dungeon_world,radius:40,"
                            + "structures:[{id:minecraft:stronghold,guaranteePlacement:true,maxCount:1}]"},
            {"Witch Village.txt",
                    "id:witch_village,count:8,radius:128,shouldFlattenTerrain:surface,"
                            + "ensureBiomeForTheWholeZone:true,biomes:[minecraft:swamp],"
                            + "structures:[{id:minecraft:swamp_hut,density:800.0,blockLeakingOutsideZone:true},"
                            + "{id:minecraft:jungle_pyramid,density:90.0,spaceAround:4,spreadDistance:48}]"}
    };

    public static Path folder() {
        return FMLPaths.CONFIGDIR.get().resolve(Zones.MOD_ID).resolve(FOLDER_NAME);
    }

    /**
     * Raw (blank- and comment-stripped) text of every zone file, ordered by file name so the same
     * folder always produces the same definition order.
     */
    public static List<String> readAll() {
        Path folder = folder();
        if (!Files.isDirectory(folder)) {
            ModLogger.warn("Zone definitions folder {} couldn't be found so no zones will be placed.", folder);

            return List.of();
        }

        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, GLOB)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    files.add(path);
                }
            }
        } catch (IOException e) {
            ModLogger.error("Could not list zone definition files in {}", folder, e);
            return List.of();
        }

        if (files.isEmpty()) {
            ModLogger.warn("No *.txt zone definition files found in {}. no zones will be placed.", folder);
            return List.of();
        }

        files.sort(Comparator.comparing(p -> p.getFileName().toString()));

        List<String> out = new ArrayList<>(files.size());
        for (Path file : files) {
            String text = readOne(file);
            if (text == null || text.isEmpty()) {
                ModLogger.warn("Zone definition file {} is empty (skipped).", file.getFileName());
                continue;
            }
            out.add(text);
        }
        return out;
    }

    public static void createDefaults() {
        Path folder = folder();
        try {
            Files.createDirectories(folder);
            for (String[] entry : DEFAULT_FILES) {
                Path file = folder.resolve(entry[0]);
                if (!Files.exists(file)) {
                    Files.writeString(file, entry[1] + System.lineSeparator(), StandardCharsets.UTF_8);
                }
            }
            ModLogger.info("Created default zone definition files in {}", folder);
        } catch (IOException e) {
            ModLogger.error("Could not create the zone definitions folder {}", folder, e);
        }
    }

    /**
     * Whole-line '//' comments, blank lines and line breaks are dropped; surviving lines are concatenated with
     * NOTHING between them, so the whole file reads as one logical line. Fields are separated by
     * commas exactly as before.
     */
    private static String readOne(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            ModLogger.error("Could not read zone definition file {}", file, e);
            return null;
        }
        StringBuilder sb = new StringBuilder(128);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(COMMENT_PREFIX)) {
                continue;
            }
            sb.append(trimmed);
        }
        return sb.toString();
    }
}
