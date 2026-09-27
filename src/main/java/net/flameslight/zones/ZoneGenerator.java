package net.flameslight.zones;

import net.flameslight.zones.config.WorldZoneConfig;
import net.flameslight.zones.logger.ModLogger;
import net.flameslight.zones.types.BiomeMatcher;
import net.flameslight.zones.types.SearchState;
import net.flameslight.zones.types.ZoneInstance;
import net.flameslight.zones.types.zoneDefinition.ZoneDefinition;
import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Runs exactly once per new world (per dimension).
 *
 * Top-level zone types (no parentZone) have no interdependency, so ALL of them are searched
 * together in one interleaved pass: every zone type with count unlimited or count > 0 starts in a
 * shared "active" pool; each iteration picks ONE random zone type from that pool and tries its next
 * candidate point (random points on successively wider rings spaced zoneStepDifference apart, the
 * first of which sits that far from the world origin). A zone type is dropped from the pool once
 * its count is reached, its search radius passes the world border, or (unlimited count) enough
 * consecutive empty rings pass. This means multiple zone types' searches progress roughly together
 * rather than one type exhausting its entire search before the next type starts.
 *
 * Nested (parentZone) zone types are unaffected and are they still resolve one at a time, in dependency
 * order, once their parent type has fully finished its own search.
 */
final class ZoneGenerator {
    /** For unlimited-count zone types: stop expanding once this many consecutive rings produce zero
     *  placements: a natural, ever-growing-circumference-scaled signal the reachable space is
     *  saturated, without a hard radius cutoff. */
    private static final int EMPTY_RING_STREAK_LIMIT = 50;

    /** Ring chance is density^this. Raising it makes mid-range densities (0.3 - 0.8) rarer without
     *  making high density like 0.9 feel rare. */
    private static final int RING_CHANCE_EXPONENT = 2;

    /** Top-level zones are placed anywhere across this range. matches vanilla's own default world
     *  border half-size, since nothing meaningful can generate past that anyway. */
    private static final long MAX_WORLD_COORDINATE = 29_999_984L;

    static List<ZoneInstance> generate(ServerLevel level, ResourceLocation dimensionId, List<ZoneDefinition> allDefinitions) {
        String dimKey = dimensionId.toString();
        List<ZoneDefinition> defs = allDefinitions.stream()
                .filter(d -> dimKey.equals(d.dimension))
                .toList();
        if (defs.isEmpty()) {
            return List.of();
        }

        PlacementGrid grid = new PlacementGrid(gridCellSize(defs));
        ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        BiomeSource biomeSource = chunkGenerator.getBiomeSource();
        RandomState randomState = level.getChunkSource().randomState();
        LevelHeightAccessor heightAccessor = level;
        // Seed-derived, so layout is deterministic per world seed and
        // independent of anything else consuming the level's live random source.
        RandomSource random = RandomSource.create(level.getSeed() ^ 0x5A11A5DEEDL);
        long worldSeed = level.getSeed();
        Predicate<ZoneDefinition> forcesBiome = forcedBiomeFilter(defs, level);

        List<ZoneInstance> result = new ArrayList<>();
        List<CompletableFuture<Void>> pendingAsyncWork = new ArrayList<>();
        Set<String> resolved = new HashSet<>();
        List<ZoneDefinition> remaining = new ArrayList<>(defs);

        // All top-level defs resolve together, interleaved, in one pass and never depend on
        // each other so there's no need to process them one at a time.
        List<ZoneDefinition> topLevelDefs = remaining.stream()
                .filter(d -> d.parentZone == null || d.parentZone.isEmpty())
                .toList();
        if (!topLevelDefs.isEmpty()) {
            placeAllTopLevel(topLevelDefs, result, random, biomeSource, randomState, level, chunkGenerator,
                    heightAccessor, worldSeed, pendingAsyncWork, grid, forcesBiome);
            for (ZoneDefinition def : topLevelDefs) {
                resolved.add(def.id);
            }
            remaining.removeAll(topLevelDefs);
        }

        // Nested defs still resolve one at a time, in dependency order. each needs its parent's
        // instances to already exist in `result` before it can place itself onto them.
        boolean progress = true;
        while (!remaining.isEmpty() && progress) {
            progress = false;
            Iterator<ZoneDefinition> it = remaining.iterator();
            while (it.hasNext()) {
                ZoneDefinition def = it.next();
                if (resolved.contains(def.parentZone)) {
                    placeNested(def, result, biomeSource, randomState, level, chunkGenerator, heightAccessor,
                            worldSeed, pendingAsyncWork, forcesBiome);
                    resolved.add(def.id);
                    it.remove();
                    progress = true;
                }
            }
        }

        if (!remaining.isEmpty()) {
            ModLogger.warn("Could not resolve {} zone definition(s) - missing or cyclic parentZone: {}",
                    remaining.size(), remaining.stream().map(d -> d.id).toList());
        }

        CompletableFuture.allOf(pendingAsyncWork.toArray(new CompletableFuture[0])).join();

        return result;
    }

