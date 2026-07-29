package kryptos.content;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.struct.IntSeq;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.gen.Building;
import mindustry.graphics.Drawf;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.meta.BuildVisibility;

import static mindustry.Vars.content;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;

/**
 * Generic point-to-point item teleporter: link one of these to one or more
 * others -- anything fed into it instantly appears out a linked target, at
 * any distance, with no physical connection required in between.
 *
 * A single block can be linked to MULTIPLE targets. When it has more than
 * one link, incoming items are round-robined across them (one item per
 * target per turn) rather than duplicated -- this is a teleporter, not a
 * copier. Many different source links can also all point at the same
 * single target with no special handling needed.
 *
 * Linking is done through logic: {@code control configure <this> <other> 0 0 0}
 * where <other> is a bound/sensed Building reference (e.g. via {@code getlink}
 * or a unit-sensed building). Configuring the same target again removes it
 * from the link list (toggle); configuring with a null/-1 building clears
 * all links. Links are one-directional per block -- link both ends to each
 * other for two-way flow.
 *
 * Link targets are stored as packed tile positions (not live Building
 * references), re-resolved on every delivery, so they survive save/load and
 * naturally go "dead" (silently skipped) if the far side is ever removed or
 * replaced with something else.
 */
public class KryptosTeleportLink extends Block {

    public KryptosTeleportLink(String name) {
        super(name);
        update = true;
        solid = true;
        hasItems = true;
        itemCapacity = 10;
        hasLiquids = true;
        liquidCapacity = 10f;
        configurable = true;
        logicConfigurable = true;
        buildVisibility = BuildVisibility.shown;
        floating = true; // placeable on water/liquid tiles, like a bridge

        config(Building.class, (KryptosTeleportLinkBuild tile, Building other) -> {
            tile.toggleLink(other != null ? other.pos() : -1);
        });

        // Also allow linking directly via a raw packed position (Integer),
        // in case a future UI/manual-link feature wants to set it that way
        // without needing a live Building reference.
        config(Integer.class, (KryptosTeleportLinkBuild tile, Integer pos) -> tile.toggleLink(pos));
    }

    public class KryptosTeleportLinkBuild extends Building {

        // Packed tile positions of every linked target. Usually just one,
        // but can hold several for fan-out (round-robin delivery).
        public IntSeq links = new IntSeq();

        // Cursor into `links` for round-robin delivery -- advances only on
        // a successful handleItem, never just from checking acceptItem.
        private int rrIndex = 0;

        // Separate round-robin cursor for liquids, so item flow and liquid
        // flow through the same links don't interfere with each other's
        // "next target" pointer.
        private int liquidRrIndex = 0;

        // Same reasoning as KryptosItemTeleporter: show whatever item last
        // passed through instead of a fixed icon.
        public Item lastItem;

        // Whatever liquid last passed through, for the same reasoning --
        // shown as a small tinted indicator when there's no item to draw.
        public Liquid lastLiquid;

        // Adds `pos` to this block's link list, or removes it if it's
        // already linked (toggle) -- this is what tap-to-link and the
        // logic `configure` call both drive. Passing -1 clears every link.
        public void toggleLink(int pos) {
            if (pos == -1) {
                links.clear();
                return;
            }
            int at = links.indexOf(pos);
            if (at >= 0) {
                links.removeIndex(at);
            } else {
                links.add(pos);
            }
        }

        // Without this, items/liquids delivered here (via handleItem/
        // handleLiquid storing them when this link is the terminal of a
        // chain) just sit in storage forever -- nothing was ever pushing
        // them onto an adjacent conveyor/inserter/pipe. dumpAccumulate()/
        // dumpLiquid() are the same mechanisms vanilla blocks like
        // ItemBridge use to push stored contents outward each tick.
        @Override
        public void updateTile() {
            dumpAccumulate();
            if (liquids.currentAmount() > 0.001f) {
                dumpLiquid(liquids.current());
            }
        }

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
                // Double-tapping self clears every link.
                configure(-1);
                return false;
            }

            if (other == null) {
                // Tapped empty space -- nothing to link to; just let the
                // default handling close the config UI.
                return true;
            }

            if (other.block instanceof KryptosTeleportLink && other.team == team) {
                // Toggles: adds it if not already linked, removes it if it
                // is -- lets you tap several targets in a row to fan out.
                configure(other.pos());
                return false;
            }

