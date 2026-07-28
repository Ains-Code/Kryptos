package kryptos.content;

import mindustry.gen.Building;
import mindustry.gen.CoreBuild;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.meta.BuildVisibility;

/**
 * A block that accepts any item and instantly moves it into the owning
 * team's core inventory -- no physical belt/junction/bridge connection is
 * needed, and there's no range limit (unlike vanilla Item Bridge, which
 * requires line-of-sight and a max link distance). Feed it from a conveyor
 * or inserter and the item disappears into the core the same tick.
 *
 * Deliberately never stores items locally: {@link #hasItems} is true only
 * so the engine considers this block item-accepting at all (see
 * BuildingComp#acceptItem/handleItem call sites, which gate on
 * block.hasItems in addition to the accept check) -- capacity is nominal
 * and nothing ever actually sits in this block's own ItemModule.
 */
public class KryptosItemTeleporter extends Block {

    public KryptosItemTeleporter(String name) {
        super(name);
        update = true;
        solid = true;
        hasItems = true;
        itemCapacity = 10;
        buildVisibility = BuildVisibility.shown;
    }

    public class KryptosItemTeleporterBuild extends Building {

        @Override
        public boolean acceptItem(Building source, Item item) {
            // Accept anything from our own team, regardless of type or
            // current core fill -- if the core is full, the item is just
            // dropped (matches how a core itself behaves when overflowing).
            return source == null || source.team == team;
        }

        @Override
        public void handleItem(Building source, Item item) {
            CoreBuild core = team.core();
            if (core != null) {
                core.items.add(item, 1);
            }
            // Intentionally not calling items.add(...) here -- the item is
            // teleported, not stored locally.
        }
    }
}
