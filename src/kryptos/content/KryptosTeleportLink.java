package kryptos.content;

import arc.math.geom.Point2;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.content.Fx;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.meta.BuildVisibility;

import static mindustry.Vars.world;

/**
 * Generic point-to-point item teleporter: a pair of these can be linked to
 * each other -- anything fed into one instantly appears out the other, at
 * any distance, with no physical connection required in between.
 *
 * Linking is done through logic: {@code control configure <this> <other> 0 0 0}
 * where <other> is a bound/sensed Building reference (e.g. via {@code getlink}
 * or a unit-sensed building). The link is one-directional per block -- link
 * both ends to each other for two-way flow.
 *
 * The link target is stored as a packed tile position (not a live Building
 * reference), re-resolved on every delivery, so it survives save/load and
 * naturally goes "dead" (silently drops items) if the far side is ever
 * removed or replaced with something else.
 */
public class KryptosTeleportLink extends Block {

    public KryptosTeleportLink(String name) {
        super(name);
        update = true;
        solid = true;
        hasItems = true;
        itemCapacity = 10;
        configurable = true;
        logicConfigurable = true;
        buildVisibility = BuildVisibility.shown;

        config(Building.class, (KryptosTeleportLinkBuild tile, Building other) -> {
            tile.linkPos = other != null ? other.pos() : -1;
        });

        // Also allow linking directly via a raw packed position (Integer),
        // in case a future UI/manual-link feature wants to set it that way
        // without needing a live Building reference.
        config(Integer.class, (KryptosTeleportLinkBuild tile, Integer pos) -> tile.linkPos = pos);
    }

    public class KryptosTeleportLinkBuild extends Building {

        public int linkPos = -1;

        @Override
        public boolean acceptItem(Building source, Item item) {
            Building target = linkedBuilding();
            return target != null && target != this && target.acceptItem(this, item);
        }

        @Override
        public void handleItem(Building source, Item item) {
            Building target = linkedBuilding();
            if (target != null && target != this) {
                target.handleItem(this, item);
                Fx.teleportOut.at(x, y, 0f, item.color);
                Fx.teleport.at(target.x, target.y, 0f, item.color);
            }
            // No valid link: item just vanishes (teleport failed silently),
            // matching how KryptosItemTeleporter drops items when the core
            // is unreachable.
        }

        private Building linkedBuilding() {
            if (linkPos == -1) return null;
            var t = world.tile(Point2.x(linkPos), Point2.y(linkPos));
            return t != null ? t.build : null;
        }

        @Override
        public Integer config() {
            return linkPos;
        }

        @Override
        public byte version() {
            return 1;
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.i(linkPos);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            linkPos = read.i();
        }
    }
}
