package kryptos.automation;

import arc.struct.ObjectIntMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.type.Item;
import mindustry.type.ItemStack;
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

    /**
     * Highest-tier unlocked (and currently affordable) conveyor, for when
     * there's nothing already on the field to match against (see
     * {@link #matchExistingConveyor}) -- e.g. the very first belt of the
     * game. Previously this fallback only ever chose between the basic
     * Conveyor and Titanium Conveyor; this instead considers every Conveyor
     * block in the game (Plastanium, Armored, etc.), mirroring how
     * {@code findBestDrillForItem} already picks drills by tier.
     */
    static Block bestUnlockedConveyor(Team team) {
        Seq<Conveyor> candidates = new Seq<>();

        for (Block block : Vars.content.blocks()) {
            if (!(block instanceof Conveyor conveyor)) continue;
            if (!conveyor.unlockedNow() && !Vars.state.rules.infiniteResources) continue;
            candidates.add(conveyor);
        }

        if (candidates.isEmpty()) return null;
        candidates.sort((a, b) -> Float.compare(b.speed, a.speed));

        if (Vars.state.rules.infiniteResources) return candidates.first();

        Building core = team.core();
        if (core != null) {
            for (Conveyor conveyor : candidates) {
                if (canAfford(core, conveyor)) return conveyor;
            }
        }

        return candidates.peek();
    }

    private static boolean canAfford(Building core, Block block) {
        for (ItemStack stack : block.requirements) {
            if (core.items.get(stack.item) < stack.amount) return false;
        }
        return true;
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

