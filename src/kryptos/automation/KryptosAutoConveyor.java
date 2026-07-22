package kryptos.automation;

import arc.Events;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.util.Log;
import arc.util.Time;
import kryptos.ui.KryptosAutomationPanel;
import kryptos.ui.KryptosHud;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.math.geom.Point2;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.environment.OreBlock;

import java.util.ArrayDeque;
import java.util.Arrays;

import static mindustry.Vars.world;

/**
 * "Auto Conveyor" automation module -- toggled from {@link KryptosAutomationPanel},
 * which itself only appears while {@link KryptosHud#autoplay} is on.
 *
 * <h2>What it does</h2>
 * Every {@link #SCAN_INTERVAL_TICKS} ticks (or immediately after being
 * switched on, see {@link #requestImmediateScan()}) it:
 * <ol>
 *   <li>Scans the loaded world for ore ({@link Tile#overlay()} instanceof
 *   {@link OreBlock}) and groups contiguous same-type ore into deposits
 *   (flood fill).</li>
 *   <li>For each deposit not yet served, finds the walkable tile bordering
 *   it that is closest to the player's core -- that tile becomes the belt's
 *   starting point (a drill placed on the ore later would feed directly
 *   into it).</li>
 *   <li>Runs a breadth-first search from that tile to the core (walkable =
 *   non-solid, non-liquid, empty or already-a-conveyor tiles) to build an
 *   actual buildable route, not just a straight line through walls.</li>
 *   <li>Queues one {@link Blocks#conveyor} {@link BuildPlan} per path tile,
 *   correctly rotated, onto {@code Vars.player.unit()}. Construction itself
 *   is handled entirely by the game's existing build-queue system (the same
 *   one used when a player shift-clicks a multi-tile belt run), so it stays
 *   resource-consuming, deterministic, and multiplayer-safe -- this class
 *   never touches {@code Tile.setBlock} directly.</li>
 * </ol>
 *
 * <h2>Explicit scope (per design decision)</h2>
 * This module does not place drills and does not infer them. It only lays
 * conveyor infrastructure from bare ore tiles to the core, so any drill
 * dropped on that ore afterwards already has an outbound belt.
 *
 * <h2>Performance</h2>
 * The full-grid classification scan is real -- there is no per-frame
 * incremental scanner -- but it only walks a boolean-per-tile buffer
 * ({@link #ensureVisitedBuffer()}, allocated once per world and reused) and
 * is gated behind {@link #SCAN_INTERVAL_TICKS}, so it runs at most once
 * every ~10 seconds while enabled. Once a tile has been classified (ore
 * cluster member, or not-ore) it is never re-examined, so the *cost* of
 * repeated scans drops to near-zero after the first pass; only genuinely
 * new/unserved deposits do any BFS work, and at most
 * {@link #MAX_DEPOSITS_PER_CYCLE} of those per cycle.
 */
public final class KryptosAutoConveyor {

    private static final float SCAN_INTERVAL_TICKS = 60f * 10f; // ~10s between full scans
    private static final int MAX_DEPOSITS_PER_CYCLE = 3;
    private static final int MIN_CLUSTER_TILES = 2;
    private static final int MAX_PATH_SEARCH_TILES = 20_000; // BFS node budget per deposit
    private static final int MAX_PATH_LENGTH = 220; // discard routes longer than this

    // Cardinal directions; index doubles as Mindustry's block rotation value
    // (0 = right/east, 1 = up/north, 2 = left/west, 3 = down/south).
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DY = {0, 1, 0, -1};

    private static boolean[] visited;
    private static int visitedW, visitedH;

    private static final IntSet servedDeposits = new IntSet();
    private static float lastScanTime = -SCAN_INTERVAL_TICKS;

    private KryptosAutoConveyor() {
        // Utility class
    }

    public static void init() {
        Events.on(WorldLoadEvent.class, e -> reset());
        Events.run(Trigger.update, KryptosAutoConveyor::update);
    }

    /** Forces the next update tick to run a scan immediately instead of waiting for the interval. */
    public static void requestImmediateScan() {
        lastScanTime = -SCAN_INTERVAL_TICKS - 1f;
    }

    public static int servedCount() {
        return servedDeposits.size;
    }

    private static void reset() {
        visited = null;
        servedDeposits.clear();
        lastScanTime = -SCAN_INTERVAL_TICKS;
    }

    private static void update() {
        if (!Vars.state.isGame()) return;
        if (!KryptosHud.autoplay || !KryptosAutomationPanel.autoConveyor) return;
        if (Vars.player == null || Vars.player.unit() == null) return;

        float now = Time.time;
        if (now - lastScanTime < SCAN_INTERVAL_TICKS) return;
        lastScanTime = now;

        scanAndBuild();
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

        outer:
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (queuedThisCycle >= MAX_DEPOSITS_PER_CYCLE) break outer;

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

                IntSeq cluster = floodFillCluster(tile, overlay, seenTiles, w, h);
                if (cluster.size < MIN_CLUSTER_TILES) continue;

                int key = clusterKey(cluster);
                if (servedDeposits.contains(key)) continue;

                Tile anchor = findAnchorTile(cluster, coreX, coreY);
                if (anchor == null) continue;

                IntSeq path = findPathToCore(anchor, core, w, h);
                // Whether or not a path was found, don't retry this exact
                // deposit shape again until the world reloads -- either it
                // built successfully, or the core is genuinely unreachable
                // from it and re-attempting every cycle would be wasted work.
                servedDeposits.add(key);

                if (path == null || path.size == 0 || path.size > MAX_PATH_LENGTH) continue;

                queueConveyorPlan(path, core);
                queuedThisCycle++;

                Log.info("[Kryptos] AutoConveyor: queued @ belt tile(s) from ore near @,@ to core.",
                        path.size, anchor.x, anchor.y);
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

    /** 4-directional flood fill over tiles sharing the exact same ore overlay block. */
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
                int nx = x + DX[dir];
                int ny = y + DY[dir];
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

    /** Stable per-deposit key (its lowest packed tile position) used to avoid re-serving the same deposit. */
    private static int clusterKey(IntSeq cluster) {
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < cluster.size; i++) {
            min = Math.min(min, cluster.items[i]);
        }
        return min;
    }

