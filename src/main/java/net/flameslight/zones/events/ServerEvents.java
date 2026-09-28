package net.flameslight.zones.events;

import net.flameslight.zones.SpawningInsideZoneHandler;
import net.flameslight.zones.ZoneManager;
import net.flameslight.zones.ZonePersistenceHandler;
import net.flameslight.zones.Zones;
import net.flameslight.zones.commands.ZoneLocateCommand;
import net.flameslight.zones.config.WorldZoneConfig;
import net.flameslight.zones.logger.ModLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Zones.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerEvents {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ZoneLocateCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            // Decides WHICH config this world runs on, and whether this mod touches it at all.
            // Must run before loadOrGenerate.
            if (!WorldZoneConfig.loadOrCreate(serverLevel)) {
                // Nothing registered -> every mixin bails on its own null/flag check, so this
                // world generates exactly as it would without the mod.
                return;
            }

            ResourceLocation dimension = serverLevel.dimension().location();
            ChunkGenerator chunkGenerator = serverLevel.getChunkSource().getGenerator();

            // Registered BEFORE loadOrGenerate: zone placement samples biomes through the biome
            // source, and must already see zoneOnlyBiomes filtered out. Every lookup still finds no
            // zone until loadOrGenerate indexes them, so nothing else changes during generation.
            ZoneManager.registerChunkGenerator(chunkGenerator, dimension);
            ZoneManager.registerBiomeSource(chunkGenerator.getBiomeSource(), dimension);
            ZoneManager.registerRandomState(serverLevel.getChunkSource().randomState(), dimension);
            ZoneManager.registerZoneOnlyBiomes(serverLevel, chunkGenerator.getBiomeSource());

            ZoneManager.loadOrGenerate(serverLevel);
            ZoneManager.ensurePlacementIndexBuilt(serverLevel.registryAccess());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            ZoneManager.unloadDimension(serverLevel.dimension().location());
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (!WorldZoneConfig.isManagedWorld()) {
            return;
        }
        ServerLevel overworldLevel = event.getServer().getLevel(Level.OVERWORLD);
        if (overworldLevel == null) {
            return;
        }

        ZoneManager.resolveConfiguredSpawnZone(overworldLevel.dimension().location()).ifPresent(zone -> {
            BlockPos pos = SpawningInsideZoneHandler.findSafeSpawnInsideZone(overworldLevel, zone);
            overworldLevel.setDefaultSpawnPos(pos, 0.0F);
            ModLogger.info("World spawn placed inside zone '{}' at {}", zone.zoneType, pos);
        });
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ZonePersistenceHandler.reset();
        ZoneManager.flushDirtyZones();
        ZoneManager.resetSessionState();
        WorldZoneConfig.reset();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && WorldZoneConfig.isManagedWorld()) {
            ZonePersistenceHandler.onServerTick();
        }
    }
}