    private static void placeAllTopLevel(List<ZoneDefinition> topLevelDefs, List<ZoneInstance> existing,
                                         RandomSource random, BiomeSource biomeSource, RandomState randomState,
                                         ServerLevel level, ChunkGenerator chunkGenerator,
                                         LevelHeightAccessor heightAccessor, long worldSeed,
                                         List<CompletableFuture<Void>> pendingAsyncWork,
                                         PlacementGrid grid, Predicate<ZoneDefinition> forcesBiome) {
        long stepDifference = WorldZoneConfig.zoneStepDifference();
        int defaultTriesPerRing = WorldZoneConfig.baseZonePlacementTries();

        List<SearchState> active = new ArrayList<>();
        for (ZoneDefinition def : topLevelDefs) {
            if (def.count > 0) {
                active.add(new SearchState(def,
                        stepDifference,
                        computeTriesPerRing(def, defaultTriesPerRing),
                        computeRingChance(def),
                        computeTryChance(def),
                        worldSeed));
            }
        }

        while (!active.isEmpty()) {
            int idx = random.nextInt(active.size());
            SearchState state = active.get(idx);
            boolean isUnlimited = state.def.count == Integer.MAX_VALUE;

            // density < 1: this whole ring is skipped. A skipped ring deliberately does NOT count
            // toward emptyRingStreak, otherwise a very rare zone would "saturate" its search
            // without ever having tried a single point.
            if (!state.ringIsAttempted()) {
                state.advanceRing();
                if (state.ringRadius > MAX_WORLD_COORDINATE) {
                    logSearchResult(state, isUnlimited);
                    removeAt(active, idx);
                }
                continue;
            }

            // Per-try density roll. A blocked try still counts toward the ring, so ring
            // advancement and the empty-ring streak behave exactly as they would otherwise.
            boolean success = false;
            if (state.tryIsAllowed()) {
                int[] candidate = state.nextCandidatePoint(random);
                success = tryPlaceCandidate(state.def, candidate[0], candidate[1], existing, biomeSource,
                        randomState, level, chunkGenerator, heightAccessor, worldSeed, pendingAsyncWork, grid,
                        forcesBiome);
            }

            if (success) {
                state.placed++;
                state.ringHadPlacement = true;

                if (!isUnlimited && state.placed >= state.def.count) {
                    removeAt(active, idx); // count reached and done, no warning needed
                    continue;
                }
            }

            state.triesUsedInRing++;
            if (state.triesUsedInRing < state.triesPerRing) {
                continue;
            }

            if (isUnlimited) {
                // Skipped rings don't reach here, so this counts only rings that were actually
                // tried.
                state.emptyRingStreak = state.ringHadPlacement ? 0 : state.emptyRingStreak + 1;
                if (state.emptyRingStreak >= EMPTY_RING_STREAK_LIMIT) {
                    logSearchResult(state, true);
                    removeAt(active, idx);
                    continue;
                }
            }

            state.advanceRing();
            if (state.ringRadius > MAX_WORLD_COORDINATE) {
                logSearchResult(state, isUnlimited);
                removeAt(active, idx);
            }
        }
    }

