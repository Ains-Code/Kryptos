package kryptos.content;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.graphics.Drawf;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.meta.BuildVisibility;

import static mindustry.Vars.content;
import static mindustry.Vars.tilesize;
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
        // default onConfigureBuildTapped() never calls configure() with the
        // tapped building -- it just decides whether to deselect.
        //
        // 'other' is null whenever the tap didn't land on a building at all
        // (bare ground, a non-solid tile, etc) -- that case must be handled
        // explicitly, since calling other.pos()/other.block on null is what
        // crashed the client before.
        @Override
        public boolean onConfigureBuildTapped(Building other) {
            if (this == other) {
                // Double-tapping self clears the link.
                configure(-1);
                return false;
            }

            if (other == null) {
                // Tapped empty space -- nothing to link to; just let the
                // default handling close the config UI.
                return true;
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

        // Shown while this block is selected (config open) -- draws a
        // pulsing outline on this block, and if it's already linked, a
        // highlighted outline + arrow pointing at the current target so
        // the player can see the link regardless of distance (there's no
        // range limit here, unlike PayloadMassDriver's dashCircle, so we
        // don't try to show "all valid tiles" -- any other same-team
        // KryptosTeleportLink on the map is a valid tap target).
        @Override
        public void drawConfigure() {
            float sin = Mathf.absin(Time.time, 6f, 1f);

            Drawf.select(x, y, size * tilesize / 2f + 2f + sin, Pal.accent);

            Building target = linkedBuilding();
            if (target != null && target != this) {
                Drawf.select(target.x, target.y, target.block.size * tilesize / 2f + 2f + sin, Pal.place);
                Drawf.arrow(x, y, target.x, target.y, size * tilesize + sin, 4f + sin, Pal.place);
            }
        }

        // Max number of teleport-link hops to follow before giving up.
        // Without this cap, a link cycle (A -> B -> A, or any longer loop)
        // would make acceptItem/handleItem call each other forever and
        // crash with a StackOverflowError -- this happened for real (see
        // crash log: infinite kryptos.content.KryptosTeleportLink$
        // KryptosTeleportLinkBuild.acceptItem recursion). Any ordinary
        // chain of linked teleporters is nowhere near this deep, so hitting
        // the cap always means a cycle or a broken/dangling link -- in
        // both cases we just drop the item like any other dead link.
        private static final int MAX_LINK_HOPS = 64;

        @Override
        public boolean acceptItem(Building source, Item item) {
            Building target = resolveFinalTarget();
            if (target == null) return false;
            if (target instanceof KryptosTeleportLinkBuild terminal) {
                // Terminal teleport link with nowhere further to forward --
                // it holds the item itself (like a tiny buffer) so nearby
                // extractors/inserters can pull it out, instead of
                // requiring every link to chain onward to some other block.
                return terminal.items.total() < itemCapacity;
            }
            return target.acceptItem(this, item);
        }

        @Override
        public void handleItem(Building source, Item item) {
            Building target = resolveFinalTarget();
            if (target != null) {
                if (target instanceof KryptosTeleportLinkBuild terminal) {
                    terminal.items.add(item, 1);
                } else {
                    target.handleItem(this, item);
                }
                lastItem = item;
                KryptosFx.scanTeleportOut.at(x, y, 0f, item.color);
                KryptosFx.scanTeleport.at(target.x, target.y, 0f, item.color);
            }
            // No valid link (or a cycle): item just vanishes (teleport
            // failed silently), matching how KryptosItemTeleporter drops
            // items when the core is unreachable.
        }

        @Override
        public void draw() {
            super.draw();
            if (lastItem != null) {
                // Recessed socket so the item reads as sitting *inside* the
                // block instead of floating flush with its edges.
                Draw.color(Pal.darkestMetal);
                Fill.square(x, y, 3f);
                Draw.color(Pal.darkOutline);
                Lines.stroke(1f);
                Lines.square(x, y, 3f);
                Draw.reset();

                Draw.rect(lastItem.fullIcon, x, y, 5f, 5f);
            }
        }

        private Building linkedBuilding() {
            if (linkPos == -1) return null;
            var t = world.tile(Point2.x(linkPos), Point2.y(linkPos));
            return t != null ? t.build : null;
        }

        // Follows the link chain iteratively (NOT recursively) until it
        // reaches either a building that isn't itself a KryptosTeleportLink,
        // or a KryptosTeleportLink that has no further outgoing link of its
        // own -- both count as a valid terminal delivery point. Returns
        // null only for a direct cycle back to the origin, or if the chain
        // is still going after MAX_LINK_HOPS (a longer cycle).
        private Building resolveFinalTarget() {
            Building current = this;
            for (int hops = 0; hops < MAX_LINK_HOPS; hops++) {
                if (!(current instanceof KryptosTeleportLinkBuild link)) {
                    return current; // reached a normal, non-teleport-link building
                }
                Building next = link.linkedBuilding();
                if (next == null) {
                    return current; // chain ends here -- this link IS the terminal
                }
                if (next == this) return null; // direct cycle back to the origin
                current = next;
            }
            return null; // likely a longer cycle -- give up rather than loop forever
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
