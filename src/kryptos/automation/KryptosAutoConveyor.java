package kryptos.automation;

import arc.Events;
import arc.math.geom.Point2;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import kryptos.ui.KryptosAutomationPanel;
import kryptos.ui.KryptosHud;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.Junction;
import mindustry.world.blocks.distribution.Router;
import mindustry.world.blocks.distribution.MassDriver;

import java.util.ArrayDeque;
import java.util.PriorityQueue;
import java.util.Arrays;

import static mindustry.Vars.world;

/**
 * AutoConveyor's job: keep every drill connected to the core with belts.
 *
 * It does NOT place new drills anymore -- that's KryptosSmartDrill's job
 * exclusively now. This module only looks at drills that already exist
 * (built by Smart Drill, or by hand) and, for any that don't have a belt
 * path running out to the core yet, builds just the missing conveyor run.
 * Division of labor: Smart Drill claims fresh ore and builds drill+belts
 * together; AutoConveyor mops up afterward and adds/repairs conveyor runs
 * wherever a drill is still sitting unserved.
 */
public final class KryptosAutoConveyor {

    private static final float SCAN_INTERVAL_TICKS = 60f * 10f;
    private static final int MAX_DRILLS_PER_CYCLE = 3;
    private static final int MAX_PATH_ATTEMPTS_PER_CYCLE = 8;
    private static final int MAX_PATH_SEARCH_TILES = 20000;
    private static final int MAX_PATH_LENGTH = 220;
    private static final int MAX_BRIDGE_LENGTH = 11;

    private static final int[] DX4 = {1, 0, -1, 0};
    private static final int[] DY4 = {0, 1, 0, -1};

    private static float lastScanTime = -SCAN_INTERVAL_TICKS;

    // Drills we've already handled (either successfully connected, or tried
    // and failed to find a route for) -- keyed by the drill's tile position,
    // separate from KryptosOreRegistry (that one tracks claimed ORE
    // clusters for Smart Drill; this tracks served DRILL buildings, a
    // different concept, so they're kept in their own set to avoid any key
    // collision between the two).
    private static final IntSet servedDrills = new IntSet();

    // The drone that actually flies out and builds -- spawned the moment
    // Auto Conveyor is switched on (see requestImmediateScan()), reused for
    // as long as it's alive. See KryptosBuilderUnits.getOrSpawn().
    private static Unit helperUnit;

    private KryptosAutoConveyor() {}

    public static void init() {
        Events.on(WorldLoadEvent.class, e -> reset());
        Events.run(Trigger.update, KryptosAutoConveyor::update);
    }

    public static void requestImmediateScan() {
        lastScanTime = -SCAN_INTERVAL_TICKS - 1f;
        ensureHelper();
    }

    private static void ensureHelper() {
        if (Vars.player == null) return;
        helperUnit = KryptosBuilderUnits.getOrSpawn(helperUnit, Vars.player.team());
    }

    public static int servedCount() {
        return servedDrills.size;
    }

    private static void reset() {
        lastScanTime = -SCAN_INTERVAL_TICKS;
        servedDrills.clear();
        helperUnit = null;
        // Kills any drone left over from a previous session/save that our
        // static reference above doesn't know about -- see
        // KryptosBuilderUnits.killAll() for why this matters. Shared with
        // KryptosSmartDrill's reset(), so this may run twice per load, which
        // is harmless (killing an already-dead unit is a no-op).
        KryptosBuilderUnits.killAll();
        // Shared with KryptosSmartDrill; both modules reset on WorldLoadEvent
        // so this may run twice per load, which is harmless (IntSet.clear()
        // is idempotent).
        KryptosOreRegistry.reset();
    }

    private static void update() {
        if (!Vars.state.isGame()) return;
        if (!KryptosHud.autoplay || !KryptosAutomationPanel.autoConveyor) return;
        if (Vars.player == null) return;

        ensureHelper();
        if (helperUnit == null) return;

        float now = Time.time;
        if (now - lastScanTime < SCAN_INTERVAL_TICKS) return;
        lastScanTime = now;

        try {
            scanAndBuild();
        } catch (Throwable t) {
            Log.err("[Kryptos] AutoConveyor scan failed, disabling module to avoid repeat crashes", t);
            KryptosAutomationPanel.autoConveyor = false;
        }
    }