    private static int computeTriesPerRing(ZoneDefinition def, int baseTries) {
        return Math.max(1, Math.round(baseTries * def.density));
    }

    private static float computeRingChance(ZoneDefinition def) {
        float density = Math.min(def.density, 1.0f);
        return (float) Math.pow(density, RING_CHANCE_EXPONENT);
    }

    private static float computeTryChance(ZoneDefinition def) {
        return Math.min(def.density, 1.0f);
    }

    private static void removeAt(List<SearchState> list, int idx) {
        int last = list.size() - 1;
        list.set(idx, list.get(last));
        list.remove(last);
    }

    private static void logSearchResult(SearchState state, boolean isUnlimited) {
        if (isUnlimited) {
            ModLogger.debug("Zone '{}': placed {} instance(s) (unlimited count, search saturated).",
                    state.def.id, state.placed);
        } else if (state.placed < state.def.count) {
            ModLogger.warn("Zone '{}': only placed {}/{} instance(s) before hitting the world-edge search "
                            + "limit. biome/spacing constraints are likely too strict for this density.",
                    state.def.id, state.placed, state.def.count);
        }
    }

    /** Checks minDistanceFromSpawn/biome/spacing for one candidate point and, if all pass, creates
     *  and registers the ZoneInstance (queueing its async flattenY/forcedBiome work). Returns
     *  whether the candidate was actually placed. */
    private static boolean tryPlaceCandidate(ZoneDefinition def, int x, int z, List<ZoneInstance> existing,
                                             BiomeSource biomeSource, RandomState randomState, ServerLevel level,
                                             ChunkGenerator chunkGenerator, LevelHeightAccessor heightAccessor,
                                             long worldSeed, List<CompletableFuture<Void>> pendingAsyncWork,
                                             PlacementGrid grid, Predicate<ZoneDefinition> forcesBiome) {
        if (def.minDistanceFromSpawn > 0) {
            long dx = x;
            long dz = z;
            long minDist = def.minDistanceFromSpawn;
            if (dx * dx + dz * dz < minDist * minDist) {
                return false;
            }
        }
        if (!def.biomes.isEmpty() && !biomeMatches(biomeSource, randomState, x, z, def.biomes, level)) {
            return false;
        }
        if (!grid.respects(def, x, z, def.radius)) {
            return false;
        }

        ZoneInstance instance = new ZoneInstance();
        instance.zoneType = def.id;
        instance.dimension = def.dimension;
        instance.centerX = x;
        instance.centerZ = z;
        instance.radius = def.radius;
        existing.add(instance);
        grid.add(instance);

        queueInstanceDetails(instance, def, x, z, def.radius, def.shouldFlattenTerrain.isEnabled(),
                forcesBiome.test(def), chunkGenerator, randomState, heightAccessor,
                biomeSource, level, worldSeed, pendingAsyncWork);
        return true;
    }

