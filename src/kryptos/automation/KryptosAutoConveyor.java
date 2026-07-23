package kryptos.automation;

import arc.Events;
import arc.math.geom.Point2;
import arc.struct.IntIntMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
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
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.environment.OreBlock;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.Junction;
import mindustry.world.blocks.distribution.Router;
import mindustry.world.blocks.distribution.MassDriver;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Arrays;

import static mindustry.Vars.world;

public final class KryptosAutoConveyor {

    private static final float SCAN_INTERVAL_TICKS = 60f * 10f;
    private static final int MAX_DEPOSITS_PER_CYCLE = 3;
    private static final int MAX_PATH_ATTEMPTS_PER_CYCLE = 8;
    private static final int MIN_CLUSTER_TILES = 2;
    private static final int MAX_PATH_SEARCH_TILES = 20000;
    private static final int MAX_PATH_LENGTH = 220;
    private static final int MAX_BRIDGE_LENGTH = 11;
    private static final int BRIDGE_SEARCH_RANGE = 15;
    private static final float DRILL_COVERAGE_RADIUS = 1.5f;

    private static final int[] DX4 = {1, 0, -1, 0};
    private static final int[] DY4 = {0, 1, 0, -1};
    private static final int[] DX8 = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DY8 = {0, 1, 1, 1, 0, -1, -1, -1};

    private static boolean[] visited;
    private static int visitedW, visitedH;

    private static final IntSet servedDeposits = new IntSet();
    private static float lastScanTime = -SCAN_INTERVAL_TICKS;
    private static final IntIntMap depositCoreDist = new IntIntMap();

    private KryptosAutoConveyor() {}

    public static void init() {
        Events.on(WorldLoadEvent.class, e -> reset());
        Events.run(Trigger.update, KryptosAutoConveyor::update);
    }

    public static void requestImmediateScan() {
        lastScanTime = -SCAN_INTERVAL_TICKS - 1f;
    }

    public static int servedCount() {
        return servedDeposits.size;
    }

    private static void reset() {
        visited = null;
        servedDeposits.clear();
        depositCoreDist.clear();
        lastScanTime = -SCAN_INTERVAL_TICKS;
    }

