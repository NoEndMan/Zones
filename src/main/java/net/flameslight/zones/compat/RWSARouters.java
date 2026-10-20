package net.flameslight.zones.compat;

import net.flameslight.zones.ZoneManager;
import net.flameslight.zones.config.WorldZoneConfig;
import net.flameslight.zones.types.ZoneInstance;
import net.flameslight.zones.types.flattenTerrain.FlattenedPreliminarySurfaceFunction;
import net.flameslight.zones.types.zoneDefinition.ZoneDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * API RW- Structures Awareness Addon (RWSA) uses to read Zones data and call Zones methods.
 * RWSA references no other Zones class.
 * <p>
 * Each method routes to whatever Zones does internally, so Zones' own classes can be renamed,
 * moved or rewritten freely. Existing methods are never removed or changed. new features adds
 * a new method and raises {@link #API_VERSION}, which RWSA checks before using this class.
 */
public final class RWSARouters {
    /** Raised whenever a method is added. Existing methods never change. Read it through apiVersion(). */
    public static final int API_VERSION = 1;

    /**
     * Returns this API's version.
     * <p>
     * A method, not the field itself: API_VERSION is a compile-time constant, so a caller reading the field
     * would get the value of the Zones jar it was compiled against, not the installed one.
     *
     * @return API_VERSION of the installed Zones
     */
    public static int apiVersion() {
        return API_VERSION;
    }

    /**
     * One placed zone as RWSA sees it.
     *
     * @param zoneId      the zone's definition id
     * @param ancestorIds the definition ids of the zones this one is nested in (its parentZone chain),
     *                    nearest parent first, empty for a top-level zone
     * @param centerX     the zone circle's center X (block)
     * @param centerZ     the zone circle's center Z (block)
     * @param radius      the zone circle's radius (blocks)
     */
    public record ZoneArea(String zoneId, List<String> ancestorIds, int centerX, int centerZ, int radius) {
    }

    /**
     * Returns the function Zones' preliminary-surface flattening wraps.
     *
     * @param function any density function, not changed
     * @return the wrapped original function if {@code function} is Zones' flattening wrapper, otherwise
     *         {@code function} itself
     */
    public static DensityFunction unwrapPreliminarySurface(DensityFunction function) {
        return function instanceof FlattenedPreliminarySurfaceFunction wrapper ? wrapper.original() : function;
    }

    /**
     * Tells whether any placed zone flattens terrain.
     *
     * @return true if at least one placed zone flattens terrain
     */
    public static boolean hasAnyFlattenZone() {
        return ZoneManager.hasAnyFlattenZone();
    }

    /**
     * Tells whether Zones flattens this column's preliminary surface.
     *
     * @param dimension the dimension id
     * @param blockX    the column's X (block)
     * @param blockZ    the column's Z (block)
     * @return true if a flattening zone sets this column's preliminary surface height
     */
    public static boolean flattensColumn(ResourceLocation dimension, int blockX, int blockZ) {
        return ZoneManager.getPreliminarySurfaceFlattenY(dimension, blockX, blockZ) != Integer.MIN_VALUE;
    }

    /**
     * Lists every zone placed in a dimension, with the zones each one is nested in.
     *
     * @param dimension the dimension id
     * @return one {@link ZoneArea} per placed zone, empty if the dimension has none
     */
    public static List<ZoneArea> allZones(ResourceLocation dimension) {
        List<ZoneInstance> zones = ZoneManager.getAllZones(dimension);
        List<ZoneArea> out = new ArrayList<>(zones.size());
        for (ZoneInstance zone : zones) {
            if (zone != null && zone.zoneType != null) {
                out.add(new ZoneArea(zone.zoneType, ancestorIds(zone.zoneType), zone.centerX, zone.centerZ, zone.radius));
            }
        }
        return out;
    }

    // ---- Used by RWSA's harness tests ----

    /**
     * Wraps a density function in Zones' preliminary-surface flattening wrapper, as Zones does at world load.
     *
     * @param original the function to wrap, not changed
     * @param owner    the RandomState the wrapper belongs to (it tells the wrapper its dimension)
     * @return a new flattening wrapper around {@code original}
     */
    public static DensityFunction wrapForFlattening(DensityFunction original, RandomState owner) {
        return new FlattenedPreliminarySurfaceFunction(original, owner);
    }

    /**
     * Tells whether a density function is Zones' preliminary-surface flattening wrapper.
     *
     * @param function any density function
     * @return true if it is the flattening wrapper
     */
    public static boolean isFlatteningWrapper(DensityFunction function) {
        return function instanceof FlattenedPreliminarySurfaceFunction;
    }

    /**
     * Tells whether a column lies inside any placed zone.
     *
     * @param dimension the dimension id
     * @param blockX    the column's X (block)
     * @param blockZ    the column's Z (block)
     * @return true if the column is inside a zone
     */
    public static boolean insideAnyZone(ResourceLocation dimension, int blockX, int blockZ) {
        return ZoneManager.getZoneAtCached(dimension, blockX, blockZ) != null;
    }

    /**
     * Walks a zone definition's parentZone chain.
     *
     * @param zoneId a zone definition id
     * @return the ids of its parent, grandparent and so on, nearest first. Stops at a missing parent or a
     *         cycle, empty for a top-level zone
     */
    private static List<String> ancestorIds(String zoneId) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(zoneId);
        ZoneDefinition current = WorldZoneConfig.findDefinition(zoneId);
        while (current != null && current.parentZone != null && !current.parentZone.isEmpty()
                && seen.add(current.parentZone)) {
            out.add(current.parentZone);
            current = WorldZoneConfig.findDefinition(current.parentZone);
        }
        return out;
    }
}
