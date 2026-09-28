package net.flameslight.zones.types.biome;

import org.jetbrains.annotations.Nullable;

/** Implemented on MultiNoiseBiomeSource by MultiNoiseBiomeSourceMixin. */
public interface ZoneBiomeSourceHolder {
    @Nullable
    ZoneBiomeSourceContext structurezones$getContext();

    void structurezones$setContext(@Nullable ZoneBiomeSourceContext context);
}
