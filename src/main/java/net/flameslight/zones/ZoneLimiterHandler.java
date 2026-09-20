package net.flameslight.zones;
import net.flameslight.zones.config.WorldZoneConfig;
import net.flameslight.zones.types.spaceAround.SpaceAroundGrid;
import net.flameslight.zones.types.ZoneInstance;
import net.flameslight.zones.types.zoneDefinition.ZoneDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-thread rules for the structure currently generating on this worker thread:
 * blockLeakingOutsideZone, blockLeakingIntoNestedZones, and per-piece spaceAround.
 * Filled in Structure#generate HEAD (StructureMixin), cleared at generate RETURN.
 *
 * The leak rules apply to EVERY structure, but are enforced differently: a jigsaw structure is
 * trimmed piece by piece during assembly (JigsawPlacerMixin calls blocksCandidate), so it grows
 * around the boundary and still generates; anything else has no assembly to trim, so
 * violatesLeakRules at RETURN rejects the whole start. spaceAround's grid is read ONLY by the
 * placer hook, so it is not even set up for a non-jigsaw structure.
 *
 * Inactive (one ThreadLocal read + boolean) for every structure none of these apply to.
 */
public final class ZoneLimiterHandler {
    private static final ThreadLocal<Context> CONTEXT = ThreadLocal.withInitial(Context::new);

    private static final class Context {
        boolean active;
        /** Non-null when blockLeakingOutsideZone applies: pieces must stay fully inside it. */
        ZoneInstance startZone;
        /** Nested zones pieces must not touch (blockLeakingIntoNestedZones). */
        final List<ZoneInstance> blockedNested = new ArrayList<>(4);
        /** Non-null only for a jigsaw structure whose root zone's tree uses spaceAround. */
        ZoneInstance root;
        SpaceAroundGrid grid;
        int ownSpaceAround;

        void clear() {
            if (!active) {
                return; // nothing was ever set up; skip nulling six fields per structure attempt
            }
            active = false;
            startZone = null;
            blockedNested.clear();
            root = null;
            grid = null;
            ownSpaceAround = 0;
        }
    }

    /**
     * Called from Structure#generate HEAD, only when something here could apply: the structure is
     * a jigsaw (so the placer hook may need the spaceAround grid), or its resolved entry sets a
     * leak flag. `entry` is the already-resolved entry for the zone at this position.
     */
    public static void beginHandling(ResourceLocation dimension, int blockX, int blockZ, String structureId,
                                     ZoneDefinition.StructureEntry entry, boolean isJigsaw) {
        Context c = CONTEXT.get();
        c.clear();

        ZoneInstance start = ZoneManager.getZoneAtCached(dimension, blockX, blockZ);
        if (start == null) {
            return;
        }
        ZoneDefinition startDef = WorldZoneConfig.findDefinition(start.zoneType);

        if (startDef == null) {
            return;
        }
        if (entry.blockLeakingOutsideZone) {
            c.startZone = start;
        }
        // Ignored when nothing nests inside the start zone (logged at debug by WorldZoneConfig).
        if (entry.blockLeakingIntoNestedZones && startDef.hasChildZones) {
            collectBlockedNested(dimension, start, startDef, structureId, c.blockedNested);
        }

        // Only the placer hook reads the grid, and only a jigsaw structure reaches it: building it
        // for anything else would resolve the root zone and possibly populate a whole index that
        // nothing then queries.
        if (isJigsaw) {
            ZoneInstance root = ZoneManager.findRootZone(dimension, blockX, blockZ);
            if (root != null) {
                ZoneDefinition rootDef = root == start ? startDef : WorldZoneConfig.findDefinition(root.zoneType);
                if (rootDef != null && rootDef.treeHasSpaceAround) {
                    SpaceAroundGrid grid = root.ensureSpaceAroundGrid();
                    if (grid != null) {
                        c.root = root;
                        c.grid = grid;
                        c.ownSpaceAround = entry.spaceAround;
                    }
                }
            }
        }

        c.active = c.startZone != null || !c.blockedNested.isEmpty() || c.grid != null;
    }

