package kryptos.automation;

import arc.Core;
import arc.Events;
import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.scene.event.Touchable;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Scaling;
import arc.util.Time;
import arc.util.Timer;
import kryptos.ui.KryptosAutomationPanel;
import kryptos.ui.KryptosHud;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.GameState.State;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.StateChangeEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Icon;
import mindustry.type.Item;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.Drill;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Tap-and-hold ore-patch drill placement, ported faithfully from
 * Ains-Code/mod-mindustry's SmartDrillFeature ("gayahin mo lahat" -- copy
 * everything) rather than KryptosSmartDrill's earlier design (an autonomous
 * background scanner with its own drone, A* belt pathing, and bridge
 * fallback). That whole approach is gone now; this is a direct port of the
 * reference instead, adapted only where Kryptos has no equivalent framework
 * to plug into (see notes below).
 *
 * Flow, unchanged from the reference: hold down on an ore tile (~100ms) ->
 * pick a direction (up/down/left/right) -> pick which unlocked drill/beam
 * drill to use -> it's placed via BuildPlans queued onto Vars.player.unit()
 * (the player's own currently-controlled unit builds it, exactly like a
 * normal manual placement -- no separate NPC drone).
 *
 * What's faithfully identical to the reference:
 * - findAllConnectedOreTiles / findAllConnectedWallOreTiles (BFS flood fill,
 *   diamond-distance sorted expansion)
 * - expandTiles (3-ring padding before stamping)
 * - isDrillTile / isBridgeTile (the fixed absolute period-6 / period-3 tile
 *   stamps -- see place2x2Drill for why these only work for size-2 drills)
 * - place2x2Drill and placeBeamDrill (the exact placement algorithms,
 *   including the bridge-chain-to-output-point logic and the beam drill
 *   column/row + power node + duct logic)
 * - The Direction enum and all its helpers
 * - getMaxTiles / isFillAll settings (see KryptosSmartDrillSettingDialog)
 *
 * What's adapted rather than ported 1:1, because Kryptos has no equivalent:
 * - mod-mindustry's TapListener is a standalone, reusable, multi-listener
 *   hold-detection service (part of their broader "Feature" plugin
 *   framework, which Kryptos doesn't have). Since this is the only consumer
 *   here, the same hold-detection logic is folded directly into update()
 *   instead of built as its own reusable class -- same detection behavior,
 *   fewer moving parts for a single caller.
 * - mod-mindustry's Feature.isEnabled() (Core.settings-backed, per-feature)
 *   maps onto KryptosAutomationPanel.autoSmartDrill, i.e. the same "Smart
 *   Drill" toggle already in the automation panel.
 * - The Feature/FeatureMetadata quick-access plugin registry itself isn't
 *   ported -- it's mod-mindustry's own infrastructure for managing many
 *   features uniformly (icon, ordering, enable persistence, etc.), and
 *   Kryptos doesn't have (or need) other features requiring that same
 *   abstraction just for this one.
 *
 * Known limitation carried over faithfully from the reference, not something
 * introduced here: place2x2Drill only actually handles drill.size == 2;
 * sizes 1/3/4 show "Not supported". Vanilla Mindustry's Mechanical Drill and
 * Pneumatic Drill are both size 2, so this covers the early game, but Laser
 * Drill (size 3) and Blast Drill (size 4) will show "Not supported" if
 * picked from the drill menu, exactly as they would in mod-mindustry.
 */
public final class KryptosSmartDrill {

    // Named Status, not State, specifically to avoid colliding with the
    // imported mindustry.core.GameState.State used a few lines down in
    // init()'s StateChangeEvent handler -- a nested enum with the same
    // simple name as an imported type shadows the import inside this class,
    // which would silently break that check (State.menu would resolve to
    // this enum instead, which has no such value).
    public enum Status { IDLE, SELECTING_DIRECTION, SELECTING_DRILL }
    private static Status state = Status.IDLE;

    public static Status state() { return state; }

    private KryptosSmartDrill() {}

    // ---- Settings (ported from SmartDrillSettingDialog's backing store) ----

    // Safety cap used when "fill entire ore patch" is enabled, so a
    // pathologically large vein can't freeze the game while scanning tiles.
    private static final int FILL_ALL_MAX_TILES = 5000;

    public static int getMaxTiles(Block drill) {
        if (isFillAll(drill)) {
            return FILL_ALL_MAX_TILES;
        }
        return Core.settings.getInt("kryptos.smart-drill.max-tiles." + drill.name, 100);
    }

    public static boolean isFillAll(Block drill) {
        return Core.settings.getBool("kryptos.smart-drill.fill-all." + drill.name, false);
    }

    // ---- Hold-detection (folded-in equivalent of mod-mindustry's TapListener) ----

    private static final long HOLD_DURATION_MILLIS = 100;

    private static Tile touchTile;
    private static long touchTime;
    private static boolean wasTouched;
    private static boolean triggeredThisTouch;

    private static Table currentMenu;
    private static Tile selectedTile;

    public static void init() {
        Events.on(WorldLoadEvent.class, e -> {
            closeMenu();
            resetHold();
        });

        Events.on(TapEvent.class, e -> {
            if (!enabled()) return;
            if (currentMenu != null && e.tile != selectedTile) {
                closeMenu();
            }
        });

        Events.on(StateChangeEvent.class, e -> {
            if (e.to == State.menu) {
                closeMenu();
            }
        });

        Events.run(Trigger.update, KryptosSmartDrill::updateHold);
    }

    private static boolean enabled() {
        return KryptosHud.autoplay && KryptosAutomationPanel.autoSmartDrill;
    }

    private static void updateHold() {
        if (!Vars.state.isGame() || Vars.state.isMenu() || Core.scene.hasMouse()) {
            resetHold();
            return;
        }
        if (!enabled()) {
            resetHold();
            return;
        }

        if (Core.input.isTouched()) {
            Vec2 pos = Core.input.mouseWorld();
            Tile currentTile = Vars.world.tileWorld(pos.x, pos.y);

            if (!wasTouched) {
                wasTouched = true;
                touchTime = Time.millis();
                touchTile = currentTile;
                triggeredThisTouch = false;
            } else if (currentTile != touchTile) {
                // Dragged to a different tile -- restart the hold timer there.
                touchTile = currentTile;
                touchTime = Time.millis();
                triggeredThisTouch = false;
            }

            if (touchTile != null && !triggeredThisTouch) {
                long holdDuration = Time.timeSinceMillis(touchTime);
                if (holdDuration >= HOLD_DURATION_MILLIS) {
                    triggeredThisTouch = true;
                    if (currentMenu == null && touchTile.build == null) {
                        handleHold(touchTile);
                    }
                }
            }
        } else {
            resetHold();
        }
    }

    private static void resetHold() {
        wasTouched = false;
        touchTile = null;
        triggeredThisTouch = false;
    }

    // ---- Menu flow (direct port) ----

    private static void handleHold(Tile tile) {
        Item drop = tile.drop();
        Item wallDrop = tile.wallDrop();

        if (drop != null) {
            showDirectionMenu(tile)
                .thenAccept(direction -> {
                    if (direction == null) return;
                    showDrillMenu(tile, drop, direction, false);
                });
        } else if (wallDrop != null) {
            showDirectionMenu(tile)
                .thenAccept(direction -> {
                    if (direction == null) return;
                    showDrillMenu(tile, wallDrop, direction, true);
                });
        } else {
            closeMenu();
        }
    }

    private static void closeMenu() {
        if (currentMenu != null) {
            currentMenu.remove();
            currentMenu = null;
            selectedTile = null;
        }
        state = Status.IDLE;
    }

    private static CompletableFuture<Direction> showDirectionMenu(Tile tile) {
        closeMenu();

        CompletableFuture<Direction> future = new CompletableFuture<>();

        selectedTile = tile;
        state = Status.SELECTING_DIRECTION;
        currentMenu = new Table();
        currentMenu.visible(() -> Vars.ui.hudfrag != null && Vars.ui.hudfrag.shown);
        currentMenu.touchable = Touchable.enabled;

        currentMenu.update(() -> {
            if (selectedTile == null) {
                closeMenu();
                future.complete(null);
                return;
            }
            Vec2 pos = Core.camera.project(selectedTile.worldx(), selectedTile.worldy());
            currentMenu.setPosition(pos.x, pos.y, Align.center);
        });

        Table directionTable = new Table();

        directionTable.add().size(48f);
        directionTable.button(Icon.up, () -> future.complete(Direction.UP)).size(48f).pad(4);
        directionTable.add().size(48f).row();

        directionTable.button(Icon.left, () -> future.complete(Direction.LEFT)).size(48f).pad(4);
        directionTable.button(Icon.cancel, () -> {
            future.complete(null);
            closeMenu();
        }).size(48f).pad(4);
        directionTable.button(Icon.right, () -> future.complete(Direction.RIGHT)).size(48f).pad(4).row();

        directionTable.add().size(48f);
        directionTable.button(Icon.down, () -> future.complete(Direction.DOWN)).size(48f).pad(4);
        directionTable.add().size(48f);

        currentMenu.add(directionTable);

        Vars.ui.hudGroup.addChild(currentMenu);
        Timer.schedule(() -> {
            if (currentMenu != null) currentMenu.toFront();
        }, 0.1f);
        currentMenu.pack();

        return future;
    }

    private static void showDrillMenu(Tile tile, Item drop, Direction direction, boolean isBeam) {
        if (currentMenu == null) return;

        state = Status.SELECTING_DRILL;
        currentMenu.clear();

        int i = 0;
        Seq<Block> drills = Vars.content.blocks()
            .select(block -> isBeam ? isValidBeamDrill(block, drop) : isValidDrill(block, drop));

        for (Block block : drills) {
            currentMenu.button(b -> b.image(block.uiIcon).scaling(Scaling.fit), Styles.clearNonei, () -> {
                closeMenu();
                Core.app.post(() -> {
                    Vars.control.input.isBuilding = false;
                    if (isBeam) {
                        placeBeamDrill(tile, direction, (BeamDrill) block, drop);
                    } else {
                        placeDrill(tile, direction, block, drop);
                    }
                });
            }).size(48f).pad(4);

            if (++i % 4 == 0) currentMenu.row();
        }

        if (i == 0) {
            currentMenu.add("@none").pad(8);
        }

        currentMenu.pack();
        currentMenu.toFront();
    }

    private static boolean isValidDrill(Block block, Item drop) {
        if (!unlocked(block)) return false;
        if (block instanceof Drill drill) return drill.tier >= drop.hardness;
        return false;
    }

    private static boolean isValidBeamDrill(Block block, Item drop) {
        if (!unlocked(block)) return false;
        if (block instanceof BeamDrill beamDrill) return beamDrill.tier >= drop.hardness;
        return false;
    }

    private static boolean unlocked(Block block) {
        return block.unlockedNowHost() && block.placeablePlayer && block.environmentBuildable()
            && block.supportsEnv(Vars.state.rules.env);
    }

    // ---- Placement (direct port) ----

    private static void placeDrill(Tile tile, Direction direction, Block drill, Item drop) {
        switch (drill.size) {
            case 2:
                place2x2Drill(tile, direction, drill, drop);
                break;
            case 1:
            case 3:
            case 4:
            default:
                Vars.ui.showInfoFade("Not supported");
                break;
        }
    }

    private static void place2x2Drill(Tile tile, Direction direction, Block drill, Item drop) {
        var unit = Vars.player.unit();
        if (unit == null) return;

        Seq<Tile> tiles = findAllConnectedOreTiles(tile, drop, getMaxTiles(drill));
        if (tiles.isEmpty()) return;

        tiles.retainAll(t -> t.drop() == drop);
        expandTiles(tiles, 3);

        var drillTiles = tiles.select(KryptosSmartDrill::isDrillTile);
        var bridgeTiles = tiles.select(KryptosSmartDrill::isBridgeTile);

        for (Tile drillTile : drillTiles) {
            BuildPlan plan = new BuildPlan(drillTile.x, drillTile.y, direction.rotation, drill);
            if (plan.placeable(Vars.player.team())) {
                unit.addBuild(plan);
            }
        }

        var outMostTile = tiles.max(t -> switch (direction) {
            case UP -> t.y;
            case DOWN -> -t.y;
            case LEFT -> -t.x;
            case RIGHT -> t.x;
        });

        bridgeTiles.sort(t -> t.dst2(outMostTile));
        var output = bridgeTiles.first().nearby(direction.mul(3));
        if (output == null) {
            output = bridgeTiles.first();
        }
        var outputBridge = output;
        bridgeTiles.add(output);
        bridgeTiles.sort(t -> t.dst2(outputBridge));

        for (Tile bridgeTile : bridgeTiles) {
            Tile neighbor = bridgeTiles.find(t -> Math.abs(t.x - bridgeTile.x) + Math.abs(t.y - bridgeTile.y) == 3);
            Point2 config = new Point2();
            if (neighbor != null && bridgeTile != outputBridge) {
                config.set(neighbor.x - bridgeTile.x, neighbor.y - bridgeTile.y);
            }
            BuildPlan plan = new BuildPlan(bridgeTile.x, bridgeTile.y, 0, Blocks.itemBridge, config);
            if (plan.placeable(Vars.player.team())) {
                unit.addBuild(plan);
            }
        }
    }

    private static void placeBeamDrill(Tile tile, Direction direction, BeamDrill drill, Item drop) {
        Seq<Tile> ores = findAllConnectedWallOreTiles(tile, drop, getMaxTiles(drill));
        if (ores.isEmpty()) return;

        var opposite = direction.opposite();

        HashMap<Integer, Boolean> hasDrill = new HashMap<>();
        Seq<BuildPlan> drillPlans = new Seq<>();
        int half = (drill.size - 1) / 2;

        for (Tile ore : ores) {
            int gridx = (ore.x / drill.size) * drill.size;
            int gridy = (ore.y / drill.size) * drill.size;

            int key = direction == Direction.LEFT || direction == Direction.RIGHT ? gridy : gridx;
            if (hasDrill.containsKey(key)) continue;
            hasDrill.put(key, true);

            for (int i = 1; i < drill.range; i++) {
                int reach = half + 1;
                int x = gridx + opposite.mul(i).x + half;
                int y = gridy + opposite.mul(i).y + half;

                BuildPlan drillPlan = new BuildPlan(x, y, direction.rotation, drill);

                int nodeOffX = direction.horizontal()
                    ? (direction == Direction.LEFT ? (reach + (drill.size % 2 == 0 ? 1 : 0)) : -reach)
                    : 0;
                int nodeOffY = direction.vertical()
                    ? reach * (direction == Direction.DOWN ? (reach + (drill.size % 2 == 0 ? 1 : 0)) : -reach)
                    : 0;

                int ductOffX = nodeOffX == 0 ? 1 : 0;
                int ductOffY = nodeOffY == 0 ? 1 : 0;

                BuildPlan powerNodePlan = new BuildPlan(x + nodeOffX, y + nodeOffY, direction.rotation, Blocks.beamNode);
                BuildPlan drillDuctPlan = new BuildPlan(x + nodeOffX + ductOffX, y + nodeOffY + ductOffY, opposite.rotation, Blocks.duct);

                if (drillPlan.placeable(Vars.player.team()) && powerNodePlan.placeable(Vars.player.team())
                    && drillDuctPlan.placeable(Vars.player.team())) {
                    Vars.player.unit().addBuild(drillPlan);
                    Vars.player.unit().addBuild(powerNodePlan);
                    Vars.player.unit().addBuild(drillDuctPlan);
                    drillPlans.add(drillPlan);
                    break;
                }
            }

            drillPlans.sort(plan -> (direction == Direction.DOWN || direction == Direction.UP) ? plan.x : plan.y);

            Direction ductDirection = direction == Direction.LEFT || direction == Direction.RIGHT ? Direction.UP : Direction.RIGHT;

            for (int planIndex = 0; planIndex < drillPlans.size - 1; planIndex++) {
                BuildPlan plan = drillPlans.get(planIndex);

                for (int j = 0; j < drill.size; j++) {
                    int reach = half + 2;

                    int offX = direction.horizontal()
                        ? (direction == Direction.LEFT ? (reach + (drill.size % 2 == 0 ? 1 : 0)) : -reach)
                        : j - half;
                    int offY = direction.vertical()
                        ? reach * (direction == Direction.DOWN ? (reach + (drill.size % 2 == 0 ? 1 : 0)) : -reach)
                        : j - half;

                    var nextPlan = drillPlans.get(planIndex + 1);
                    var connectDuctDirection = Direction.UP;

                    if (ductDirection == Direction.RIGHT) {
                        if (plan.y == nextPlan.y) continue;
                        connectDuctDirection = plan.y > nextPlan.y ? Direction.DOWN : Direction.UP;
                    } else {
                        if (plan.x == nextPlan.x) continue;
                        connectDuctDirection = plan.x > nextPlan.x ? Direction.RIGHT : Direction.LEFT;
                    }

                    BuildPlan ductPlan = new BuildPlan(plan.x + offX, plan.y + offY, ductDirection.rotation, Blocks.armoredDuct);
                    Vars.player.unit().addBuild(ductPlan);

                    // Matches the reference exactly -- this extra connecting
                    // duct plan is computed but deliberately left unqueued
                    // there too (commented out), not something dropped here.
                    // BuildPlan extraDuctPlan = new BuildPlan(plan.x + opposite.x + drill.size,
                    //     plan.y + opposite.y + drill.size, connectDuctDirection.rotation, Blocks.duct);
                }
            }
        }
    }

    // ---- Flood fill / stamping (direct port) ----

    private static Seq<Tile> findAllConnectedWallOreTiles(Tile start, Item drop, int maxTiles) {
        Seq<Tile> tiles = new Seq<>();
        Seq<Tile> queue = new Seq<>();
        ObjectSet<Tile> visited = new ObjectSet<>();

        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty() && tiles.size < maxTiles) {
            Tile t = queue.remove(0);
            tiles.add(t);

            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    if (x == 0 && y == 0) continue;
                    Tile neighbor = t.nearby(x, y);
                    if (neighbor == null || visited.contains(neighbor) || neighbor.wallDrop() != drop) continue;
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        return tiles;
    }

    private static Seq<Tile> findAllConnectedOreTiles(Tile start, Item drop, int maxTiles) {
        Seq<Tile> tiles = new Seq<>();
        Seq<Tile> queue = new Seq<>();
        ObjectSet<Tile> visited = new ObjectSet<>();

        int centerX = start.x;
        int centerY = start.y;

        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty() && tiles.size < maxTiles) {
            queue.sort(t -> {
                float dx = Math.abs(t.x - centerX);
                float dy = Math.abs(t.y - centerY);
                float diff = Math.abs(dx - dy);
                return Math.max(dx * dx + diff, dy * dy + diff);
            });

            Tile t = queue.remove(0);
            tiles.add(t);

            for (int i = 0; i < 4; i++) {
                Tile neighbor = t.nearby(i);
                if (neighbor == null || visited.contains(neighbor)
                    || (neighbor.drop() != drop && neighbor.wallDrop() != drop)) continue;
                visited.add(neighbor);
                queue.add(neighbor);
            }
        }

        return tiles;
    }

    private static void expandTiles(Seq<Tile> tiles, int times) {
        for (int i = 0; i < times; i++) {
            expandTiles(tiles);
        }
    }

    private static void expandTiles(Seq<Tile> tiles) {
        Seq<Tile> newTiles = new Seq<>();
        for (Tile tile : tiles) {
            for (int i = 0; i < 4; i++) {
                Tile neighbor = tile.nearby(i);
                if (neighbor == null || tiles.contains(neighbor)) continue;
                newTiles.addUnique(neighbor);
            }
        }
        tiles.addAll(newTiles);
    }

    // Fixed absolute period-6 "brick" stamp for 2x2 drills -- NOT relative to
    // the ore patch, the same world tile coordinates always stamp true, so
    // every patch on the map tiles consistently against the same grid.
    // This is exactly why place2x2Drill only supports size-2 drills: the
    // stamp geometry below is hand-tuned for that footprint specifically.
    private static boolean isDrillTile(Tile tile) {
        switch (tile.x % 6) {
            case 0:
            case 2:
                if ((tile.y - 1) % 6 == 0) return true;
                break;
            case 1:
                if ((tile.y - 3) % 6 == 0 || (tile.y - 3) % 6 == 2) return true;
                break;
            case 3:
            case 5:
                if ((tile.y - 4) % 6 == 0) return true;
                break;
            case 4:
                if ((tile.y) % 6 == 0 || (tile.y) % 6 == 2) return true;
                break;
        }
        return false;
    }

    private static boolean isBridgeTile(Tile tile) {
        return tile.x % 3 == 0 && tile.y % 3 == 0;
    }

    // ---- Direction (direct port) ----

    public enum Direction {
        RIGHT(1, 0, 0), UP(0, 1, 1), LEFT(-1, 0, 2), DOWN(0, -1, 3);

        public final int x, y, rotation;

        Direction(int x, int y, int rotation) {
            this.x = x;
            this.y = y;
            this.rotation = rotation;
        }

        public boolean horizontal() { return this == RIGHT || this == LEFT; }
        public boolean vertical() { return this == UP || this == DOWN; }

        public Point2 mul(int i) {
            return new Point2(x * i, y * i);
        }

        public Direction opposite() {
            return switch (this) {
                case RIGHT -> LEFT;
                case UP -> DOWN;
                case LEFT -> RIGHT;
                case DOWN -> UP;
            };
        }
    }
}