    /** The walkable tile bordering the cluster that is closest (Manhattan) to the core. */
    private static Tile findAnchorTile(IntSeq cluster, int coreX, int coreY) {
        IntSet clusterSet = new IntSet();
        for (int i = 0; i < cluster.size; i++) {
            clusterSet.add(cluster.items[i]);
        }

        Tile best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int i = 0; i < cluster.size; i++) {
            int x = Point2.x(cluster.items[i]);
            int y = Point2.y(cluster.items[i]);

            for (int dir = 0; dir < 4; dir++) {
                int nx = x + DX[dir];
                int ny = y + DY[dir];
                Tile neighbor = world.tile(nx, ny);
                if (neighbor == null || clusterSet.contains(neighbor.pos())) continue;
                if (!isWalkable(neighbor)) continue;

                int dist = Math.abs(nx - coreX) + Math.abs(ny - coreY);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = neighbor;
                }
            }
        }

        return best;
    }

    /** Breadth-first search from {@code anchor} to any tile orthogonally touching {@code core}. */
    private static IntSeq findPathToCore(Tile anchor, Building core, int w, int h) {
        boolean[] seen = new boolean[w * h];
        int[] prevIdx = new int[w * h];
        Arrays.fill(prevIdx, -1);

        int startIdx = anchor.y * w + anchor.x;
        seen[startIdx] = true;

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(startIdx);

        int goalIdx = -1;
        int steps = 0;

        while (!queue.isEmpty() && steps < MAX_PATH_SEARCH_TILES) {
            int idx = queue.poll();
            steps++;

            int x = idx % w;
            int y = idx / w;

            if (touchesCore(x, y, core)) {
                goalIdx = idx;
                break;
            }

            for (int dir = 0; dir < 4; dir++) {
                int nx = x + DX[dir];
                int ny = y + DY[dir];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;

                int nIdx = ny * w + nx;
                if (seen[nIdx]) continue;

                Tile neighbor = world.tile(nx, ny);
                if (!isWalkable(neighbor)) continue;

                seen[nIdx] = true;
                prevIdx[nIdx] = idx;
                queue.add(nIdx);
            }
        }

        if (goalIdx == -1) return null;

        IntSeq path = new IntSeq();
        int cur = goalIdx;
        while (cur != -1) {
            path.add(Point2.pack(cur % w, cur / w));
            cur = prevIdx[cur];
        }
        // Built backwards (goal -> anchor); flip in place so index 0 is the
        // anchor tile, matching what queueConveyorPlan() expects.
        for (int a = 0, b = path.size - 1; a < b; a++, b--) {
            int tmp = path.items[a];
            path.items[a] = path.items[b];
            path.items[b] = tmp;
        }
        return path;
    }

    /** Ground the belt can occupy: not solid, not liquid, and either empty or already a conveyor. */
    private static boolean isWalkable(Tile t) {
        if (t == null) return false;
        if (t.floor().isLiquid) return false;
        if (t.solid()) return false;
        Block block = t.block();
        return block == Blocks.air || block instanceof Conveyor;
    }

    private static boolean touchesCore(int x, int y, Building core) {
        for (int dir = 0; dir < 4; dir++) {
            Tile neighbor = world.tile(x + DX[dir], y + DY[dir]);
            if (neighbor != null && neighbor.build == core) return true;
        }
        return false;
    }

    private static void queueConveyorPlan(IntSeq path, Building core) {
        Unit unit = Vars.player.unit();
        if (unit == null) return;

        for (int i = 0; i < path.size; i++) {
            int x = Point2.x(path.items[i]);
            int y = Point2.y(path.items[i]);

            Tile tile = world.tile(x, y);
            if (tile == null || tile.block() instanceof Conveyor) continue; // already built, skip

            int rotation;
            if (i < path.size - 1) {
                int nx = Point2.x(path.items[i + 1]);
                int ny = Point2.y(path.items[i + 1]);
                rotation = rotationFor(nx - x, ny - y);
            } else {
                rotation = rotationTowardCore(x, y, core);
            }

            unit.addBuild(new BuildPlan(x, y, rotation, Blocks.conveyor));
        }
    }

    private static int rotationFor(int dx, int dy) {
        for (int dir = 0; dir < 4; dir++) {
            if (DX[dir] == dx && DY[dir] == dy) return dir;
        }
        return 0;
    }

    private static int rotationTowardCore(int x, int y, Building core) {
        for (int dir = 0; dir < 4; dir++) {
            Tile neighbor = world.tile(x + DX[dir], y + DY[dir]);
            if (neighbor != null && neighbor.build == core) return dir;
        }
        return 0;
    }
}

