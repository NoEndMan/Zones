package net.flameslight.zones.types;

import net.flameslight.zones.ZoneManager;
import net.flameslight.zones.types.zoneDefinition.ZoneDefinition;
import net.minecraft.util.RandomSource;

/** Per-zone-type search progress: successive rings at stepDifference, 2*stepDifference, ...
 *  with triesPerRing random points on each ring's circumference. gets triesPerRing tries on an attempted ring,
 *  each with its own roll, so a rare type skips most rings without affecting any other type's rings. */
public class SearchState {
    public final ZoneDefinition def;
    public final long stepDifference;
    public final int triesPerRing;
    /** density^RING_CHANCE_EXPONENT: the chance this zone type attempts a given ring at all. */
    public final float ringChance;
    public final long rollSalt;
    /** Per-try chance that a candidate point is allowed to place at all (density, capped at 1.0). */
    public final float tryChance;

    public long ringRadius;
    public int ringIndex = 0;
    public int triesUsedInRing = 0;
    public boolean ringHadPlacement = false;
    public int emptyRingStreak = 0;
    public int placed = 0;
    /** Monotonic across the whole search, so no two tries ever share a roll. */
    public int tryIndex = 0;

    public SearchState(ZoneDefinition def, long stepDifference, int triesPerRing, float ringChance,
                       float tryChance, long worldSeed) {
        this.def = def;
        this.stepDifference = stepDifference;
        this.triesPerRing = triesPerRing;
        this.ringChance = ringChance;
        this.tryChance = tryChance;
        // Salted by the zone's own id, so adding or removing a zone file never shifts any
        // other zone's rings.
        this.rollSalt = def.id.hashCode() * 31L + worldSeed;
        this.ringRadius = stepDifference; // ring 1; there is no try at the world origin
    }

    /** Deterministic per ring index, so calling it repeatedly while a ring is still being tried
     *  always gives the same answer. Uses ringIndex (not tryIndex) and its own salt, so the ring
     *  roll and the per-try roll never draw from the same stream. */
    public boolean ringIsAttempted() {
        return ringChance >= 1.0f
                || ZoneManager.deterministicRoll(rollSalt, ringIndex, 0x9E3779B97F4A7C15L) < ringChance;
    }

    /** Rolled before the candidate is tested, and tryIndex advances either way, so ring
     *  bookkeeping is unaffected by the outcome. */
    public boolean tryIsAllowed() {
        int index = tryIndex++;
        return tryChance >= 1.0f
                || ZoneManager.deterministicRoll(rollSalt, index, 0xC2B2AE3D27D4EB4FL) < tryChance;
    }

    public int[] nextCandidatePoint(RandomSource random) {
        double angle = random.nextDouble() * Math.PI * 2;
        int x = (int) Math.round(Math.cos(angle) * ringRadius);
        int z = (int) Math.round(Math.sin(angle) * ringRadius);
        return new int[]{x, z};
    }

    public void advanceRing() {
        ringIndex++;
        ringRadius += stepDifference;
        triesUsedInRing = 0;
        ringHadPlacement = false;
    }
}