    private static void placeNested(ZoneDefinition def,
                                    List<ZoneInstance> existing,
                                    BiomeSource biomeSource,
                                    RandomState randomState,
                                    ServerLevel level,
                                    ChunkGenerator chunkGenerator,
                                    LevelHeightAccessor heightAccessor,
                                    long worldSeed,
                                    List<CompletableFuture<Void>> pendingAsyncWork,
                                    Predicate<ZoneDefinition> forcesBiome) {
        List<ZoneInstance> parents = existing.stream()
                .filter(zi -> zi.zoneType.equals(def.parentZone))
                .toList();

        // An ancestor's flatten Y / forced biome already covers this child's whole circle; a value of
        // the child's own would only fight it inside the smaller circle.
        boolean flatten = def.shouldFlattenTerrain.isEnabled()
                && !ancestorHas(def, d -> d.shouldFlattenTerrain.isEnabled());
        boolean forceBiome = forcesBiome.test(def) && !ancestorHas(def, forcesBiome);

        if (def.shouldFlattenTerrain.isEnabled() && !flatten) {
            ModLogger.warn("Zone '{}': shouldFlattenTerrain ignored! a parent zone already flattens it.", def.id);
        }
        if (forcesBiome.test(def) && !forceBiome) {
            ModLogger.warn("Zone '{}': ensureBiomeForTheWholeZone ignored! a parent zone already forces its biome.", def.id);
        }

        for (ZoneInstance parent : parents) {
            int radius = def.radius;
            if (radius > parent.radius) {
                ModLogger.warn("Zone '{}': radius {} exceeds parent zone '{}' radius {}; clamping.",
                        def.id, radius, parent.zoneType, parent.radius);
                radius = parent.radius;
            }

            ZoneInstance instance = new ZoneInstance();
            instance.zoneType = def.id;
            instance.dimension = def.dimension;
            instance.centerX = parent.centerX;
            instance.centerZ = parent.centerZ;
            instance.radius = radius;
            existing.add(instance);

            queueInstanceDetails(instance, def, parent.centerX, parent.centerZ, radius, flatten, forceBiome,
                    chunkGenerator, randomState, heightAccessor, biomeSource, level, worldSeed, pendingAsyncWork);
        }
    }

    /** Uses a deterministic, per-instance seed, NOT the shared search RandomSource, which the
     *  main thread keeps mutating concurrently and is unsafe to touch from a background task. */
    private static void queueInstanceDetails(ZoneInstance instance, ZoneDefinition def, int centerX, int centerZ,
                                             int radius, boolean toFlatten, boolean toForceBiome,
                                             ChunkGenerator chunkGenerator, RandomState randomState,
                                             LevelHeightAccessor heightAccessor, BiomeSource biomeSource,
                                             ServerLevel level, long worldSeed,
                                             List<CompletableFuture<Void>> pendingAsyncWork) {
        if (!toFlatten && !toForceBiome) {
            return;
        }
        int seaLevel = level.getSeaLevel();
        long instanceSeed = worldSeed ^ 0x5A11A5DEEDL ^ (((long) centerX) << 32 ^ (centerZ & 0xffffffffL));
        pendingAsyncWork.add(CompletableFuture.runAsync(() -> {
            if (toFlatten) {
                RandomSource instanceRandom = RandomSource.create(instanceSeed);
                instance.flattenY = TerrainFlatteningHandler.computeFlattenY(def,
                        centerX,
                        centerZ,
                        radius,
                        chunkGenerator,
                        randomState,
                        heightAccessor,
                        instanceRandom,
                        seaLevel);
            }
            if (toForceBiome) {
                instance.forcedBiome = def.ensureBiomeId != null
                        ? def.ensureBiomeId
                        : resolveBiomeId(biomeSource, randomState, centerX, centerZ, level);
            }
        }, Util.backgroundExecutor()));
    }

    /**
     * Which definitions really force a biome in this world. An explicit ensureBiomeForTheWholeZone
     * biome id missing from the biome registry disables the option for that zone completely, so it
     * neither forces anything nor blocks its child zones from forcing their own.
     */
    private static Predicate<ZoneDefinition> forcedBiomeFilter(List<ZoneDefinition> defs, ServerLevel level) {
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        Set<String> invalid = new HashSet<>();

        for (ZoneDefinition def : defs) {
            if (def.ensureBiomeForTheWholeZone && def.ensureBiomeId != null
                    && !biomes.containsKey(def.ensureBiomeId)) {
                ModLogger.warn("Zone '{}': ensureBiomeForTheWholeZone biome '{}' does not exist; option ignored.",
                        def.id, def.ensureBiomeId);
                invalid.add(def.id);
            }
        }

        return def -> def.ensureBiomeForTheWholeZone && !invalid.contains(def.id);
    }

