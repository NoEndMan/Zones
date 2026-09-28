package net.flameslight.zones;

import net.flameslight.zones.config.WorldZoneConfig;
import net.flameslight.zones.types.ZoneInstance;
import net.flameslight.zones.types.zoneDefinition.ZoneDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * zoneOnlyMobs and a zone's 'mobs'. Vanilla picks every natural spawn (and re-checks the pick right
 * before spawning, canSpawnMobAt) from the list this event hands out, so editing it here is the
 * whole "biome check": zone-only mobs leave every list, and inside a zone its own mobs join the
 * list for their category, replacing any biome entry for the same mob. Never spawns anything
 * itself. LOWEST priority so entries other mods add are seen too.
 *
 * Runs on every natural spawn attempt, so everything it reads is precomputed per world
 * (WorldZoneConfig.build) and the checks are ordered cheapest-first.
 */
@Mod.EventBusSubscriber(modid = Zones.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZoneSpawnHandler {
    private ZoneSpawnHandler() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPotentialSpawns(LevelEvent.PotentialSpawns event) {
        if (!WorldZoneConfig.isSpawnRulesActive()) {
            return;
        }
        MobCategory category = event.getMobCategory();
        EntityType<?>[] zoneOnly = WorldZoneConfig.getZoneOnlyMobs();

        // The zone lookup only matters for categories some zone actually adds mobs to.
        MobSpawnSettings.SpawnerData[] additions = null;
        EntityType<?>[] replaced = null;
        if (WorldZoneConfig.hasZoneMobsIn(category) && event.getLevel() instanceof ServerLevel level) {
            BlockPos pos = event.getPos();
            ZoneInstance zone = ZoneManager.getZoneAtCached(level.dimension().location(), pos.getX(), pos.getZ());
            ZoneDefinition def = zone == null ? null : WorldZoneConfig.findDefinition(zone.zoneType);
            if (def != null) {
                additions = def.zoneSpawnsFor(category);
                if (additions != null) {
                    replaced = def.zoneSpawnTypes;
                }
            }
        }
        if (zoneOnly.length == 0 && additions == null) {
            return;
        }

        // Only allocate when something actually has to go: the common case outside zones is a
        // single scan that finds nothing.
        List<MobSpawnSettings.SpawnerData> current = event.getSpawnerDataList();
        MobSpawnSettings.SpawnerData[] toRemove = null;
        int removeCount = 0;
        for (int i = 0, n = current.size(); i < n; i++) {
            MobSpawnSettings.SpawnerData data = current.get(i);
            if (contains(zoneOnly, data.type) || (replaced != null && contains(replaced, data.type))) {
                if (toRemove == null) {
                    toRemove = new MobSpawnSettings.SpawnerData[n];
                }
                toRemove[removeCount++] = data;
            }
        }
        for (int i = 0; i < removeCount; i++) {
            event.removeSpawnerData(toRemove[i]);
        }
        if (additions != null) {
            for (MobSpawnSettings.SpawnerData data : additions) {
                event.addSpawnerData(data);
            }
        }
    }

    private static boolean contains(EntityType<?>[] types, EntityType<?> type) {
        for (EntityType<?> candidate : types) {
            if (candidate == type) {
                return true;
            }
        }
        return false;
    }
}