    public static void endHandling() {
        CONTEXT.get().clear();
    }

    /**
     * Jigsaw placer hook: true if this candidate piece must not be placed. `candidate` is vanilla's
     * own test shape, the piece's box deflated by 0.25, so flooring its bounds gives back the exact
     * block box.
     */
    public static boolean blocksCandidate(VoxelShape candidate) {
        Context c = CONTEXT.get();
        if (!c.active) {
            return false;
        }

        // min/max(Axis) read the shape's own bounds without allocating an AABB per call.
        int minX = Mth.floor(candidate.min(Direction.Axis.X));
        int minY = Mth.floor(candidate.min(Direction.Axis.Y));
        int minZ = Mth.floor(candidate.min(Direction.Axis.Z));
        int maxX = Mth.floor(candidate.max(Direction.Axis.X));
        int maxY = Mth.floor(candidate.max(Direction.Axis.Y));
        int maxZ = Mth.floor(candidate.max(Direction.Axis.Z));

        if (blocksByLeakRules(c, minX, minZ, maxX, maxZ)) {
            return true;
        }
        // Pieces fully outside the top-level zone are ignored by spaceAround (by design). The
        // grid read is lock-free; the atomic check at generate RETURN catches any race.
        return c.grid != null
                && c.root.intersectsBox(minX, minZ, maxX, maxZ)
                && c.grid.conflicts(minX, minY, minZ, maxX, maxY, maxZ, c.ownSpaceAround);
    }

    /**
     * Checked at generate RETURN for EVERY structure. For a jigsaw it is a safety net: trimming
     * already kept the child pieces in bounds, but the start piece is never trimmed and another
     * assembly path could bypass the placer hook. For anything else it is the only enforcement
     * there is, and a violation rejects the whole start.
     */
    public static boolean violatesLeakRules(StructureStart start) {
        Context c = CONTEXT.get();
        if (!c.active || (c.startZone == null && c.blockedNested.isEmpty())) {
            return false;
        }
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox b = piece.getBoundingBox();
            if (blocksByLeakRules(c, b.minX(), b.minZ(), b.maxX(), b.maxZ())) {
                return true;
            }
        }
        return false;
    }

    private static boolean blocksByLeakRules(Context c, int minX, int minZ, int maxX, int maxZ) {
        if (c.startZone != null && !c.startZone.containsBox(minX, minZ, maxX, maxZ)) {
            return true;
        }
        List<ZoneInstance> nested = c.blockedNested;
        for (int i = 0; i < nested.size(); i++) {
            if (nested.get(i).intersectsBox(minX, minZ, maxX, maxZ)) {
                return true;
            }
        }
        return false;
    }

    private static void collectBlockedNested(ResourceLocation dimension, ZoneInstance start, ZoneDefinition startDef,
                                             String structureId, List<ZoneInstance> out) {
        for (ZoneInstance zi : ZoneManager.zonesNear(dimension, start.centerX, start.centerZ)) {
            if (zi == start || zi.centerX != start.centerX || zi.centerZ != start.centerZ) {
                continue;
            }
            ZoneDefinition def = WorldZoneConfig.findDefinition(zi.zoneType);
            if (def == null || !isDescendantOf(def, startDef.id)) {
                continue;
            }
            if (ZoneManager.resolveEntry(def, structureId) == null) {
                out.add(zi);
            }
        }
    }

    private static boolean isDescendantOf(ZoneDefinition def, String ancestorId) {
        String parentId = def.parentZone;
        for (int guard = 0; parentId != null && !parentId.isEmpty() && guard < 64; guard++) {
            if (parentId.equals(ancestorId)) {
                return true;
            }
            ZoneDefinition parent = WorldZoneConfig.findDefinition(parentId);
            if (parent == null) {
                return false;
            }
            parentId = parent.parentZone;
        }
        return false;
    }
}