    private static void update() {
        if (!Vars.state.isGame()) return;
        if (!KryptosHud.autoplay || !KryptosAutomationPanel.autoConveyor) return;
        if (Vars.player == null || Vars.player.unit() == null) return;

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

        boolean[] seenTiles = ensureVisitedBuffer();
        int w = world.width();
        int h = world.height();
        int coreX = core.tile.x;
        int coreY = core.tile.y;

        int queuedThisCycle = 0;
        int attemptsThisCycle = 0;

        outer:
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (queuedThisCycle >= MAX_DEPOSITS_PER_CYCLE) break outer;
                if (attemptsThisCycle >= MAX_PATH_ATTEMPTS_PER_CYCLE) break outer;

                int idx = y * w + x;
                if (seenTiles[idx]) continue;

                Tile tile = world.tile(x, y);
                if (tile == null) {
                    seenTiles[idx] = true;
                    continue;
                }

                Block overlay = tile.overlay();
                if (!(overlay instanceof OreBlock)) {
                    seenTiles[idx] = true;
                    continue;
                }

                OreBlock ore = (OreBlock) overlay;
                IntSeq cluster = floodFillCluster(tile, ore, seenTiles, w, h);
                if (cluster.size < MIN_CLUSTER_TILES) continue;

                int key = clusterKey(cluster);
                if (servedDeposits.contains(key)) continue;

                DrillPlacement placement = findBestDrillPlacement(cluster, ore, coreX, coreY);
                if (placement == null) {
                    servedDeposits.add(key);
                    continue;
                }

                attemptsThisCycle++;
                IntSeq path = findPathAStar(placement.conveyorX, placement.conveyorY, core, w, h);
                if (path == null || path.size == 0 || path.size > MAX_PATH_LENGTH) {
                    servedDeposits.add(key);
                    continue;
                }

                serveDeposit(cluster, key, placement, path, core, ore);
                queuedThisCycle++;
            }
        }
    }

    private static boolean[] ensureVisitedBuffer() {
        int w = world.width();
        int h = world.height();
        if (visited == null || visitedW != w || visitedH != h) {
            visited = new boolean[w * h];
            visitedW = w;
            visitedH = h;
        }
        return visited;
    }

    private static IntSeq floodFillCluster(Tile start, Block overlay, boolean[] seenTiles, int w, int h) {
        IntSeq cluster = new IntSeq();
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        queue.add(start.pos());
        seenTiles[start.y * w + start.x] = true;

        while (!queue.isEmpty()) {
            int packed = queue.poll();
            int x = Point2.x(packed);
            int y = Point2.y(packed);
            cluster.add(packed);

            for (int dir = 0; dir < 4; dir++) {
                int nx = x + DX4[dir];
                int ny = y + DY4[dir];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;

                int nIdx = ny * w + nx;
                if (seenTiles[nIdx]) continue;

                Tile neighbor = world.tile(nx, ny);
                if (neighbor == null || neighbor.overlay() != overlay) {
                    seenTiles[nIdx] = true;
                    continue;
                }

                seenTiles[nIdx] = true;
                queue.add(neighbor.pos());
            }
        }

        return cluster;
    }

    private static int clusterKey(IntSeq cluster) {
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < cluster.size; i++) {
            min = Math.min(min, cluster.items[i]);
        }
        return min;
    }

    private static DrillPlacement findBestDrillPlacement(IntSeq cluster, OreBlock ore, int coreX, int coreY) {
        Drill bestDrill = findBestDrillForOre(ore);
        if (bestDrill == null) return null;

        int drillSize = bestDrill.size;
        int drillRadius = drillSize / 2;
        int coverage = bestDrill.drillTime > 0 ? (int) Math.ceil(DRILL_COVERAGE_RADIUS * drillSize) : drillSize;

        Seq<DrillPlacement> candidates = new Seq<>();

        for (int i = 0; i < cluster.size; i++) {
            int cx = Point2.x(cluster.items[i]);
            int cy = Point2.y(cluster.items[i]);

            for (int dx = -drillRadius; dx <= drillRadius; dx++) {
                for (int dy = -drillRadius; dy <= drillRadius; dy++) {
                    int dx_ = cx + dx;
                    int dy_ = cy + dy;

                    if (dx_ < 0 || dy_ < 0 || dx_ >= world.width() || dy_ >= world.height()) continue;

                    if (canPlaceDrill(dx_, dy_, drillSize, ore)) {
                        int covered = countOreCovered(dx_, dy_, drillSize, coverage, ore);
                        if (covered > 0) {
                            Tile conveyorTile = findBestConveyorTile(dx_, dy_, drillSize, coreX, coreY);
                            if (conveyorTile != null) {
                                int dist = Math.abs(conveyorTile.x - coreX) + Math.abs(conveyorTile.y - coreY);
                                candidates.add(new DrillPlacement(dx_, dy_, conveyorTile.x, conveyorTile.y, covered, dist, bestDrill));
                            }
                        }
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.<DrillPlacement>comparingInt(p -> -p.covered)
            .thenComparingInt(p -> p.coreDist));

        return candidates.first();
    }

    private static Drill findBestDrillForOre(OreBlock ore) {
        Seq<Block> blocks = Vars.content.blocks();
        Drill best = null;
        int bestTier = -1;

        for (Block block : blocks) {
            if (!(block instanceof Drill)) continue;
            Drill drill = (Drill) block;
            if (!drill.unlockedNow() && !Vars.state.rules.infiniteResources) continue;
            if (drill.drillTime <= 0) continue;

            if (drill.tier > bestTier) {
                bestTier = drill.tier;
                best = drill;
            }
        }

        return best != null ? best : findAnyDrill();
    }

    private static Drill findAnyDrill() {
        Seq<Block> blocks = Vars.content.blocks();
        for (Block block : blocks) {
            if (block instanceof Drill) return (Drill) block;
        }
        return null;
    }

    private static boolean canPlaceDrill(int x, int y, int size, OreBlock ore) {
        int half = size / 2;
        for (int dx = -half; dx <= half; dx++) {
            for (int dy = -half; dy <= half; dy++) {
                Tile t = world.tile(x + dx, y + dy);
                if (t == null) return false;
                if (t.block() != Blocks.air && !(t.block() instanceof OreBlock)) return false;
                if (t.floor().isLiquid) return false;
                if (t.build != null && !(t.build.block instanceof OreBlock)) return false;
            }
        }
        return true;
    }

    private static int countOreCovered(int cx, int cy, int size, int coverage, OreBlock ore) {
        int count = 0;
        int half = size / 2;
        int range = half + coverage;

        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                if (dx * dx + dy * dy > range * range) continue;
                int x = cx + dx;
                int y = cy + dy;
                if (x < 0 || y < 0 || x >= world.width() || y >= world.height()) continue;
                Tile t = world.tile(x, y);
                if (t != null && t.overlay() == ore) count++;
            }
        }
        return count;
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

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
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
        int coreX = core.tile.x;
        int coreY = core.tile.y;

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

    private static void serveDeposit(IntSeq cluster, int key, DrillPlacement placement, IntSeq path, Building core, OreBlock ore) {
        servedDeposits.add(key);

        Unit unit = Vars.player.unit();
        if (unit == null) return;

        Seq<BuildPlan> plans = new Seq<>();

        Drill bestDrill = findBestDrillForOre(ore);
        if (bestDrill != null && canPlaceDrill(placement.drillX, placement.drillY, bestDrill.size, ore)) {
            plans.add(new BuildPlan(placement.drillX, placement.drillY, 0, bestDrill));
        }

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

        Log.info("[Kryptos] AutoConveyor: queued @ drill + @ belts from @,@ ore to core.",
                plans.size, path.size, placement.drillX, placement.drillY);
    }

    private static Block selectConveyorType(int index, int pathLength, Tile tile) {
        Block existing = tile.block();
        if (existing instanceof Conveyor) return existing;

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

    private static class DrillPlacement {
        final int drillX, drillY;
        final int conveyorX, conveyorY;
        final int covered;
        final int coreDist;
        final Drill drill;

        DrillPlacement(int dx, int dy, int cx, int cy, int covered, int dist, Drill drill) {
            final int drillX, drillY;
        final int conveyorX, conveyorY;
        final int covered;
        final int coreDist;
        final Drill drill;
        }
    }
       
        DrillPlacement(int dx, int dy, int cx, int cy, int covered, int dist, Drill drill) {
            this.drillX = dx;
            this.drillY = dy;
            this.conveyorX = cx;
            this.conveyorY = cy;
            this.covered = covered;
            this.coreDist = dist;
            this.drill = drill;
        }
    }

    private static class Node {
        final int idx;
        final float f;
        Node(int idx, float f) { this.idx = idx; this.f = f; }
    }
    }
