package kryptos.content;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.meta.BuildVisibility;

import static mindustry.Vars.content;

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

        // Whatever item most recently passed through -- drawn on the block
        // in place of a fixed icon, so the block visually reflects whatever
        // it's actually moving (copper if fed copper, titanium if fed
        // titanium, etc.) instead of always showing the same static art.
        public Item lastItem;

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
                lastItem = item;
                KryptosFx.scanTeleportOut.at(x, y, 0f, item.color);
                KryptosFx.scanTeleport.at(core.x, core.y, 0f, item.color);
            }
            // Intentionally not calling items.add(...) here -- the item is
            // teleported, not stored locally.
        }

        @Override
        public void draw() {
            super.draw();
            if (lastItem != null) {
                // Recessed socket so the ore/item reads as sitting *inside*
                // the block instead of floating flush with its edges.
                Draw.color(Pal.darkestMetal);
                Fill.square(x, y, 3f);
                Draw.color(Pal.darkOutline);
                Lines.stroke(1f);
                Lines.square(x, y, 3f);
                Draw.reset();

                Draw.rect(lastItem.fullIcon, x, y, 5f, 5f);
            }
        }

        @Override
        public byte version() {
            return 1;
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.s(lastItem == null ? -1 : lastItem.id);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            short itemId = read.s();
            lastItem = itemId == -1 ? null : content.item(itemId);
        }
    }
}
