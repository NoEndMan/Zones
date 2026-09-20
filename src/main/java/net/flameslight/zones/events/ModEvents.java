package net.flameslight.zones.events;

import net.flameslight.zones.Zones;
import net.flameslight.zones.config.CommonConfig;
import net.flameslight.zones.config.ZoneDefinitionFiles;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = Zones.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModEvents {
    @SubscribeEvent
    public static void onConfigLoaded(ModConfigEvent event) {
        if (event instanceof ModConfigEvent.Unloading
                || event.getConfig().getSpec() != CommonConfig.COMMON_SPEC) {
            return;
        }

        ZoneDefinitionFiles.createDefaults();
    }
}
