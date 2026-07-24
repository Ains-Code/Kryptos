package kryptos.automation;

import arc.struct.ObjectIntMap;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.production.Drill;

/**
 * Both KryptosSmartDrill and KryptosAutoConveyor used to always pick the
 * "best" (highest-tier) unlocked drill/conveyor for every new build. That
 * meant automation could silently place a Titanium Conveyor or a
 * higher-tier drill next to a field that's otherwise all Mechanical
 * Drills/basic Conveyors -- inconsistent with what the player is actually
 * building by hand.
 *
 * This looks at what's already placed on the field first and matches that
 * tier. Only falls back to "best unlocked" when nothing matching exists yet
 * (e.g. the very first drill/conveyor of the game).
 */
final class KryptosFieldTier {

    private KryptosFieldTier() {}

    /** Most common existing drill type on the field that's able to mine {@code item}, or null if none. */
    static Drill matchExistingDrill(Team team, Item item) {
        ObjectIntMap<Drill> counts = new ObjectIntMap<>();

        Groups.build.each(b -> {
            if (b.team != team) return;
            if (!(b.block instanceof Drill drill)) return;
            if (item.hardness > drill.tier) return; // this tier can't mine this item, don't match it
            counts.increment(drill, 0, 1);
        });

        return mostCommon(counts);
    }

    /** Most common existing belt type on the field, or null if none placed yet. */
    static Block matchExistingConveyor(Team team) {
        ObjectIntMap<Block> counts = new ObjectIntMap<>();

        Groups.build.each(b -> {
            if (b.team != team) return;
            if (!(b.block instanceof Conveyor)) return;
            counts.increment(b.block, 0, 1);
        });

        return mostCommon(counts);
    }

    private static <T> T mostCommon(ObjectIntMap<T> counts) {
        T best = null;
        int bestCount = -1;
        for (var entry : counts) {
            if (entry.value > bestCount) {
                bestCount = entry.value;
                best = entry.key;
            }
        }
        return best;
    }
}

