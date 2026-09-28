package net.flameslight.zones.types.biome;

import net.flameslight.zones.ZoneOnlyBiomeFilter;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the biome source mixin needs about its own biome source, stored ON that source
 * (ZoneBiomeSourceHolder) at level load, so a biome lookup reads one field instead of doing
 * identity-map lookups for the dimension and the zoneOnlyBiomes filter.
 */
public record ZoneBiomeSourceContext(ResourceLocation dimension, @Nullable ZoneOnlyBiomeFilter filter) {
}
