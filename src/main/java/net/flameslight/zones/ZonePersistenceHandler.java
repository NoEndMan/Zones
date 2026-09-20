package net.flameslight.zones;

import net.flameslight.zones.logger.ModLogger;
import net.minecraft.Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Periodic zone-data durability. Zone bookkeeping (maxCount counts, guaranteePlacement attempts)
 * only reached disk on a clean shutdown before this, so a crash silently rewound every placement
 * recorded since boot and let already-placed structures generate a second time.
 *
 * The snapshot is taken on the server thread and the disk work handed to the IO pool, so the tick
 * thread never blocks on it. Zone instances are mutated under their own monitors, so a snapshot can
 * miss a counter bump that lands mid-serialization; the next flush picks it up.
 */
public final class ZonePersistenceHandler {
    /** 30 seconds. Long enough that the serialization cost is noise, short enough that a crash
     *  loses at most half a minute of placement records. */
    private static final int FLUSH_INTERVAL_TICKS = 600;

    private static int flushCountdown = FLUSH_INTERVAL_TICKS;

    /** Called every server tick (END phase); does real work once per FLUSH_INTERVAL_TICKS. */
    public static void onServerTick() {
        if (--flushCountdown > 0) {
            return;
        }
        flushCountdown = FLUSH_INTERVAL_TICKS;
        flushAsync();
    }

    /** Resets the countdown so a new world in the same game session starts a fresh interval. */
    public static void reset() {
        flushCountdown = FLUSH_INTERVAL_TICKS;
    }

    private static void flushAsync() {
        Map<Path, String> pending = ZoneManager.snapshotDirtyZones();
        boolean locatePending = LocatePositionsSavingHandler.hasPendingWrites();
        if (pending.isEmpty() && !locatePending) {
            return;
        }
        Util.ioPool().execute(() -> {
            for (Map.Entry<Path, String> entry : pending.entrySet()) {
                try {
                    Files.writeString(entry.getKey(), entry.getValue(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    ModLogger.error("Failed to write zone data to {}", entry.getKey(), e);
                }
            }
            LocatePositionsSavingHandler.flushPending();
        });
    }
}
