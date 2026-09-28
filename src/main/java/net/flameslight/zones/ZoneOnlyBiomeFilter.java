package net.flameslight.zones;

import com.mojang.datafixers.util.Pair;
import net.flameslight.zones.logger.ModLogger;
import net.flameslight.zones.mixins.MultiNoiseBiomeSourceAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * zoneOnlyBiomes for one dimension's biome source. Everything costly (resolving ids, rebuilding
 * the climate table without those biomes) happens once, at level load. At runtime a natural
 * biome is only looked up in an identity set, and a replacement is searched only where a
 * zone-only biome would actually have generated.
 */
public final class ZoneOnlyBiomeFilter {
    /** Set while the natural biome is being looked up, so the biome source mixin steps aside. */
    private static final ThreadLocal<boolean[]> IN_NATURAL_LOOKUP = ThreadLocal.withInitial(() -> new boolean[1]);

    private final Set<Holder<Biome>> zoneOnly;
    /** The source's own climate table minus the zone-only biomes; null if nothing is left in it. */
    @Nullable
    private final Climate.ParameterList<Holder<Biome>> replacements;

    private ZoneOnlyBiomeFilter(Set<Holder<Biome>> zoneOnly, @Nullable Climate.ParameterList<Holder<Biome>> replacements) {
        this.zoneOnly = zoneOnly;
        this.replacements = replacements;
    }

    /** Null if there is nothing to filter for this source. */
    @Nullable
    public static ZoneOnlyBiomeFilter create(BiomeSource biomeSource, Registry<Biome> biomes,
                                             List<ResourceLocation> ids, ResourceLocation dimension) {
        if (ids.isEmpty()) {
            return null;
        }
        if (!(biomeSource instanceof MultiNoiseBiomeSource)) {
            ModLogger.debug("zoneOnlyBiomes: {} doesn't use a multi-noise biome source; nothing filtered there.",
                    dimension);
            return null;
        }
        Set<Holder<Biome>> zoneOnly = new HashSet<>();
        for (ResourceLocation id : ids) {
            Holder<Biome> holder = biomes.getHolder(ResourceKey.create(Registries.BIOME, id)).orElse(null);
            if (holder == null) {
                ModLogger.warn("zoneOnlyBiomes: '{}' is not a known biome; ignored.", id);
            } else {
                zoneOnly.add(holder);
            }
        }
        if (zoneOnly.isEmpty()) {
            return null;
        }

        List<Pair<Climate.ParameterPoint, Holder<Biome>>> kept = new ArrayList<>();
        for (Pair<Climate.ParameterPoint, Holder<Biome>> point
                : ((MultiNoiseBiomeSourceAccessor) biomeSource).structurezones$parameters().values()) {
            if (!zoneOnly.contains(point.getSecond())) {
                kept.add(point);
            }
        }
        Climate.ParameterList<Holder<Biome>> replacements = kept.isEmpty() ? null : new Climate.ParameterList<>(kept);
        return new ZoneOnlyBiomeFilter(Collections.unmodifiableSet(zoneOnly), replacements);
    }

    public static boolean isInNaturalLookup() {
        return IN_NATURAL_LOOKUP.get()[0];
    }

    public boolean isZoneOnly(Holder<Biome> biome) {
        return zoneOnly.contains(biome);
    }

    /**
     * The biome that generates here naturally, zone-only biomes swapped for the nearest remaining
     * biome of this climate. Asks the source itself (so TerraBlender or any other hook answers),
     * with the mixin stepped aside for that nested call.
     */
    public Holder<Biome> naturalBiome(BiomeSource source, int x, int y, int z, Climate.Sampler sampler) {
        boolean[] guard = IN_NATURAL_LOOKUP.get();
        Holder<Biome> natural;
        guard[0] = true;
        try {
            natural = source.getNoiseBiome(x, y, z, sampler);
        } finally {
            guard[0] = false;
        }
        if (replacements == null || !zoneOnly.contains(natural)) {
            return natural;
        }
        return replacements.findValue(sampler.sample(x, y, z));
    }
}
