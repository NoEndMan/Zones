package net.flameslight.zones.logger;

import net.flameslight.zones.Zones;
import net.flameslight.zones.config.CommonConfig;
import org.slf4j.Logger;

import java.text.MessageFormat;

public class ModLogger {
    private static final Logger LOGGER = Zones.LOGGER;

    public static void info(String message, Object ... args) {
        LOGGER.info(buildMessageLog(message), args);
    }

    public static void debug(String message, Object ... args) {
        if(CommonConfig.isDebugMod())
            LOGGER.info(buildMessageLog(MessageFormat.format("[DEBUG]: {0}", buildMessageLog(message))), args);
    }

    public static void warn(String message, Object ... args) {
        LOGGER.warn(buildMessageLog(message), args);
    }

    public static void error(String message, Object ... args) {
        LOGGER.error(buildMessageLog(message), args);
    }

    public static void error(String message, Throwable error) {
        LOGGER.error(buildMessageLog(message), error);
    }

    private static String buildMessageLog(String message) {
        return MessageFormat.format("{0}: {1}", "Zones-logger", message);
    }
}
