package net.flameslight.zones;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.google.gson.reflect.TypeToken;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.flameslight.zones.config.WorldZoneConfig;
import net.flameslight.zones.logger.ModLogger;
import net.flameslight.zones.types.BiomeMatcher;
import net.flameslight.zones.types.ChunkInZoneLoadingStatus;
import net.flameslight.zones.types.ZoneInstance;
import net.flameslight.zones.types.biome.ZoneBiomeSourceContext;
import net.flameslight.zones.types.biome.ZoneBiomeSourceHolder;
import net.flameslight.zones.types.zoneDefinition.ZoneDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ZoneManager {
    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(ResourceLocation.class, new ResourceLocationAdapter().nullSafe())
            .create();
    public static final ThreadLocal<Boolean> COMPUTING_REAL_SURFACE = ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<FlattenColumnCache> FLATTEN_COLUMN_CACHE =
            ThreadLocal.withInitial(FlattenColumnCache::new);
    private static final ThreadLocal<ZoneLookupCache> LAST_ZONE_LOOKUP =
            ThreadLocal.withInitial(ZoneLookupCache::new);

    /**
     * How far below the REAL local terrain height ensureBiomeForTheWholeZone still overrides biome
     */
    private static final int SURFACE_DEPTH_MARGIN = 8;
    private static final int GUARANTEE_MAX_ATTEMPTS = 10;
    private static final int FLATTEN_CACHE_MASK = 63; // 64 slots, power of two
    private static final int ZONE_LOOKUP_MASK = 3; // 4 slots, power of two
    private static final int SURFACE_CACHE_MAX = 8192;
    /**
     * One real noise sample per chunk for ensureBiomeForTheWholeZone.
     */
    private static final int SURFACE_SAMPLE_MASK = ~15;
    private static final int ZONE_GRID = 512;
    /**
     * How far past a flatten zone's radius its flattenY still applies to the preliminary surface.
     * Vanilla samples that surface only at chunk corners (16 blocks apart) and interpolates, so a
     * corner just outside the circle would otherwise pull the zone's edge back up to the old terrain.
     */
    private static final int PRELIMINARY_SURFACE_MARGIN = 16;

    /**
     * A zone spanning more cells than this goes in the always-scanned `oversized` list instead,
     * so one huge-radius zone can't blow up the index with tens of thousands of cell entries.
     */
    private static final int MAX_CELLS_PER_ZONE = 256;

    private static volatile Set<String> ZONE_RESTRICTED_STRUCTURES = Collections.emptySet();

    /**
     * Cached toString() per structure id. ResourceLocation.toString() allocates and every map
     * lookup then re-hashes a long string; the registry hands out one instance per structure, so
     * an identity map turns both into a single reference lookup. Bounded by the structure registry
     * (a few hundred entries), copy-on-write, cleared with the session.
     */
    private static volatile Map<ResourceLocation, String> ID_STRING_CACHE = Collections.emptyMap();
    private static final Map<ResourceLocation, List<ZoneInstance>> ZONES_BY_DIMENSION = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ServerLevel> LEVEL_BY_DIMENSION = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Map<Long, Integer>> SURFACE_HEIGHT_CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ZoneIndex> ZONE_INDEX_BY_DIMENSION = new ConcurrentHashMap<>();

    private static final Set<ResourceLocation> DIRTY_DIMENSIONS = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> FRESHLY_GENERATED = ConcurrentHashMap.newKeySet();
    /**
     * the ids /locate may have recorded positions for.
     */
    private static volatile Set<String> ZONE_TRACKED_STRUCTURES = Collections.emptySet();

    private static volatile RegistryAccess CACHED_REGISTRY_ACCESS;
    private static volatile boolean structureReferencesValidated = false;
    private static volatile boolean hasAnyIncreasedDensity = false;
    private static volatile boolean hasAnyFlattenZone = false;
    private static volatile boolean hasAnyForcedBiomeZone = false;
    private static volatile boolean hasAnyGuaranteePlacement = false;
    private static volatile boolean hasAnyReclaimableZoneData = false;

    /*
        Copy-on-write identity maps: read lock-free on every worldgen thread, replaced wholesale on
        the rare write (level load). Entries are dropped in unloadDimension and resetSessionState.
    */
    private static volatile Map<ChunkGenerator, ResourceLocation> DIMENSION_BY_GENERATOR = Collections.emptyMap();
    private static volatile Map<BiomeSource, ResourceLocation> DIMENSION_BY_BIOME_SOURCE = Collections.emptyMap();
    private static volatile Map<RandomState, ResourceLocation> DIMENSION_BY_RANDOM_STATE = Collections.emptyMap();

    // Maps every live StructurePlacement instance to the structure ids its
    // StructureSet contains — built once per server session so the
    // isStructureChunk mixin can find "which structures does this shared
    // placement gate" without any datapack surgery.
    private static volatile Map<StructurePlacement, List<ResourceLocation>> PLACEMENT_TO_STRUCTURES =
            Collections.emptyMap();
    private static volatile boolean placementIndexBuilt = false;

    /**
     * Bumped on every resetSessionState() call so a worker thread's cached getZoneAt result from
     * a PREVIOUS world/session can never be mistakenly reused in a new one, even if the new
     * session happens to reuse the same dimension string and coordinates.
     */
    private static volatile long zoneLookupEpoch = 0L;

    /**
     * Direct-mapped, 4 entries. A single slot thrashed badly: the worldgen path alternates
     * between a structure's own position and neighbouring columns within one chunk.
     */
    private static final class ZoneLookupCache {
        final long[] keys = new long[ZONE_LOOKUP_MASK + 1];
        final ResourceLocation[] dims = new ResourceLocation[ZONE_LOOKUP_MASK + 1];
        final ZoneInstance[] results = new ZoneInstance[ZONE_LOOKUP_MASK + 1];
        long epoch = -1L;
    }

    /**
     * Keeps a ResourceLocation as its plain "namespace:path" string in the zone files, the same format
     * forcedBiome had as a String, so older worlds load unchanged. A malformed id reads back as null
     * (no forced biome) instead of failing the whole file.
     */
    private static final class ResourceLocationAdapter extends TypeAdapter<ResourceLocation> {
        @Override
        public void write(JsonWriter out, ResourceLocation value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public ResourceLocation read(JsonReader in) throws IOException {
            return ResourceLocation.tryParse(in.nextString());
        }
    }

    private static final class ZoneIndex {
        // Primitive long keys: every biome/zone lookup hits this, and a boxed Long per call adds up.
        final Long2ObjectOpenHashMap<List<ZoneInstance>> cells = new Long2ObjectOpenHashMap<>();
        final List<ZoneInstance> oversized = new ArrayList<>();
    }

    private static final class FlattenColumnCache {
        private final long[] keys = new long[FLATTEN_CACHE_MASK + 1];
        private final ResourceLocation[] dims = new ResourceLocation[FLATTEN_CACHE_MASK + 1];
        private final int[] values = new int[FLATTEN_CACHE_MASK + 1];
        private long epoch = -1L;

        // Single-entry "does this whole chunk contain a flatten zone" answer, for fillArray.
        private ResourceLocation chunkDim;
        private long chunkKey = Long.MIN_VALUE;
        private boolean chunkHasFlatten;

        // Last surface sample handed out by computeSurfaceHeight. The biome source asks for the same
        // chunk's sample many times in a row, so this skips the shared, locked cache almost always.
        private ResourceLocation surfaceDim;
        private long surfaceKey = Long.MIN_VALUE;
        private int surfaceValue;
    }

    // ---- Loading / generation ----

    public static synchronized void loadOrGenerate(ServerLevel level) {
        ResourceLocation dimId = level.dimension().location();
        LEVEL_BY_DIMENSION.put(dimId, level);
        if (ZONES_BY_DIMENSION.containsKey(dimId)) {
            recomputeDerivedState();
            return;
        }

        List<ZoneDefinition> allDefs = WorldZoneConfig.getParsedDefinitions();
        String dimKey = dimId.toString();
        boolean anyForThisDimension = allDefs.stream().anyMatch(d -> dimKey.equals(d.dimension));
        if (!anyForThisDimension) {
            ZONES_BY_DIMENSION.put(dimId, List.of());
            indexZones(dimId, List.of());
            recomputeDerivedState();
            return;
        }

        Path file = zoneFile(level, dimId);
        List<ZoneInstance> instances;
        if (Files.exists(file)) {
            instances = readZones(file);

            int before = instances.size();
            instances.removeIf(zoneInstance -> zoneInstance == null || zoneInstance.zoneType == null || zoneInstance.dimension == null);
            if (instances.size() != before) {
                ModLogger.warn("Dropped {} corrupt zone instance(s) (missing zoneType/dimension) while "
                        + "loading {}", before - instances.size(), file);
            }

            for (ZoneInstance zoneInstance : instances) {
                zoneInstance.normalizeCollectionsAfterLoad();
            }

            ModLogger.info("Loaded {} existing zone instance(s) for {}", instances.size(), dimId.toString());
        } else {
            instances = ZoneGenerator.generate(level, dimId, allDefs);
            writeZones(file, instances);
            FRESHLY_GENERATED.add(dimId);
            ModLogger.info("Generated {} new zone instance(s) for {}", instances.size(), dimId.toString());
        }

        List<ZoneInstance> stored = List.copyOf(instances);
        resolveForcedBiomeHolders(level, stored);

        ZONES_BY_DIMENSION.put(dimId, stored);
        indexZones(dimId, stored);
        LocatePositionsSavingHandler.openDimension(dimId, dataDir(level));
        recomputeDerivedState();
    }

    /**
     * Resolves every instance's forcedBiome once per load, so the biome hot path returns a cached
     * holder instead of creating a ResourceKey and hitting the registry on every lookup.
     */
    private static void resolveForcedBiomeHolders(ServerLevel level, List<ZoneInstance> instances) {
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        for (ZoneInstance zoneInstance : instances) {
            if (zoneInstance.forcedBiome == null) {
                continue;
            }
            zoneInstance.forcedBiomeHolder = biomes
                    .getHolder(ResourceKey.create(Registries.BIOME, zoneInstance.forcedBiome))
                    .orElse(null);
            if (zoneInstance.forcedBiomeHolder == null) {
                ModLogger.warn("Zone '{}' at [{}, {}] forces biome '{}', which no longer exists; not forcing it.",
                        zoneInstance.zoneType, zoneInstance.centerX, zoneInstance.centerZ, zoneInstance.forcedBiome);
            }
        }
    }

    public static Optional<ZoneInstance> resolveConfiguredSpawnZone(ResourceLocation dimension) {
        if (!FRESHLY_GENERATED.contains(dimension)) {
            return Optional.empty();
        }
        List<String> whitelist = WorldZoneConfig.getSpawnWhitelist();
        if (whitelist.isEmpty()) {
            return Optional.empty();
        }
        List<ZoneInstance> zones = getAllZones(dimension);
        for (String zoneId : whitelist) {
            for (ZoneInstance zoneInstance : zones) {
                if (zoneInstance.zoneType.equals(zoneId)) {
                    return Optional.of(zoneInstance);
                }
            }
        }
        return Optional.empty();
    }

    public static synchronized void registerChunkGenerator(ChunkGenerator generator, ResourceLocation dimension) {
        DIMENSION_BY_GENERATOR = withEntry(DIMENSION_BY_GENERATOR, generator, dimension);
    }

    public static ResourceLocation getDimensionForGenerator(ChunkGenerator generator) {
        return DIMENSION_BY_GENERATOR.get(generator);
    }

    public static synchronized void registerBiomeSource(BiomeSource biomeSource, ResourceLocation dimension) {
        DIMENSION_BY_BIOME_SOURCE = withEntry(DIMENSION_BY_BIOME_SOURCE, biomeSource, dimension);
        if (biomeSource instanceof ZoneBiomeSourceHolder holder) {
            holder.structurezones$setContext(new ZoneBiomeSourceContext(dimension, null));
        }
    }

    public static synchronized void registerRandomState(RandomState randomState, ResourceLocation dimension) {
        DIMENSION_BY_RANDOM_STATE = withEntry(DIMENSION_BY_RANDOM_STATE, randomState, dimension);
    }

    public static ResourceLocation getDimensionForRandomState(RandomState randomState) {
        return DIMENSION_BY_RANDOM_STATE.get(randomState);
    }

    /**
     * zoneOnlyBiomes: builds this dimension's filter once, at level load and before zones are
     * generated, so zone placement already sees the filtered biomes.
     */
    public static synchronized void registerZoneOnlyBiomes(ServerLevel level, BiomeSource biomeSource) {
        ResourceLocation dimension = level.dimension().location();
        ZoneOnlyBiomeFilter filter = ZoneOnlyBiomeFilter.create(biomeSource,
                level.registryAccess().registryOrThrow(Registries.BIOME), WorldZoneConfig.getZoneOnlyBiomes(), dimension);
        if (filter == null) {
            return;
        }
        if (biomeSource instanceof ZoneBiomeSourceHolder holder) {
            holder.structurezones$setContext(new ZoneBiomeSourceContext(dimension, filter));
        }

        String dimKey = dimension.toString();
        for (ZoneDefinition def : WorldZoneConfig.getParsedDefinitions()) {
            if (!dimKey.equals(def.dimension)
                    || def.biomes.isEmpty()
                    || (def.parentZone != null && !def.parentZone.isEmpty())) {
                continue;
            }
            boolean onlyZoneOnly = true;
            for (BiomeMatcher matcher : def.biomes) {
                ResourceLocation exact = matcher.exactId();
                Holder<Biome> holder = exact == null ? null : level.registryAccess().registryOrThrow(Registries.BIOME)
                        .getHolder(ResourceKey.create(Registries.BIOME, exact)).orElse(null);
                if (holder == null || !filter.isZoneOnly(holder)) {
                    onlyZoneOnly = false;
                    break;
                }
            }
            if (onlyZoneOnly) {
                ModLogger.warn("Zone '{}': every biome in its 'biomes' list is in zoneOnlyBiomes, so none of them "
                        + "generates naturally and this zone can never be placed.", def.id);
            }
        }
    }

    public static ResourceLocation getDimensionForBiomeSource(BiomeSource biomeSource) {
        return DIMENSION_BY_BIOME_SOURCE.get(biomeSource);
    }

    /** Detaches our per-source context so a closed world's zones never answer for a new one. */
    private static void clearBiomeSourceContexts(Collection<BiomeSource> sources) {
        for (BiomeSource source : sources) {
            if (source instanceof ZoneBiomeSourceHolder holder) {
                holder.structurezones$setContext(null);
            }
        }
    }

    public static void ensurePlacementIndexBuilt(RegistryAccess registryAccess) {
        CACHED_REGISTRY_ACCESS = registryAccess;
        if (!structureReferencesValidated) {
            validateStructureReferences(registryAccess);
            structureReferencesValidated = true;
        }
        if (placementIndexBuilt) {
            return;
        }
        synchronized (ZoneManager.class) {
            if (placementIndexBuilt) {
                return;
            }
            Map<StructurePlacement, List<ResourceLocation>> index = new IdentityHashMap<>();
            Map<ResourceLocation, String> idStrings = new IdentityHashMap<>();

            for (StructureSet set : registryAccess.registryOrThrow(Registries.STRUCTURE_SET)) {
                List<ResourceLocation> ids = new ArrayList<>();
                for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                    entry.structure().unwrapKey().ifPresent(k -> ids.add(k.location()));
                }
                index.put(set.placement(), List.copyOf(ids));
            }

            for (ResourceLocation id : registryAccess.registryOrThrow(Registries.STRUCTURE).keySet()) {
                idStrings.put(id, id.toString().intern());
            }

            ID_STRING_CACHE = Collections.unmodifiableMap(idStrings);
            PLACEMENT_TO_STRUCTURES = Collections.unmodifiableMap(index);
            placementIndexBuilt = true;
        }
    }

    /**
     * One-time-per-session sanity check: warns about structure ids referenced in config that
     * don't actually exist in the structure registry, and about zoneOnlyStructures entries that
     * aren't registered to any zone at all (which would otherwise silently block that structure
     * EVERYWHERE, in and out of every zone, with nothing to allow it back).
     */
    private static void validateStructureReferences(RegistryAccess registryAccess) {
        var structureRegistry = registryAccess.registryOrThrow(Registries.STRUCTURE);
        Set<String> allReferencedIds = new HashSet<>();

        for (ZoneDefinition def : WorldZoneConfig.getParsedDefinitions()) {
            for (ZoneDefinition.StructureEntry entry : def.structures) {
                if (entry == null || entry.id == null || entry.id.isEmpty()) {
                    continue;
                }
                allReferencedIds.add(entry.id);
                ResourceLocation id = ResourceLocation.tryParse(entry.id);
                if (id == null || !structureRegistry.containsKey(id)) {
                    ModLogger.warn("Zone '{}' references unknown structure '{}'. Check if the id is correct "
                                    + "and that any mod providing it is installed; this structure will never generate.",
                            def.id, entry.id);
                }
            }
        }

        for (String zoneOnlyId : WorldZoneConfig.getZoneOnlyStructures()) {
            if (!allReferencedIds.contains(zoneOnlyId)) {
                ModLogger.warn("'{}' is listed in zoneOnlyStructures but isn't registered to any zone's "
                        + "'structures' list so it will be blocked from generating everywhere, in and out "
                        + "of every zone, with nothing to allow it back.", zoneOnlyId);
            }
        }
    }

    public static List<ResourceLocation> getStructuresForPlacement(StructurePlacement placement) {
        return PLACEMENT_TO_STRUCTURES.getOrDefault(placement, List.of());
    }

    /**
     * Clears all cached state. Called on server stop so loading a different save within one game session can't reuse stale data.
     */
    public static synchronized void resetSessionState() {
        ZONES_BY_DIMENSION.clear();
        LEVEL_BY_DIMENSION.clear();
        SURFACE_HEIGHT_CACHE.clear();
        ID_STRING_CACHE = Collections.emptyMap();
        DIMENSION_BY_GENERATOR = Collections.emptyMap();
        clearBiomeSourceContexts(DIMENSION_BY_BIOME_SOURCE.keySet());
        DIMENSION_BY_BIOME_SOURCE = Collections.emptyMap();
        PLACEMENT_TO_STRUCTURES = Collections.emptyMap();
        DIMENSION_BY_RANDOM_STATE = Collections.emptyMap();
        ZONE_RESTRICTED_STRUCTURES = Collections.emptySet();
        ZONE_TRACKED_STRUCTURES = Collections.emptySet();
        FRESHLY_GENERATED.clear();
        ZONE_INDEX_BY_DIMENSION.clear();
        DIRTY_DIMENSIONS.clear();
        LocatePositionsSavingHandler.reset();
        CACHED_REGISTRY_ACCESS = null;

        hasAnyGuaranteePlacement = false;
        hasAnyReclaimableZoneData = false;
        placementIndexBuilt = false;
        hasAnyIncreasedDensity = false;
        structureReferencesValidated = false;
        hasAnyFlattenZone = false;
        hasAnyForcedBiomeZone = false;

        zoneLookupEpoch++; // invalidates every worker thread's cached getZoneAt result
    }

    public static synchronized void onDefinitionsChanged() {
        recomputeDerivedState();
        zoneLookupEpoch++;
    }

    private static void recomputeDerivedState() {
        boolean anyIncreased = false;
        boolean anyFlatten = false;
        boolean anyForcedBiome = false;
        boolean anyGuarantee = false;
        boolean anyReclaimable = false;
        Set<String> tracked = new HashSet<>();

        for (ZoneDefinition def : WorldZoneConfig.getParsedDefinitions()) {
            if (def.shouldFlattenTerrain.isEnabled()) {
                anyFlatten = true;
            }
            if (def.ensureBiomeForTheWholeZone) {
                anyForcedBiome = true;
            }

            boolean defReclaimable = false;
            for (ZoneDefinition.StructureEntry entry : def.structures) {
                if (entry == null || entry.id == null || entry.id.isEmpty()) {
                    continue;
                }
                tracked.add(entry.id);
                if (entry.spaceAround > 0 || entry.maxCount > 0 || entry.guaranteePlacement) {
                    defReclaimable = true;
                }
                if (entry.density > 1.0f) {
                    anyIncreased = true;
                }
                if (entry.guaranteePlacement) {
                    anyGuarantee = true;
                }
            }
            def.hasReclaimableData = defReclaimable;
            anyReclaimable |= defReclaimable;
        }

        ZONE_RESTRICTED_STRUCTURES = WorldZoneConfig.getZoneOnlyStructures();
        ZONE_TRACKED_STRUCTURES = Set.copyOf(tracked);
        hasAnyIncreasedDensity = anyIncreased;
        hasAnyFlattenZone = anyFlatten;
        hasAnyForcedBiomeZone = anyForcedBiome;
        hasAnyGuaranteePlacement = anyGuarantee;
        hasAnyReclaimableZoneData = anyReclaimable;
    }

    // ---- Query API: touched by the worldgen hot path and by other mods ----

    /**
     * Nullable, allocation-free form used by the worldgen hot path.
     */
    public static ZoneInstance getZoneAtCached(ResourceLocation dimension, int blockX, int blockZ) {
        long currentEpoch = zoneLookupEpoch;
        ZoneLookupCache cached = LAST_ZONE_LOOKUP.get();

        if (cached.epoch != currentEpoch) {
            cached.epoch = currentEpoch;
            Arrays.fill(cached.keys, Long.MIN_VALUE);
            Arrays.fill(cached.dims, null);
            Arrays.fill(cached.results, null);
        }

        long key = (((long) blockX) << 32) ^ (blockZ & 0xffffffffL);
        int slot = zoneLookupSlot(blockX, blockZ, dimension);
        // dims[slot] is null on an empty slot, so equals() rejects it regardless of the key.
        if (cached.keys[slot] == key && dimension.equals(cached.dims[slot])) {
            return cached.results[slot];
        }

        ZoneInstance best = null;
        for (ZoneInstance zoneInstance : zonesNear(dimension, blockX, blockZ)) {
            if (zoneInstance.contains(blockX, blockZ) && (best == null || zoneInstance.radius < best.radius)) {
                best = zoneInstance;
            }
        }

        cached.keys[slot] = key;
        cached.dims[slot] = dimension;
        cached.results[slot] = best;
        return best;
    }

    /**
     * Final, dimension-correct gate used by Structure#generate. Handles zone membership, density
     * thinning, maxCount, guaranteePlacement, and obeyParent inheritance.
     */
    public static boolean isStructureAllowed(ResourceLocation dimension,
                                             int blockX,
                                             int blockZ,
                                             ResourceLocation structureId,
                                             long worldSeed) {
        String structureIdStr = idString(structureId);
        ZoneInstance zoneInstance = getZoneAtCached(dimension, blockX, blockZ);
        if (zoneInstance == null) {
            return !ZONE_RESTRICTED_STRUCTURES.contains(structureIdStr);
        }

        ZoneDefinition def = WorldZoneConfig.findDefinition(zoneInstance.zoneType);
        if (def == null) {
            return false;
        }

        // obeyParent: the child sees its own 'structures' list first; only if it has no entry
        // of its own for this id AND obeyParent is on does it fall back to the parent's entry.
        ZoneDefinition.StructureEntry entry = resolveEntry(def, structureIdStr);

        if (entry == null) {
            return false;
        }

        int currentCount = zoneInstance.getStructurePlacementCount(structureIdStr);
        if (entry.maxCount > 0 && currentCount >= entry.maxCount) {
            return false;
        }
        if (entry.guaranteePlacement && currentCount == 0 && !isPlacementGiveUp(zoneInstance, structureIdStr)) {
            return true;
        }

        if (!passesSpreadCell(structureIdStr, entry.spreadDistance, worldSeed, blockX, blockZ)) {
            return false;
        }
        if (entry.density >= 1.0f) {
            return true;
        }
        double roll = deterministicRoll(structureIdStr.hashCode(), blockX >> 4, blockZ >> 4);

        return roll < entry.density;
    }

    /**
     * Called from ChunkGeneratorMixin only once a start has actually been committed to its chunk.
     * Locks the one zone instance it touches rather than the whole manager: at high structure
     * densities this runs for nearly every chunk, on every worldgen thread at once.
     */
    public static void recordStructurePlaced(ResourceLocation dimension, ChunkPos chunkPos,
                                             ResourceLocation structureId) {
        int blockX = chunkPos.getMinBlockX() + 8;
        int blockZ = chunkPos.getMinBlockZ() + 8;
        ZoneInstance zoneInstance = getZoneAtCached(dimension, blockX, blockZ);
        if (zoneInstance == null) {
            return;
        }

        String idStr = idString(structureId);

        // maxCount / guaranteePlacement read this back (resolved through obeyParent). Nothing reads
        // it once the zone has retired.
        boolean countChanged = false;
        synchronized (zoneInstance) {
            if (!zoneInstance.spaceAroundRetired && needsPlacementCount(zoneInstance, idStr)) {
                zoneInstance.initStructurePlacementCounts().merge(idStr, 1, Integer::sum);
                countChanged = true;
            }
        }

        LocatePositionsSavingHandler.record(dimension, idStr,
                (((long) blockX) << 32) ^ (blockZ & 0xffffffffL));

        if (countChanged) {
            persistZones(dimension);
        }
    }

    /** Nearest recorded placement of a structure committed inside any zone instance in this dimension. */
    public static Optional<BlockPos> findNearestZonePlacement(ResourceLocation dimension,
                                                              ResourceLocation structureId, BlockPos from) {
        return LocatePositionsSavingHandler.findNearest(dimension, idString(structureId), from);
    }

    public static boolean isZoneRestrictedStructure(ResourceLocation structureId) {
        return ZONE_RESTRICTED_STRUCTURES.contains(idString(structureId));
    }

    public static boolean isZoneTrackedStructure(ResourceLocation structureId) {
        return ZONE_TRACKED_STRUCTURES.contains(idString(structureId));
    }

    /**
     * the actual guarantee mechanism, checked from StructurePlacementMixin.
     * Scoped to a zone's own 'structures' list only, not obeyParent-inherited entries.
     * `dimension` may be null when the caller couldn't resolve it, in which case every loaded
     * dimension is scanned. A false positive there just means an extra eligible chunk that
     * isStructureAllowed correctly rejects afterward.
     */
    public static boolean shouldForceGuaranteedEligibility(ResourceLocation dimension, int blockX, int blockZ,
                                                           List<ResourceLocation> structureIds) {
        if (!hasAnyGuaranteePlacement) {
            return false;
        }
        if (dimension != null) {
            return forcesGuaranteedEligibilityIn(dimension, blockX, blockZ, structureIds);
        }
        for (ResourceLocation dim : ZONE_INDEX_BY_DIMENSION.keySet()) {
            if (forcesGuaranteedEligibilityIn(dim, blockX, blockZ, structureIds)) {
                return true;
            }
        }
        return false;
    }

    public static void recordPlacementOutcome(ResourceLocation dimension, int blockX, int blockZ,
                                              ResourceLocation structureId, boolean succeeded) {
        ZoneInstance zoneInstance = getZoneAtCached(dimension, blockX, blockZ);

        if (zoneInstance == null) {
            return;
        }

        ZoneDefinition def = WorldZoneConfig.findDefinition(zoneInstance.zoneType);
        if (def == null) {
            return;
        }
        String idStr = idString(structureId);
        ZoneDefinition.StructureEntry entry = def.findStructure(idStr);
        if (entry == null || !entry.guaranteePlacement) {
            return;
        }

        synchronized (zoneInstance) {
            if (zoneInstance.getStructurePlacementCount(idStr) > 0 || isPlacementGiveUp(zoneInstance, idStr)) {
                return;
            }

            if (succeeded) {
                zoneInstance.initGuaranteeAttempts().remove(idStr);
            } else {
                int failed = zoneInstance.initGuaranteeAttempts().merge(idStr, 1, Integer::sum);
                if (failed >= GUARANTEE_MAX_ATTEMPTS) {
                    ModLogger.warn("guaranteePlacement: could not place '{}' in zone '{}' after {} attempts; "
                            + "aborting.", idStr, zoneInstance.zoneType, GUARANTEE_MAX_ATTEMPTS);
                }
            }
        }
        persistZones(dimension);
    }

    public static boolean isBiomeWidenedForStructure(ResourceLocation dimension, int blockX, int blockZ,
                                                     ResourceLocation structureId, Holder<Biome> biome) {
        ZoneInstance zone = getZoneAtCached(dimension, blockX, blockZ);

        if (zone == null) {
            return false;
        }

        ZoneDefinition def = WorldZoneConfig.findDefinition(zone.zoneType);
        if (def == null) {
            return false;
        }
        String idStr = idString(structureId);
        ZoneDefinition.StructureEntry entry = resolveEntry(def, idStr);

        // this zone doesn't register the structure, don't widen for it
        if (entry == null) {
            return false;
        }
        for (BiomeMatcher matcher : def.biomes) {
            if (matcher.matches(biome)) {
                return true;
            }
        }

        // The biome forced over this spot (own or an ancestor's) is what the structure's biome check
        // actually samples here, so it counts as allowed for every structure the zone registers.
        if (!hasAnyForcedBiomeZone) {
            return false;
        }
        ZoneInstance forcing = findForcedBiomeZone(dimension, blockX, blockZ);
        return forcing != null && forcing.forcedBiomeHolder == biome;
    }

    /** The smallest zone containing this column that forces a (resolvable) biome, or null. */
    private static ZoneInstance findForcedBiomeZone(ResourceLocation dimension, int blockX, int blockZ) {
        ZoneInstance best = null;
        for (ZoneInstance zoneInstance : zonesNear(dimension, blockX, blockZ)) {
            if (zoneInstance.forcedBiomeHolder == null || !zoneInstance.contains(blockX, blockZ)) {
                continue;
            }
            if (best == null || zoneInstance.radius < best.radius) {
                best = zoneInstance;
            }
        }
        return best;
    }

    /**
     * ensureBiomeForTheWholeZone lookup. Only overrides biome within SURFACE_DEPTH_MARGIN of the
     * REAL local terrain height at this column.
     */
    @Nullable
    public static Holder<Biome> getForcedBiome(ResourceLocation dimension, int blockX, int blockY, int blockZ) {
        ZoneInstance best = findForcedBiomeZone(dimension, blockX, blockZ);
        if (best == null) {
            return null;
        }

        Integer surfaceY = resolveSurfaceY(dimension, blockX, blockZ);
        if (surfaceY == null || blockY < surfaceY - SURFACE_DEPTH_MARGIN) {
            return null; // underground: leave whatever the natural biome noise assigns
        }
        return best.forcedBiomeHolder;
    }

    /**
     * Flushes every dimension whose zone data changed since the last flush. Called on server stop.
     */
    public static synchronized void flushDirtyZones() {
        for (ResourceLocation key : DIRTY_DIMENSIONS) {
            ServerLevel level = LEVEL_BY_DIMENSION.get(key);
            List<ZoneInstance> zones = ZONES_BY_DIMENSION.get(key);
            if (level != null && zones != null) {
                writeZones(zoneFile(level, key), zones);
            }
        }

        DIRTY_DIMENSIONS.clear();
        LocatePositionsSavingHandler.flushPending();
    }

    public static void markDirty(ResourceLocation dimension) {
        persistZones(dimension);
    }

    /**
     * The surface height ensureBiomeForTheWholeZone measures its depth band from. A flattened
     * column's real generated surface IS its flattenY, not the natural terrain the noise would
     * have produced. flattenY is the minimum of 30 samples, so the natural height is higher than
     * it almost everywhere in the zone, and measuring from the natural height would leave the
     * forced-biome band floating in the air above the flattened ground while the ground itself
     * kept its natural biome. Unflattened columns still use the real pre-flatten height, which is
     * what COMPUTING_REAL_SURFACE exists to obtain.
     */
    private static Integer resolveSurfaceY(ResourceLocation dimension, int blockX, int blockZ) {
        int flattenY = getFlattenTargetYCached(dimension, blockX, blockZ);
        if (flattenY != Integer.MIN_VALUE) {
            // +1 to match getBaseHeight's own convention: the first air block ABOVE the surface,
            // which is exactly what the flatten override in NoiseBasedChunkGeneratorMixin returns.
            return flattenY + 1;
        }
        return computeSurfaceHeight(dimension, blockX, blockZ);
    }

    private static Integer computeSurfaceHeight(ResourceLocation dimension, int blockX, int blockZ) {
        ServerLevel level = LEVEL_BY_DIMENSION.get(dimension);
        if (level == null) {
            return null;
        }
        // getBaseHeight walks a whole noise column, and the biome source asks per quart cell --
        // 16 real samples per chunk. Surface height inside a zone varies slowly, so one sample at
        // the chunk centre stands in for the chunk.
        int sampleX = (blockX & SURFACE_SAMPLE_MASK) + 8;
        int sampleZ = (blockZ & SURFACE_SAMPLE_MASK) + 8;
        long key = (((long) sampleX) << 32) ^ (sampleZ & 0xffffffffL);
        FlattenColumnCache local = FLATTEN_COLUMN_CACHE.get();
        long epoch = zoneLookupEpoch;
        if (local.epoch != epoch) {
            resetFlattenCache(local, epoch);
        }
        if (local.surfaceKey == key && dimension.equals(local.surfaceDim)) {
            return local.surfaceValue;
        }

        Map<Long, Integer> cache = surfaceCacheFor(dimension);
        Integer cached = cache.get(key);
        if (cached != null) {
            rememberSurface(local, dimension, key, cached);
            return cached;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        COMPUTING_REAL_SURFACE.set(true);
        int height;
        try {
            height = generator.getBaseHeight(sampleX, sampleZ, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
        } finally {
            COMPUTING_REAL_SURFACE.set(false);
        }
        cache.put(key, height);
        rememberSurface(local, dimension, key, height);
        return height;
    }

    private static void rememberSurface(FlattenColumnCache local, ResourceLocation dimension, long key, int value) {
        local.surfaceDim = dimension;
        local.surfaceKey = key;
        local.surfaceValue = value;
    }

    private static Map<Long, Integer> surfaceCacheFor(ResourceLocation dimKey) {
        return SURFACE_HEIGHT_CACHE.computeIfAbsent(dimKey, k ->
                Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
                        return size() > SURFACE_CACHE_MAX;
                    }
                }));
    }

    /**
     * `dimension` may be null when the caller couldn't resolve it, in
     * which case every loaded dimension is scanned. A false positive there just means an extra
     * eligible chunk that isStructureAllowed correctly rejects afterward.
     */
    public static float getMaxExtraChance(ResourceLocation dimension, int blockX, int blockZ,
                                          List<ResourceLocation> structureIds) {
        if (!hasAnyIncreasedDensity) {
            return 0f;
        }
        if (dimension != null) {
            return maxExtraChanceIn(dimension, blockX, blockZ, structureIds);
        }
        float maxExtra = 0f;
        for (ResourceLocation dim : ZONE_INDEX_BY_DIMENSION.keySet()) {
            maxExtra = Math.max(maxExtra, maxExtraChanceIn(dim, blockX, blockZ, structureIds));
        }
        return maxExtra;
    }

    public static boolean hasAnyIncreasedDensity() {
        return hasAnyIncreasedDensity;
    }

    public static boolean hasAnyFlattenZone() {
        return hasAnyFlattenZone;
    }

    public static boolean hasAnyForcedBiomeZone() {
        return hasAnyForcedBiomeZone;
    }

    public static boolean hasAnyGuaranteePlacement() {
        return hasAnyGuaranteePlacement;
    }

    /**
     * Whether anything in this dimension can possibly change a structure's fate: any zone at all,
     * or a zoneOnlyStructures entry (which gates even OUTSIDE zones). Lets the Structure mixin
     * skip its own work entirely in zoneless dimensions.
     */
    public static boolean isGatingActive(ResourceLocation dimension) {
        if (!ZONE_RESTRICTED_STRUCTURES.isEmpty()) {
            return true;
        }
        ZoneIndex index = ZONE_INDEX_BY_DIMENSION.get(dimension);
        return index != null && (!index.cells.isEmpty() || !index.oversized.isEmpty());
    }

    /**
     * Chunk-level flatten test, for callers that would otherwise pay a per-column lookup for a
     * whole chunk's worth of cells. Cached per thread for the chunk last asked about, since
     * fillArray is called many times for the same chunk.
     */
    public static boolean chunkHasFlattenZone(ResourceLocation dimension, int blockX, int blockZ) {
        if (!hasAnyFlattenZone) {
            return false;
        }
        FlattenColumnCache c = FLATTEN_COLUMN_CACHE.get();
        long epoch = zoneLookupEpoch;
        if (c.epoch != epoch) {
            resetFlattenCache(c, epoch);
        }
        long chunkKey = (((long) (blockX >> 4)) << 32) ^ ((blockZ >> 4) & 0xffffffffL);
        if (c.chunkKey == chunkKey && dimension.equals(c.chunkDim)) {
            return c.chunkHasFlatten;
        }
        boolean result = computeChunkHasFlattenZone(dimension, blockX, blockZ);
        c.chunkKey = chunkKey;
        c.chunkDim = dimension;
        c.chunkHasFlatten = result;
        return result;
    }

    private static boolean computeChunkHasFlattenZone(ResourceLocation dimension, int blockX, int blockZ) {
        int minBlockX = blockX & ~15;
        int minBlockZ = blockZ & ~15;
        for (ZoneInstance zoneInstance : zonesNearChunk(dimension, minBlockX, minBlockZ)) {
            if (zoneInstance.flattenY == null) {
                continue;
            }
            int closestX = Math.max(minBlockX, Math.min(zoneInstance.centerX, minBlockX + 15));
            int closestZ = Math.max(minBlockZ, Math.min(zoneInstance.centerZ, minBlockZ + 15));
            long dx = zoneInstance.centerX - closestX;
            long dz = zoneInstance.centerZ - closestZ;
            long r = zoneInstance.radius;
            if (dx * dx + dz * dz <= r * r) {
                return true;
            }
        }
        return false;
    }

    /**
     * Marks one freshly-noise-generated chunk against every zone containing its center, and
     * releases a zone's placement bookkeeping once its whole circle has generated. This is the
     * only per-zone data that is safe to free while the world runs: nothing can be placed in a
     * finished zone again, so its counts, guarantee attempts and spaceAround boxes are dead --
     * only the recorded positions /locate reads survive.
     */
    public static void recordChunkGenerated(ResourceLocation dimension, int chunkX, int chunkZ) {
        if (!hasAnyReclaimableZoneData) {
            return; // nothing in this config produces reclaimable per-zone data
        }
        int blockX = (chunkX << 4) + 8;
        int blockZ = (chunkZ << 4) + 8;
        List<ZoneInstance> zones = zonesNear(dimension, blockX, blockZ);
        if (zones.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (ZoneInstance zoneInstance : zones) {
            if (zoneInstance.spaceAroundRetired || !zoneInstance.contains(blockX, blockZ)) {
                continue;
            }
            ZoneDefinition def = WorldZoneConfig.findDefinition(zoneInstance.zoneType);
            if (def == null || !def.hasReclaimableData) {
                continue; // nothing this zone holds would ever be released
            }
            int status = zoneInstance.noteChunkGenerated(chunkX, chunkZ);
            if (status == ChunkInZoneLoadingStatus.UNCHANGED.value) {
                continue;
            }
            changed = true;
            if (status == ChunkInZoneLoadingStatus.RETIRED.value) {
                ModLogger.debug("Zone '{}' at [{}, {}] fully generated. releasing its placement bookkeeping.",
                        zoneInstance.zoneType, zoneInstance.centerX, zoneInstance.centerZ);
            }
        }
        if (changed) {
            persistZones(dimension);
        }
    }

    public static synchronized void unloadDimension(ResourceLocation dimension) {
        ServerLevel level = LEVEL_BY_DIMENSION.get(dimension);
        List<ZoneInstance> zones = ZONES_BY_DIMENSION.get(dimension);
        if (DIRTY_DIMENSIONS.remove(dimension) && level != null && zones != null) {
            writeZones(zoneFile(level, dimension), zones);
        }

        LocatePositionsSavingHandler.closeDimension(dimension);
        LEVEL_BY_DIMENSION.remove(dimension);
        ZONES_BY_DIMENSION.remove(dimension);
        ZONE_INDEX_BY_DIMENSION.remove(dimension);
        SURFACE_HEIGHT_CACHE.remove(dimension);
        FRESHLY_GENERATED.remove(dimension);
        DIMENSION_BY_GENERATOR = withoutDimension(DIMENSION_BY_GENERATOR, dimension);
        List<BiomeSource> sources = new ArrayList<>();
        DIMENSION_BY_BIOME_SOURCE.forEach((source, dim) -> {
            if (dim.equals(dimension)) {
                sources.add(source);
            }
        });
        clearBiomeSourceContexts(sources);
        DIMENSION_BY_BIOME_SOURCE = withoutDimension(DIMENSION_BY_BIOME_SOURCE, dimension);
        DIMENSION_BY_RANDOM_STATE = withoutDimension(DIMENSION_BY_RANDOM_STATE, dimension);
        zoneLookupEpoch++;
    }

    public static float densityBaseChance() {
        return WorldZoneConfig.densityBaseChance();
    }

    /**
     * Deterministic hash normalized to [0, 1). Seed-independent by design.
     * Each salt is folded in and fully avalanched (SplitMix64 finalizer) before the next, so
     * neighbouring inputs give uncorrelated outputs instead of a linear ramp.
     */
    public static double deterministicRoll(long saltA, long saltB, long saltC) {
        long h = mix64(saltA);
        h = mix64(h + saltB * 0x9E3779B97F4A7C15L);
        h = mix64(h + saltC * 0xC2B2AE3D27D4EB4FL);
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Jittered sub-grid: the zone is divided into spreadDistance-sized cells, and each cell has
     * exactly ONE candidate chunk, at a pseudo-random offset inside that cell. Salted by the
     * structure's own resource id AND the world seed, so different structure types get
     * completely unrelated cell targets and never spread against each other, and the same
     * seed + config always reproduces the same layout while a different seed does not.
     * this is implemented in order to achieve a more random structure layout
     */
    private static boolean passesSpreadCell(String structureIdStr, int spreadDistance, long worldSeed,
                                            int blockX, int blockZ) {
        if (spreadDistance <= 0) {
            return true; // explicitly disabled
        }
        long salt = structureIdStr.hashCode() * 31L + worldSeed;
        int cellX = Math.floorDiv(blockX, spreadDistance);
        int cellZ = Math.floorDiv(blockZ, spreadDistance);
        int targetX = cellX * spreadDistance
                + (int) (deterministicRoll(salt, cellX, cellZ) * spreadDistance);
        int targetZ = cellZ * spreadDistance
                + (int) (deterministicRoll(salt ^ 0x9E3779B97F4A7C15L, cellX, cellZ) * spreadDistance);
        return (targetX >> 4) == (blockX >> 4) && (targetZ >> 4) == (blockZ >> 4);
    }

    public static List<ZoneInstance> getAllZones(ResourceLocation dimension) {
        return ZONES_BY_DIMENSION.getOrDefault(dimension, List.of());
    }

    /**
     * Interned toString() for a structure id. Falls back to a plain toString() for an id the
     * cache has never seen, so correctness never depends on the cache being populated.
     */
    public static String idString(ResourceLocation id) {
        String cached = ID_STRING_CACHE.get(id);
        return cached != null ? cached : id.toString();
    }

    /**
     * The zone's own entry for a structure, or only if it has none and obeyParent is on the parent's.
     */
    public static ZoneDefinition.StructureEntry resolveEntry(ZoneDefinition def, String structureId) {
        ZoneDefinition.StructureEntry entry = def.findStructure(structureId);
        if (entry == null && def.obeyParent && def.parentZone != null) {
            ZoneDefinition parentDef = WorldZoneConfig.findDefinition(def.parentZone);
            entry = parentDef != null ? parentDef.findStructure(structureId) : null;
        }
        return entry;
    }

    /**
     * The resolved entry for whatever zone covers this position, or null if there is no zone or
     * the zone doesn't register the structure. One lookup for callers that would otherwise repeat
     * getZoneAtCached + findDefinition + resolveEntry.
     */
    public static ZoneDefinition.StructureEntry resolveEntryAt(ResourceLocation dimension, int blockX, int blockZ,
                                                               String structureIdStr) {
        ZoneInstance zone = getZoneAtCached(dimension, blockX, blockZ);
        if (zone == null) {
            return null;
        }
        ZoneDefinition def = WorldZoneConfig.findDefinition(zone.zoneType);
        return def == null ? null : resolveEntry(def, structureIdStr);
    }

    /**
     * The single top-level instance containing this point (top-level zones never overlap), or null.
     */
    public static ZoneInstance findRootZone(ResourceLocation dimension, int blockX, int blockZ) {
        for (ZoneInstance zoneInstance : zonesNear(dimension, blockX, blockZ)) {
            if (!zoneInstance.contains(blockX, blockZ)) {
                continue;
            }
            ZoneDefinition def = WorldZoneConfig.findDefinition(zoneInstance.zoneType);
            if (def != null && (def.parentZone == null || def.parentZone.isEmpty())) {
                return zoneInstance;
            }
        }
        return null;
    }

    private static boolean isPlacementGiveUp(ZoneInstance zoneInstance, String structureId) {
        return zoneInstance.getGuaranteePlacementAttemptsCount(structureId) >= GUARANTEE_MAX_ATTEMPTS;
    }

    /**
     * spaceAround (0 = none) from the resolved entry: the zone's own entry for the structure, or,
     * only if it has none and obeyParent is on, the parent's. A child's own entry fully replaces
     * the parent's.
     */
    public static int resolveSpaceAround(ResourceLocation dimension, int blockX, int blockZ, ResourceLocation structureId) {
        ZoneDefinition.StructureEntry entry = resolveEntryAt(dimension, blockX, blockZ, idString(structureId));
        return entry != null ? entry.spaceAround : 0;
    }

    /**
     * shouldFlattenTerrain lookup.
     */
    private static Integer getFlattenTargetY(ResourceLocation dimension, int blockX, int blockZ) {
        ZoneInstance best = null;
        for (ZoneInstance zoneInstance : zonesNear(dimension, blockX, blockZ)) {
            if (zoneInstance.flattenY == null || !zoneInstance.contains(blockX, blockZ)) {
                continue;
            }
            if (best == null || zoneInstance.radius < best.radius) {
                best = zoneInstance;
            }
        }
        return best != null ? best.flattenY : null;
    }

    /**
     * flattenY for the preliminary surface (FlattenedPreliminarySurfaceFunction). A column inside a
     * flatten zone always uses that zone's own Y; otherwise the smallest flatten zone within
     * PRELIMINARY_SURFACE_MARGIN of its edge. Integer.MIN_VALUE when none applies.
     */
    public static int getPreliminarySurfaceFlattenY(ResourceLocation dimension, int blockX, int blockZ) {
        if (!hasAnyFlattenZone) {
            return Integer.MIN_VALUE;
        }
        int exact = getFlattenTargetYCached(dimension, blockX, blockZ);
        if (exact != Integer.MIN_VALUE) {
            return exact;
        }
        ZoneInstance best = null;
        for (ZoneInstance zoneInstance : zonesNear(dimension, blockX, blockZ)) {
            if (zoneInstance.flattenY == null) {
                continue;
            }
            long dx = blockX - zoneInstance.centerX;
            long dz = blockZ - zoneInstance.centerZ;
            long r = (long) zoneInstance.radius + PRELIMINARY_SURFACE_MARGIN;
            if (dx * dx + dz * dz > r * r) {
                continue;
            }
            if (best == null || zoneInstance.radius < best.radius) {
                best = zoneInstance;
            }
        }
        return best != null ? best.flattenY : Integer.MIN_VALUE;
    }

    private static long cellKey(int blockX, int blockZ) {
        return (((long) Math.floorDiv(blockX, ZONE_GRID)) << 32)
                ^ (Math.floorDiv(blockZ, ZONE_GRID) & 0xffffffffL);
    }

    private static void addCell(ZoneIndex index, long cell, List<ZoneInstance> out) {
        List<ZoneInstance> zones = index.cells.get(cell);
        if (zones != null) {
            out.addAll(zones);
        }
    }

    private static void indexZones(ResourceLocation dim, List<ZoneInstance> zones) {
        ZoneIndex index = new ZoneIndex();
        for (ZoneInstance zoneInstance : zones) {
            // Flatten zones reach PRELIMINARY_SURFACE_MARGIN past their radius (getPreliminarySurfaceFlattenY),
            // so they must be findable from every cell that margin touches. contains() still tests the exact radius.
            int reach = zoneInstance.radius + (zoneInstance.flattenY != null ? PRELIMINARY_SURFACE_MARGIN : 0);
            int minCx = Math.floorDiv(zoneInstance.centerX - reach, ZONE_GRID);
            int maxCx = Math.floorDiv(zoneInstance.centerX + reach, ZONE_GRID);
            int minCz = Math.floorDiv(zoneInstance.centerZ - reach, ZONE_GRID);
            int maxCz = Math.floorDiv(zoneInstance.centerZ + reach, ZONE_GRID);
            long span = (long) (maxCx - minCx + 1) * (maxCz - minCz + 1);
            if (span > MAX_CELLS_PER_ZONE) {
                index.oversized.add(zoneInstance);
                continue;
            }
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    index.cells.computeIfAbsent((((long) cx) << 32) ^ (cz & 0xffffffffL),
                            k -> new ArrayList<>()).add(zoneInstance);
                }
            }
        }
        index.cells.replaceAll((k, v) -> List.copyOf(v));
        ZONE_INDEX_BY_DIMENSION.put(dim, index);
    }

    public static List<ZoneInstance> zonesNear(ResourceLocation dimension, int blockX, int blockZ) {
        ZoneIndex index = ZONE_INDEX_BY_DIMENSION.get(dimension);
        if (index == null) {
            return List.of();
        }
        List<ZoneInstance> cell = index.cells.getOrDefault(cellKey(blockX, blockZ), List.of());
        if (index.oversized.isEmpty()) {
            return cell;
        }
        if (cell.isEmpty()) {
            return Collections.unmodifiableList(index.oversized);
        }
        List<ZoneInstance> merged = new ArrayList<>(cell.size() + index.oversized.size());
        merged.addAll(cell);
        merged.addAll(index.oversized);
        return merged;
    }

    /**
     * Every zone whose cell(s) overlap this chunk. A 16-block chunk spans at most 2 cells per
     * axis, so the four corners cover it exactly.
     */
    public static List<ZoneInstance> zonesNearChunk(ResourceLocation dimension, int minBlockX, int minBlockZ) {
        ZoneIndex index = ZONE_INDEX_BY_DIMENSION.get(dimension);
        if (index == null) {
            return List.of();
        }
        int maxBlockX = minBlockX + 15;
        int maxBlockZ = minBlockZ + 15;
        long c00 = cellKey(minBlockX, minBlockZ);
        long c10 = cellKey(maxBlockX, minBlockZ);
        long c01 = cellKey(minBlockX, maxBlockZ);
        long c11 = cellKey(maxBlockX, maxBlockZ);

        if (c00 == c10 && c00 == c01 && c00 == c11 && index.oversized.isEmpty()) {
            return index.cells.getOrDefault(c00, List.of()); // common case: no merge, no allocation
        }

        List<ZoneInstance> merged = new ArrayList<>(index.oversized);
        addCell(index, c00, merged);
        if (c10 != c00) addCell(index, c10, merged);
        if (c01 != c00 && c01 != c10) addCell(index, c01, merged);
        if (c11 != c00 && c11 != c10 && c11 != c01) addCell(index, c11, merged);

        return merged;
    }

    public static int getFlattenTargetYCached(ResourceLocation dimension, int blockX, int blockZ) {
        if (!hasAnyFlattenZone) {
            return Integer.MIN_VALUE;
        }
        FlattenColumnCache c = FLATTEN_COLUMN_CACHE.get();
        long epoch = zoneLookupEpoch;
        if (c.epoch != epoch) {
            resetFlattenCache(c, epoch);
        }
        long packed = (((long) blockX) << 32) ^ (blockZ & 0xffffffffL);
        int slot = flattenSlot(blockX, blockZ, dimension);
        if (c.keys[slot] == packed && dimension.equals(c.dims[slot])) {
            return c.values[slot];
        }
        Integer computed = getFlattenTargetY(dimension, blockX, blockZ);
        int result = computed == null ? Integer.MIN_VALUE : computed;
        c.keys[slot] = packed;
        c.dims[slot] = dimension;
        c.values[slot] = result;

        return result;
    }

    private static int flattenSlot(int blockX, int blockZ, ResourceLocation dimension) {
        int h = blockX * 0x9E3779B1 + blockZ * 0x85EBCA77 + dimension.hashCode();
        h ^= h >>> 15;
        return h & FLATTEN_CACHE_MASK;
    }

    private static void resetFlattenCache(FlattenColumnCache c, long epoch) {
        c.epoch = epoch;
        java.util.Arrays.fill(c.keys, Long.MIN_VALUE);
        java.util.Arrays.fill(c.dims, null);
        c.chunkKey = Long.MIN_VALUE;
        c.chunkDim = null;
        c.surfaceKey = Long.MIN_VALUE;
        c.surfaceDim = null;
    }

    /**
     * A placement count is only ever read by maxCount and guaranteePlacement.
     */
    private static boolean needsPlacementCount(ZoneInstance zoneInstance, String structureIdStr) {
        ZoneDefinition def = WorldZoneConfig.findDefinition(zoneInstance.zoneType);
        if (def == null) {
            return false;
        }
        ZoneDefinition.StructureEntry entry = resolveEntry(def, structureIdStr);

        return entry != null && (entry.maxCount > 0 || entry.guaranteePlacement);
    }

    /**
     * Copy-on-write: the returned map is NEVER mutated after it is published to its volatile field,
     * so it is handed out bare (no unmodifiable wrapper) to keep the hot-path reads one call deep.
     */
    private static <K> Map<K, ResourceLocation> withEntry(Map<K, ResourceLocation> map, K key, ResourceLocation value) {
        Map<K, ResourceLocation> copy = new IdentityHashMap<>(map);
        copy.put(key, value);
        return copy;
    }

    /** Copy-on-write, same never-mutated-after-publish contract as withEntry. */
    private static <K> Map<K, ResourceLocation> withoutDimension(Map<K, ResourceLocation> map, ResourceLocation dimension) {
        if (!map.containsValue(dimension)) {
            return map;
        }
        Map<K, ResourceLocation> copy = new IdentityHashMap<>(map);
        copy.values().removeIf(dimension::equals);
        return copy;
    }

    private static int zoneLookupSlot(int blockX, int blockZ, ResourceLocation dimension) {
        int h = blockX * 0x9E3779B1 + blockZ * 0x85EBCA77 + dimension.hashCode();
        h ^= h >>> 15;
        return h & ZONE_LOOKUP_MASK;
    }

    private static boolean forcesGuaranteedEligibilityIn(ResourceLocation dimension, int blockX, int blockZ,
                                                         List<ResourceLocation> structureIds) {
        ZoneIndex index = ZONE_INDEX_BY_DIMENSION.get(dimension);
        // A dimension with no zones at all can never force anything.
        if (index == null || (index.cells.isEmpty() && index.oversized.isEmpty())) {
            return false;
        }
        for (ZoneInstance zoneInstance : zonesNear(dimension, blockX, blockZ)) {
            if (!zoneInstance.contains(blockX, blockZ)) {
                continue;
            }
            ZoneDefinition def = WorldZoneConfig.findDefinition(zoneInstance.zoneType);
            if (def == null) {
                continue;
            }
            for (ResourceLocation id : structureIds) {
                String idStr = idString(id);
                ZoneDefinition.StructureEntry entry = def.findStructure(idStr);

                if ((entry == null || !entry.guaranteePlacement)
                        || zoneInstance.getStructurePlacementCount(idStr) > 0
                        || isPlacementGiveUp(zoneInstance, idStr)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static float maxExtraChanceIn(ResourceLocation dimension, int blockX, int blockZ,
                                          List<ResourceLocation> structureIds) {
        ZoneIndex index = ZONE_INDEX_BY_DIMENSION.get(dimension);
        if (index == null || (index.cells.isEmpty() && index.oversized.isEmpty())) {
            return 0f;
        }
        float maxExtra = 0f;
        for (ZoneInstance zoneInstance : zonesNear(dimension, blockX, blockZ)) {
            if (!zoneInstance.contains(blockX, blockZ)) {
                continue;
            }
            ZoneDefinition def = WorldZoneConfig.findDefinition(zoneInstance.zoneType);
            if (def == null) {
                continue;
            }
            for (ResourceLocation id : structureIds) {
                ZoneDefinition.StructureEntry entry = def.findStructure(idString(id));
                if (entry != null && entry.density > 1.0f) {
                    maxExtra = Math.max(maxExtra, entry.density - 1.0f);
                }
            }
        }
        return maxExtra;
    }

    // ---- File persistence ----

    private static Path dataDir(ServerLevel level) {
        Path dataDir = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(Zones.MOD_ID);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            ModLogger.error("Could not create data directory {}", dataDir, e);
        }
        return dataDir;
    }

    private static Path zoneFile(ServerLevel level, ResourceLocation dimensionId) {
        return dataDir(level).resolve(dimensionId.toString().replace(':', '_') + "_zones.json");
    }

    private static void persistZones(ResourceLocation dimension) {
        DIRTY_DIMENSIONS.add(dimension);
    }

    private static List<ZoneInstance> readZones(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            Type listType = new TypeToken<List<ZoneInstance>>() {
            }.getType();
            List<ZoneInstance> list = GSON.fromJson(reader, listType);
            return list != null ? list : new ArrayList<>();
        } catch (IOException e) {
            ModLogger.error("Failed to read zone data from {}", file, e);
            return new ArrayList<>();
        }
    }

    private static void writeZones(Path file, List<ZoneInstance> zones) {
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(zones, writer);
        } catch (IOException e) {
            ModLogger.error("Failed to write zone data to {}", file, e);
        }
    }

    /**
     * Serializes every dirty dimension's zone list. Zone instances are mutated under their own
     * monitors now, so a list can be read here while a worker updates one instance's counters; the
     * maps involved are concurrent, and anything missed is written by the next flush.
     */
    public static synchronized Map<Path, String> snapshotDirtyZones() {
        if (DIRTY_DIMENSIONS.isEmpty()) {
            return Map.of();
        }
        Map<Path, String> pending = new HashMap<>();
        for (ResourceLocation key : DIRTY_DIMENSIONS) {
            ServerLevel level = LEVEL_BY_DIMENSION.get(key);
            List<ZoneInstance> zones = ZONES_BY_DIMENSION.get(key);
            if (level != null && zones != null) {
                pending.put(zoneFile(level, key), GSON.toJson(zones));
            }
        }
        DIRTY_DIMENSIONS.clear();
        return pending;
    }
}
