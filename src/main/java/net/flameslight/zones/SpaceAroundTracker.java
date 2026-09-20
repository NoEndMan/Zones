package net.flameslight.zones;

import net.flameslight.zones.config.WorldZoneConfig;
import net.flameslight.zones.types.ZoneInstance;
import net.flameslight.zones.types.spaceAround.SpaceAroundGrid;
import net.flameslight.zones.types.spaceAround.SpaceAroundRecord;
import net.flameslight.zones.types.zoneDefinition.ZoneDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.Arrays;
import java.util.List;

/**
 * spaceAround: keeps structures (per piece, for jigsaws) at least spaceAround blocks from other
 * structures inside the same top-level zone tree. Records and their index live on the root zone.
 */
public final class SpaceAroundTracker {
    /**
     * Check and register as ONE atomic step under the root zone's lock. Returns false if the
     * candidate violates spaceAround (nothing registered); true otherwise (registered if
     * spaceAround > 0). Jigsaw pieces have normally been trimmed already during assembly; this
     * catches races between concurrent assemblies and conflicts of the start piece itself.
     */
    public static boolean checkAndRegister(ResourceLocation dimension, int blockX, int blockZ,
                                           StructureStart start, boolean jigsaw, int spaceAround) {
        ZoneInstance root = ZoneManager.findRootZone(dimension, blockX, blockZ);

        if (root == null) {
            return true; // outside every zone: no boxes stored here, nothing to register
        }

        ZoneDefinition rootDef = WorldZoneConfig.findDefinition(root.zoneType);

        if (rootDef == null || !rootDef.treeHasSpaceAround) {
            return true; // config-static: no record can ever exist in this tree
        }

        int[] pieces = collectPieces(root, start, jigsaw);

        if (pieces.length == 0) {
            return true;
        }

        synchronized (root.spaceAroundLock) {
            SpaceAroundGrid grid = root.ensureSpaceAroundGrid();
            if (grid == null) {
                return true; // zone retired: nothing more is ever placed or protected here
            }
            for (int i = 0; i < pieces.length; i += 6) {
                if (grid.conflicts(pieces[i], pieces[i + 1], pieces[i + 2],
                        pieces[i + 3], pieces[i + 4], pieces[i + 5], spaceAround)) {
                    return false;
                }
            }
            if (spaceAround > 0 && root.addSpaceAroundRecordIfActive(SpaceAroundRecord.of(pieces, spaceAround))) {
                ZoneManager.markDirty(dimension);
            }
            return true;
        }
    }

    /**
     * Non-jigsaw: the overall box. Jigsaw: every piece box, except pieces
     * lying fully outside the top-level zone, which spaceAround ignores by design.
     */
    private static int[] collectPieces(ZoneInstance root, StructureStart start, boolean jigsaw) {
        if (!jigsaw) {
            BoundingBox b = start.getBoundingBox();
            return new int[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()};
        }
        List<StructurePiece> list = start.getPieces();
        int[] out = new int[list.size() * 6];
        int n = 0;
        for (StructurePiece piece : list) {
            BoundingBox b = piece.getBoundingBox();
            if (!root.intersectsBox(b.minX(), b.minZ(), b.maxX(), b.maxZ())) {
                continue;
            }
            out[n++] = b.minX();
            out[n++] = b.minY();
            out[n++] = b.minZ();
            out[n++] = b.maxX();
            out[n++] = b.maxY();
            out[n++] = b.maxZ();
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }
}