            return true;
        }

        // Shown while this block is selected (config open) -- draws a
        // pulsing outline on this block, and one highlighted outline +
        // arrow per linked target, so the player can see every link
        // regardless of distance (there's no range limit here, unlike
        // PayloadMassDriver's dashCircle, so we don't try to show "all
        // valid tiles" -- any other same-team KryptosTeleportLink on the
        // map is a valid tap target).
        @Override
        public void drawConfigure() {
            float sin = Mathf.absin(Time.time, 6f, 1f);

            Drawf.select(x, y, size * tilesize / 2f + 2f + sin, Pal.accent);

            for (int i = 0; i < links.size; i++) {
                Building target = buildAt(links.get(i));
                if (target != null && target != this) {
                    Drawf.select(target.x, target.y, target.block.size * tilesize / 2f + 2f + sin, Pal.place);
                    Drawf.arrow(x, y, target.x, target.y, size * tilesize + sin, 4f + sin, Pal.place);
                }
            }
        }

        // Max combined number of teleport-link hops to follow (across the
        // WHOLE search, including every branch tried) before giving up.
        // Without this cap, a link cycle (A -> B -> A, or any longer loop,
        // possibly through a fan-out with several branches) could make
        // resolution recurse forever and crash with a StackOverflowError --
        // this happened for real (see crash log: infinite
        // kryptos.content.KryptosTeleportLink$KryptosTeleportLinkBuild
        // .acceptItem recursion). Any ordinary web of linked teleporters is
        // nowhere near this deep, so hitting the cap always means a cycle
        // or a broken/dangling link -- in both cases the item is just
        // dropped like any other dead link.
        private static final int MAX_LINK_HOPS = 64;

        @Override
        public boolean acceptItem(Building source, Item item) {
            return pickTarget(item) != null;
        }

        @Override
        public void handleItem(Building source, Item item) {
            Building target = pickTarget(item);
            if (target != null) {
                if (target instanceof KryptosTeleportLinkBuild terminal) {
                    // Terminal teleport link with nowhere further to
                    // forward -- it holds the item itself (like a tiny
                    // buffer) so nearby extractors/inserters can pull it
                    // out, instead of requiring every link to chain onward
                    // to some other block.
                    terminal.items.add(item, 1);
                } else {
                    target.handleItem(this, item);
                }
                lastItem = item;
                KryptosFx.scanTeleportOut.at(x, y, 0f, item.color);
                KryptosFx.scanTeleport.at(target.x, target.y, 0f, item.color);

                // Only advance the round-robin cursor on an actual
                // successful delivery, not on speculative acceptItem
                // checks -- otherwise a conveyor calling acceptItem
                // repeatedly without ever following through would
                // desync which target is "next".
                if (links.size > 0) rrIndex = (rrIndex + 1) % links.size;
            }
            // No valid link (or every link dead-ends/cycles): item just
            // vanishes (teleport failed silently), matching how
            // KryptosItemTeleporter drops items when the core is
            // unreachable.
        }

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            return pickLiquidTarget(liquid) != null;
        }

        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount) {
            Building target = pickLiquidTarget(liquid);
            if (target != null) {
                if (target instanceof KryptosTeleportLinkBuild terminal) {
                    // Terminal teleport link with nowhere further to
                    // forward -- it holds the liquid itself so nearby
                    // pipes/pumps/tanks can draw it out.
                    terminal.liquids.add(liquid, amount);
                } else {
                    target.handleLiquid(this, liquid, amount);
                }
                lastLiquid = liquid;
                KryptosFx.scanTeleportOut.at(x, y, 0f, liquid.color);
                KryptosFx.scanTeleport.at(target.x, target.y, 0f, liquid.color);

                if (links.size > 0) liquidRrIndex = (liquidRrIndex + 1) % links.size;
            }
            // No valid link (or every link dead-ends/cycles): liquid just
            // vanishes, same as a dropped item above.
        }

        // Round-robins across this block's OWN links for liquid, exactly
        // like pickTarget() does for items -- kept as a separate cursor
        // (liquidRrIndex) so item and liquid flow don't fight over which
        // target is "next".
        private Building pickLiquidTarget(Liquid liquid) {
            if (links.isEmpty()) return null;

            int[] hopsLeft = {MAX_LINK_HOPS};
            for (int i = 0; i < links.size && hopsLeft[0] > 0; i++) {
                int idx = (liquidRrIndex + i) % links.size;
                Building resolved = resolveFrom(links.get(idx), hopsLeft);
                if (resolved == null) continue;

                if (resolved instanceof KryptosTeleportLinkBuild terminal) {
                    if (terminal.liquids.get(liquid) < liquidCapacity) return terminal;
                } else if (resolved.acceptLiquid(this, liquid)) {
                    return resolved;
                }
            }
            return null;
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
            } else if (lastLiquid != null && liquids.currentAmount() > 0.001f) {
                // No item to show -- if there's liquid actually sitting in
                // storage right now, tint the same recessed socket with
                // the liquid's color instead of an item icon.
                Draw.color(Pal.darkestMetal);
                Fill.square(x, y, 3f);
                Draw.color(lastLiquid.color);
                Fill.square(x, y, 2.5f);
                Draw.color(Pal.darkOutline);
                Lines.stroke(1f);
                Lines.square(x, y, 3f);
                Draw.reset();
            }
        }

        private Building buildAt(int pos) {
            if (pos == -1) return null;
            var t = world.tile(Point2.x(pos), Point2.y(pos));
            return t != null ? t.build : null;
        }

        // Round-robins across this block's OWN links (if more than one),
        // starting from rrIndex, and returns the first resolved target that
        // can actually accept `item` right now -- or null if none can (or
        // there are no links at all). Does not mutate rrIndex itself; the
        // caller advances it only after a successful handleItem.
        private Building pickTarget(Item item) {
            if (links.isEmpty()) return null;

            int[] hopsLeft = {MAX_LINK_HOPS};
            for (int i = 0; i < links.size && hopsLeft[0] > 0; i++) {
                int idx = (rrIndex + i) % links.size;
                Building resolved = resolveFrom(links.get(idx), hopsLeft);
                if (resolved == null) continue;

                if (resolved instanceof KryptosTeleportLinkBuild terminal) {
                    if (terminal.items.total() < itemCapacity) return terminal;
                } else if (resolved.acceptItem(this, item)) {
                    return resolved;
                }
            }
            return null;
        }

        // Follows the link chain starting at `pos`, iteratively (not
        // recursively along the main chain), until it reaches either a
        // building that isn't itself a KryptosTeleportLink, or a
        // KryptosTeleportLink that has no further outgoing links of its own
        // -- both count as a valid terminal delivery point. If an
        // intermediate node itself has multiple links (nested fan-out), its
        // own links are tried in order (first one that resolves wins).
        // Returns null for a dead/missing link, a cycle back to this
        // origin block, or if the shared hop budget runs out (a longer
        // cycle or an implausibly long/wide chain).
        private Building resolveFrom(int pos, int[] hopsLeft) {
            if (hopsLeft[0]-- <= 0) return null;

            Building current = buildAt(pos);
            if (current == null || current == this) return null;

            if (!(current instanceof KryptosTeleportLinkBuild link)) {
                return current; // reached a normal, non-teleport-link building
            }
            if (link.links.isEmpty()) {
                return current; // chain ends here -- this link IS the terminal
            }

            for (int i = 0; i < link.links.size; i++) {
                Building next = resolveFrom(link.links.get(i), hopsLeft);
                if (next != null) return next;
                if (hopsLeft[0] <= 0) break;
            }
            return null;
        }

        @Override
        public byte version() {
            return 3;
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.s((short) links.size);
            for (int i = 0; i < links.size; i++) {
                write.i(links.get(i));
            }
            write.s(lastItem == null ? -1 : lastItem.id);
            write.s(lastLiquid == null ? -1 : lastLiquid.id);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            if (revision >= 2) {
                int count = read.s();
                links.clear();
                for (int i = 0; i < count; i++) {
                    links.add(read.i());
                }
            } else {
                // Old single-link saves (version 1): one packed position,
                // or -1 for "unlinked".
                int oldLinkPos = read.i();
                links.clear();
                if (oldLinkPos != -1) links.add(oldLinkPos);
            }
            short itemId = read.s();
            lastItem = itemId == -1 ? null : content.item(itemId);

            if (revision >= 3) {
                short liquidId = read.s();
                lastLiquid = liquidId == -1 ? null : content.liquid(liquidId);
            }
        }
    }
}
