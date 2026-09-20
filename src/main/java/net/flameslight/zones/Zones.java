package net.flameslight.zones;

import com.mojang.logging.LogUtils;
import net.flameslight.zones.config.CommonConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod(Zones.MOD_ID)
public class Zones
{
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "zones";
    public static final Logger LOGGER = LogUtils.getLogger();

    @SuppressWarnings("removal")
    public Zones() {
        // NightConfig writes common-config.toml into this folder; create it first so the very first
        // launch can't race the folder into existence.
        Path configFolder = FMLPaths.CONFIGDIR.get().resolve(MOD_ID);
        try {
            Files.createDirectories(configFolder);
        } catch (IOException e) {
            LOGGER.error("Could not create the zones config folder {}", configFolder, e);
        }

        FMLJavaModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON, CommonConfig.COMMON_SPEC, MOD_ID + "/common-config.toml");
    }
}
