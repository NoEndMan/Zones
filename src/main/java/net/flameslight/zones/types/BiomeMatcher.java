package net.flameslight.zones.types;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.Optional;

/**
 * Matches either an exact biome registry name ("minecraft:desert") or a biome
 * tag ("#minecraft:is_overworld", '#'-prefixed). Used everywhere a config
 * entry accepts a list of biomes, so a single zone can whitelist by tag
 * instead of enumerating every matching biome by hand.
 */
public final class BiomeMatcher {
    private final ResourceLocation exactId;
    private final TagKey<Biome> tag;

    private BiomeMatcher(ResourceLocation exactId, TagKey<Biome> tag) {
        this.exactId = exactId;
        this.tag = tag;
    }

    /** Returns null if the string isn't a valid resource location/tag id. */
    public static BiomeMatcher parse(String raw) {
        String s = raw.trim();
        if (s.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(s.substring(1));
            if (id == null) {
                return null;
            }
            return new BiomeMatcher(null, TagKey.create(Registries.BIOME, id));
        }
        ResourceLocation id = ResourceLocation.tryParse(s);
        if (id == null) {
            return null;
        }
        return new BiomeMatcher(id, null);
    }

    /** The exact biome id, or null for a tag matcher. */
    public ResourceLocation exactId() {
        return exactId;
    }

    public boolean matches(Holder<Biome> biome) {
        if (tag != null) {
            return biome.is(tag);
        }
        return biome.unwrapKey().map(key -> key.location().equals(exactId)).orElse(false);
    }
}
