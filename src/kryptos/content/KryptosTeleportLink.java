package kryptos.content;

import arc.graphics.g2d.Draw;
import arc.math.geom.Point2;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.meta.BuildVisibility;

import static mindustry.Vars.content;
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

        // Same reasoning as KryptosItemTeleporter: show whatever item last
        // passed through instead of a fixed icon.
        public Item lastItem;

        // Enables in-game tap-to-link (select this block, then tap another
        // KryptosTeleportLink to link to it) -- mirrors the pattern used by
        // vanilla PowerNode/PayloadMassDriver. Without this override, the
        // block only linked via logic's `control configure`, since the
        // default onConfigureBuildTapped() never calls configure() with the
        // tapped building -- it just decides whether to deselect.
        @Override
        public boolean onConfigureBuildTapped(Building other) {
            if (this == other) {
                // Double-tapping self clears the link.
                configure(-1);
                return false;
            }

            if (linkPos == other.pos()) {
                // Tapping the currently-linked target again unlinks it.
                configure(-1);
                return false;
            } else if (other.block instanceof KryptosTeleportLink && other.team == team) {
                configure(other.pos());
                return false;
            }

            return true;
        }

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
                lastItem = item;
                KryptosFx.scanTeleportOut.at(x, y, 0f, item.color);
                KryptosFx.scanTeleport.at(target.x, target.y, 0f, item.color);
            }
            // No valid link: item just vanishes (teleport failed silently),
            // matching how KryptosItemTeleporter drops items when the core
            // is unreachable.
        }

        @Override
        public void draw() {
            super.draw();
            if (lastItem != null) {
                Draw.rect(lastItem.fullIcon, x, y, 8f, 8f);
            }
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
            write.s(lastItem == null ? -1 : lastItem.id);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            linkPos = read.i();
            short itemId = read.s();
            lastItem = itemId == -1 ? null : content.item(itemId);
        }
    }
}