    private static void scanAndBuild() {
        Team team = Vars.player.team();
        Building core = team.core();
        if (core == null) return;

        int w = world.width();
        int h = world.height();
        int coreX = core.tile.x;
        int coreY = core.tile.y;

        // Collect every not-yet-served drill first and sort by distance to
        // core, same reasoning as before: working outward from the core
        // instead of jumping between far-flung drills in whatever order
        // Groups.build happens to iterate them.
        Seq<Building> candidates = new Seq<>();

        Groups.build.each(b -> {
            if (b.team != team) return;
            if (!(b.block instanceof Drill)) return;
            if (servedDrills.contains(Point2.pack(b.tile.x, b.tile.y))) return;
            candidates.add(b);
        });

        candidates.sort((a, b) -> Integer.compare(distToCore(a, coreX, coreY), distToCore(b, coreX, coreY)));

        int queuedThisCycle = 0;
        int attemptsThisCycle = 0;

        for (Building drill : candidates) {
            if (queuedThisCycle >= MAX_DRILLS_PER_CYCLE) break;
            if (attemptsThisCycle >= MAX_PATH_ATTEMPTS_PER_CYCLE) break;

            int key = Point2.pack(drill.tile.x, drill.tile.y);

            Tile startTile = findBestConveyorTile(drill.tile.x, drill.tile.y, drill.block.size, coreX, coreY);
            if (startTile == null) {
                servedDrills.add(key);
                continue;
            }

            if (startTile.block() instanceof Conveyor) {
                // Already has a belt leading out of it -- consider it served.
                servedDrills.add(key);
                continue;
            }

            attemptsThisCycle++;
            IntSeq path = findPathAStar(startTile.x, startTile.y, core, w, h);
            if (path == null || path.size == 0 || path.size > MAX_PATH_LENGTH) {
                servedDrills.add(key);
                continue;
            }

            serveDrillPath(drill, key, path, core);
            queuedThisCycle++;
        }
    }

    private static int distToCore(Building b, int coreX, int coreY) {
        return Math.abs(b.tile.x - coreX) + Math.abs(b.tile.y - coreY);
    }

    private static Tile findBestConveyorTile(int drillX, int drillY, int drillSize, int coreX, int coreY) {
        int half = drillSize / 2;
        Tile best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int dir = 0; dir < 4; dir++) {
            int cx = drillX + DX4[dir] * (half + 1);
            int cy = drillY + DY4[dir] * (half + 1);

            if (cx < 0 || cy < 0 || cx >= world.width() || cy >= world.height()) continue;

            Tile t = world.tile(cx, cy);
            if (t == null) continue;
            if (!isConveyorWalkable(t)) continue;

            int dist = Math.abs(cx - coreX) + Math.abs(cy - coreY);
            if (dist < bestDist) {
                bestDist = dist;
                best = t;
            }
        }