    /** Walks the parentZone chain (cycle-safe); true if any ancestor matches. */
    private static boolean ancestorHas(ZoneDefinition def, Predicate<ZoneDefinition> flag) {
        Set<String> seen = new HashSet<>();
        String parentId = def.parentZone;
        while (parentId != null && !parentId.isEmpty() && seen.add(parentId)) {
            ZoneDefinition parentDef = WorldZoneConfig.findDefinition(parentId);
            if (parentDef == null) {
                return false;
            }
            if (flag.test(parentDef)) {
                return true;
            }
            parentId = parentDef.parentZone;
        }
        return false;
    }

    private static ResourceLocation resolveBiomeId(BiomeSource biomeSource, RandomState randomState, int blockX, int blockZ, ServerLevel level) {
        int quartY = level.getSeaLevel() >> 2;
        Holder<Biome> biome = biomeSource.getNoiseBiome(blockX >> 2, quartY, blockZ >> 2, randomState.sampler());
        return biome.unwrapKey().map(ResourceKey::location).orElse(null);
    }

    private static boolean biomeMatches(BiomeSource biomeSource, RandomState randomState, int blockX, int blockZ,
                                        List<BiomeMatcher> allowedBiomes, ServerLevel level) {
        // Sampled straight from the biome source's noise function — this does
        // NOT force chunk generation, unlike Level#getBiome.
        int quartY = level.getSeaLevel() >> 2;
        Holder<Biome> biome = biomeSource.getNoiseBiome(blockX >> 2, quartY, blockZ >> 2, randomState.sampler());
        for (BiomeMatcher matcher : allowedBiomes) {
            if (matcher.matches(biome)) {
                return true;
            }
        }
        return false;
    }

    /** Incremental spatial index over already-placed instances, so each candidate only tests the
     *  zones that could possibly be close enough. Cell size is derived from the largest
     *  radius+gap in play, so a 3x3 neighbourhood provably covers every possible conflict. */
    private static final class PlacementGrid {
        private final int cellSize;
        private final Map<Long, List<ZoneInstance>> cells = new HashMap<>();

        PlacementGrid(int cellSize) {
            this.cellSize = Math.max(1, cellSize);
        }

        void add(ZoneInstance zi) {
            cells.computeIfAbsent(key(zi.centerX, zi.centerZ), k -> new ArrayList<>()).add(zi);
        }

        boolean respects(ZoneDefinition def, int x, int z, int radius) {
            int cx = Math.floorDiv(x, cellSize);
            int cz = Math.floorDiv(z, cellSize);
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    List<ZoneInstance> bucket = cells.get(
                            (((long) (cx + ox)) << 32) ^ ((cz + oz) & 0xffffffffL));
                    if (bucket == null) {
                        continue;
                    }
                    for (ZoneInstance other : bucket) {
                        long dx = x - other.centerX;
                        long dz = z - other.centerZ;
                        long required = (long) radius + other.radius + def.minDistanceFromOtherZones;
                        if (dx * dx + dz * dz < required * required) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        private long key(int x, int z) {
            return (((long) Math.floorDiv(x, cellSize)) << 32) ^ (Math.floorDiv(z, cellSize) & 0xffffffffL);
        }
    }

    private static int gridCellSize(List<ZoneDefinition> defs) {
        int max = 1;
        for (ZoneDefinition d : defs) {
            max = Math.max(max, d.radius + d.minDistanceFromOtherZones);
        }
        return max * 2; // two zones' worth, so a 3x3 neighbourhood can never miss a conflict
    }
}