        return best;
    }

    private static IntSeq findPathAStar(int startX, int startY, Building core, int w, int h) {
        return findPathAStar(startX, startY, core, w, h, true);
    }

    private static IntSeq findPathAStar(int startX, int startY, Building core, int w, int h, boolean allowBridge) {
        int startIdx = startY * w + startX;
        int coreX = core.tile.x;
        int coreY = core.tile.y;

        boolean[] closed = new boolean[w * h];
        int[] prev = new int[w * h];
        float[] gScore = new float[w * h];
        float[] fScore = new float[w * h];
        Arrays.fill(gScore, Float.MAX_VALUE);
        Arrays.fill(fScore, Float.MAX_VALUE);
        Arrays.fill(prev, -1);

        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Float.compare(a.f, b.f));

        gScore[startIdx] = 0;
        fScore[startIdx] = heuristic(startX, startY, coreX, coreY);
        open.add(new Node(startIdx, fScore[startIdx]));

        int goalIdx = -1;
        int steps = 0;

        while (!open.isEmpty() && steps < MAX_PATH_SEARCH_TILES) {
            Node current = open.poll();
            int idx = current.idx;
            steps++;

            if (closed[idx]) continue;
            closed[idx] = true;

            int x = idx % w;
            int y = idx / w;

            if (touchesCore(x, y, core)) {
                goalIdx = idx;
                break;
            }

            for (int dir = 0; dir < 4; dir++) {
                int nx = x + DX4[dir];
                int ny = y + DY4[dir];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;

                int nIdx = ny * w + nx;
                if (closed[nIdx]) continue;

                Tile t = world.tile(nx, ny);
                if (!isConveyorWalkable(t)) continue;

                float tentativeG = gScore[idx] + moveCost(t, dir);

                if (tentativeG < gScore[nIdx]) {
                    prev[nIdx] = idx;
                    gScore[nIdx] = tentativeG;
                    fScore[nIdx] = tentativeG + heuristic(nx, ny, coreX, coreY);
                    open.add(new Node(nIdx, fScore[nIdx]));
                }
            }
        }

        if (goalIdx == -1) {
            return allowBridge ? tryBridgePath(startX, startY, core, w, h) : null;
        }

        return reconstructPath(prev, goalIdx, w);
    }

    private static IntSeq tryBridgePath(int startX, int startY, Building core, int w, int h) {
        for (int dir = 0; dir < 4; dir++) {
            for (int len = 2; len <= MAX_BRIDGE_LENGTH; len++) {
                int bx = startX + DX4[dir] * len;
                int by = startY + DY4[dir] * len;
                if (bx < 0 || by < 0 || bx >= w || by >= h) break;

                if (isConveyorWalkable(world.tile(bx, by))) {
                    IntSeq path = findPathAStar(bx, by, core, w, h, false);
                    if (path != null && path.size > 0) {
                        IntSeq bridgePath = new IntSeq();
                        for (int l = 1; l <= len; l++) {
                            int bx2 = startX + DX4[dir] * l;
                            int by2 = startY + DY4[dir] * l;
                            bridgePath.add(Point2.pack(bx2, by2));
                        }
                        for (int i = 0; i < path.size; i++) {
                            bridgePath.add(path.items[i]);
                        }
                        return bridgePath;
                    }
                }
            }
        }
        return null;
    }

    private static float heuristic(int x, int y, int goalX, int goalY) {
        return Math.abs(x - goalX) + Math.abs(y - goalY);
    }

    private static float moveCost(Tile t, int dir) {
        Block b = t.block();
        if (b == Blocks.air) return 1f;
        if (b instanceof Conveyor) return 0.5f;
        if (b instanceof MassDriver) return 0.8f;
        if (b instanceof Junction) return 0.6f;
        return 1f;
    }

    private static IntSeq reconstructPath(int[] prev, int goalIdx, int w) {
        IntSeq path = new IntSeq();
        int cur = goalIdx;
        while (cur != -1) {
            path.add(Point2.pack(cur % w, cur / w));
            cur = prev[cur];
        }
        for (int a = 0, b = path.size - 1; a < b; a++, b--) {
            int tmp = path.items[a];
            path.items[a] = path.items[b];
            path.items[b] = tmp;
        }
        return path;
    }

    private static boolean isConveyorWalkable(Tile t) {
        if (t == null) return false;
        if (t.floor().isLiquid) return false;
        if (t.solid()) return false;
        Block b = t.block();
        return b == Blocks.air || b instanceof Conveyor || b instanceof MassDriver || b instanceof Junction || b instanceof Router;
    }

    private static boolean touchesCore(int x, int y, Building core) {
        for (int dir = 0; dir < 4; dir++) {
            Tile n = world.tile(x + DX4[dir], y + DY4[dir]);
            if (n != null && n.build == core) return true;
        }
        return false;
    }

    private static void serveDrillPath(Building drill, int key, IntSeq path, Building core) {
        servedDrills.add(key);

        Unit unit = helperUnit;
        if (unit == null) return;

        Seq<BuildPlan> plans = new Seq<>();

        for (int i = 0; i < path.size; i++) {
            int x = Point2.x(path.items[i]);
            int y = Point2.y(path.items[i]);
            Tile tile = world.tile(x, y);
            if (tile == null || tile.block() instanceof Conveyor) continue;

            int rotation;
            if (i < path.size - 1) {
                int nx = Point2.x(path.items[i + 1]);
                int ny = Point2.y(path.items[i + 1]);
                rotation = rotationFor(nx - x, ny - y);
            } else {
                rotation = rotationTowardCore(x, y, core);
            }

            Block conveyorType = selectConveyorType(i, path.size, tile);
            plans.add(new BuildPlan(x, y, rotation, conveyorType));
        }

        for (BuildPlan plan : plans) {
            unit.addBuild(plan);
        }

        Log.info("[Kryptos] AutoConveyor: queued @ belts for drill at @,@ -> core.",
                plans.size, drill.tile.x, drill.tile.y);
    }

    private static Block selectConveyorType(int index, int pathLength, Tile tile) {
        Block existing = tile.block();
        if (existing instanceof Conveyor) return existing;

        Block fieldMatch = KryptosFieldTier.matchExistingConveyor(Vars.player.team());
        if (fieldMatch != null) return fieldMatch;

        if (index == pathLength - 1) {
            return Blocks.conveyor;
        }

        if (Vars.state.rules.infiniteResources || hasTitanium()) {
            return Blocks.titaniumConveyor;
        }

        return Blocks.conveyor;
    }

    private static boolean hasTitanium() {
        return Vars.player.team().core().items.get(Items.titanium) > 50;
    }

    private static int rotationFor(int dx, int dy) {
        for (int dir = 0; dir < 4; dir++) {
            if (DX4[dir] == dx && DY4[dir] == dy) return dir;
        }
        return 0;
    }

    private static int rotationTowardCore(int x, int y, Building core) {
        for (int dir = 0; dir < 4; dir++) {
            Tile neighbor = world.tile(x + DX4[dir], y + DY4[dir]);
            if (neighbor != null && neighbor.build == core) return dir;
        }
        return 0;
    }

    private static class Node {
        final int idx;
        final float f;
        Node(int idx, float f) { this.idx = idx; this.f = f; }
    }
}
