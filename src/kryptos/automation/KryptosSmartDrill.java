package kryptos.automation;

import arc.Events;
import arc.math.geom.Point2;
import arc.struct.IntIntMap;
import arc.struct.IntSeq;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import kryptos.ui.KryptosAutomationPanel;
import kryptos.ui.KryptosHud;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import kryptos.content.KryptosBlocks;
import kryptos.content.KryptosItems;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
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

import static mindustry.Vars.world;

public final class KryptosSmartDrill {

    private static final float SCAN_INTERVAL_TICKS = 60f * 5f;
    private static final int MAX_DRILLS_PER_CYCLE = 4;
    private static final int MAX_PATH_ATTEMPTS_PER_CYCLE = 8;
    private static final int MAX_PATH_SEARCH_TILES = 15000;
    private static final int MAX_PATH_LENGTH = 180;

    private static final int[] DX4 = {1, 0, -1, 0};
    private static final int[] DY4 = {0, 1, 0, -1};

    private static float lastScanTime = -SCAN_INTERVAL_TICKS;
    private static final IntIntMap oreClusterKey = new IntIntMap();

    private KryptosSmartDrill() {}

    public static void init() {
        Events.on(WorldLoadEvent.class, e -> reset());
        Events.run(Trigger.update, KryptosSmartDrill::update);
    }

    public static void requestImmediateScan() {
        lastScanTime = -SCAN_INTERVAL_TICKS - 1f;
    }

    private static void reset() {
        oreClusterKey.clear();
        lastScanTime = -SCAN_INTERVAL_TICKS;
    }

    private static void update() {
        if (!Vars.state.isGame()) return;
        if (!KryptosHud.autoplay || !KryptosAutomationPanel.autoSmartDrill) return;
        if (Vars.player == null || Vars.player.unit() == null) return;

        float now = Time.time;
        if (now - lastScanTime < SCAN_INTERVAL_TICKS) return;
        lastScanTime = now;

        scanAndManageDrills();
    }

    private static void scanAndManageDrills() {
        Team team = Vars.player.team();
        Building core = team.core();
        if (core == null) return;

        int coreX = core.tile.x;
        int coreY = core.tile.y;
        int w = world.width();
        int h = world.height();

        ObjectMap<Item, Seq<OreDeposit>> depositsByItem = new ObjectMap<>();
        boolean[] seen = new boolean[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (seen[idx]) continue;

                Tile tile = world.tile(x, y);
                if (tile == null) {
                    seen[idx] = true;
                    continue;
                }

                Block overlay = tile.overlay();
                if (!(overlay instanceof OreBlock)) {
                    seen[idx] = true;
                    continue;
                }

                OreBlock oreBlock = (OreBlock) overlay;
                Item item = getItemFromOre(oreBlock);
                if (item == null) {
                    seen[idx] = true;
                    continue;
                }

                IntSeq cluster = floodFillCluster(tile, oreBlock, seen, w, h);
                if (cluster.size < 2) continue;

                int key = clusterKey(cluster);
                if (oreClusterKey.containsKey(key)) continue;

                int bestDist = Integer.MAX_VALUE;
                int bestX = -1, bestY = -1;
                for (int i = 0; i < cluster.size; i++) {
                    int cx = Point2.x(cluster.items[i]);
                    int cy = Point2.y(cluster.items[i]);
                    int dist = Math.abs(cx - coreX) + Math.abs(cy - coreY);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestX = cx;
                        bestY = cy;
                    }
                }

                OreDeposit deposit = new OreDeposit(key, cluster, item, bestX, bestY, bestDist);
                Seq<OreDeposit> list = depositsByItem.get(item);
                if (list == null) {
                    list = new Seq<>();
                    depositsByItem.put(item, list);
                }
                list.add(deposit);
                // Note: not marking oreClusterKey here anymore -- only deposits actually
                // attempted below (within the per-cycle cap) get marked, so any deposit
                // skipped this cycle due to the cap is retried on the next scan instead
                // of being permanently ignored.
            }
        }

        Seq<DrillPlan> plans = new Seq<>();
        int attemptsThisCycle = 0;

        outerItems:
        for (Item item : depositsByItem.keys()) {
            Seq<OreDeposit> deposits = depositsByItem.get(item);
            deposits.sort((a, b) -> Integer.compare(a.coreDist, b.coreDist));

            int drillsToBuild = Math.min(MAX_DRILLS_PER_CYCLE, deposits.size);
            for (int i = 0; i < drillsToBuild; i++) {
                if (attemptsThisCycle >= MAX_PATH_ATTEMPTS_PER_CYCLE) break outerItems;
                attemptsThisCycle++;

                OreDeposit dep = deposits.get(i);
                oreClusterKey.put(dep.key, 1);
                DrillPlan plan = createDrillPlan(dep, coreX, coreY);
                if (plan != null) plans.add(plan);
            }
        }

        if (plans.isEmpty()) return;

        manageExistingDrills(core, depositsByItem);
        executePlans(plans, core);
    }

    private static IntSeq floodFillCluster(Tile start, Block overlay, boolean[] seen, int w, int h) {
        IntSeq cluster = new IntSeq();
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        queue.add(start.pos());
        seen[start.y * w + start.x] = true;

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
                if (seen[nIdx]) continue;

                Tile neighbor = world.tile(nx, ny);
                if (neighbor == null || neighbor.overlay() != overlay) {
                    seen[nIdx] = true;
                    continue;
                }

                seen[nIdx] = true;
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

    private static DrillPlan createDrillPlan(OreDeposit deposit, int coreX, int coreY) {
        Drill bestDrill = findBestDrillForItem(deposit.item);
        if (bestDrill == null) return null;

        int drillSize = bestDrill.size;
        int half = drillSize / 2;

        int bestDrillX = -1, bestDrillY = -1, bestCovered = -1;
        int bestConveyorX = -1, bestConveyorY = -1;

        for (int i = 0; i < deposit.cluster.size; i++) {
            int cx = Point2.x(deposit.cluster.items[i]);
            int cy = Point2.y(deposit.cluster.items[i]);

            for (int dx = -half; dx <= half; dx++) {
                for (int dy = -half; dy <= half; dy++) {
                    int drillX = cx + dx;
                    int drillY = cy + dy;

                    if (!canPlaceDrill(drillX, drillY, drillSize, deposit.item)) continue;

                    int covered = countOreCovered(drillX, drillY, drillSize, deposit.item);
                    if (covered <= 0) continue;

                    Tile conveyorTile = findBestConveyorTile(drillX, drillY, drillSize, coreX, coreY);
                    if (conveyorTile == null) continue;

                    if (covered > bestCovered) {
                        bestCovered = covered;
                        bestDrillX = drillX;
                        bestDrillY = drillY;
                        bestConveyorX = conveyorTile.x;
                        bestConveyorY = conveyorTile.y;
                    }
                }
            }
        }

        if (bestDrillX == -1) return null;

        IntSeq path = findPathAStar(bestConveyorX, bestConveyorY, coreX, coreY);
        if (path == null || path.size == 0 || path.size > MAX_PATH_LENGTH) return null;

        return new DrillPlan(
            bestDrillX, bestDrillY,
            bestConveyorX, bestConveyorY,
            bestDrill, deposit.item,
            path, deposit.key
        );
    }

    private static Drill findBestDrillForItem(Item item) {
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

    private static boolean canPlaceDrill(int x, int y, int size, Item item) {
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

    private static int countOreCovered(int cx, int cy, int size, Item item) {
        int count = 0;
        int half = size / 2;
        int range = half + 1;

        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                if (dx * dx + dy * dy > range * range) continue;
                int x = cx + dx;
                int y = cy + dy;
                if (x < 0 || y < 0 || x >= world.width() || y >= world.height()) continue;
                Tile t = world.tile(x, y);
                if (t != null) {
                    Block overlay = t.overlay();
                    if (overlay instanceof OreBlock) {
                        OreBlock ore = (OreBlock) overlay;
                        Item oreItem = getItemFromOre(ore);
                        if (oreItem == item) count++;
                    }
                }
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

    private static IntSeq findPathAStar(int startX, int startY, int coreX, int coreY) {
        int w = world.width();
        int h = world.height();
        int startIdx = startY * w + startX;

        boolean[] closed = new boolean[w * h];
        int[] prev = new int[w * h];
        float[] gScore = new float[w * h];
        float[] fScore = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            gScore[i] = Float.MAX_VALUE;
            fScore[i] = Float.MAX_VALUE;
            prev[i] = -1;
        }

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

            if (touchesCore(x, y, coreX, coreY)) {
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

                float tentativeG = gScore[idx] + moveCost(t);

                if (tentativeG < gScore[nIdx]) {
                    prev[nIdx] = idx;
                    gScore[nIdx] = tentativeG;
                    fScore[nIdx] = tentativeG + heuristic(nx, ny, coreX, coreY);
                    open.add(new Node(nIdx, fScore[nIdx]));
                }
            }
        }

        if (goalIdx == -1) return null;

        return reconstructPath(prev, goalIdx, w);
    }

    private static float heuristic(int x, int y, int goalX, int goalY) {
        return Math.abs(x - goalX) + Math.abs(y - goalY);
    }

    private static float moveCost(Tile t) {
        Block b = t.block();
        if (b == Blocks.air) return 1f;
        if (b instanceof Conveyor) return 0.5f;
        if (b instanceof MassDriver) return 0.8f;
        if (b instanceof Junction) return 0.6f;
        if (b instanceof Router) return 0.6f;
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
        return b == Blocks.air
            || b instanceof Conveyor
            || b instanceof MassDriver
            || b instanceof Junction
            || b instanceof Router;
    }

    private static boolean touchesCore(int x, int y, int coreX, int coreY) {
        for (int dir = 0; dir < 4; dir++) {
            int nx = x + DX4[dir];
            int ny = y + DY4[dir];
            if (nx == coreX && ny == coreY) return true;
        }
        return false;
    }

    private static void manageExistingDrills(Building core, ObjectMap<Item, Seq<OreDeposit>> depositsByItem) {
        Team team = Vars.player.team();
        Seq<Building> drills = new Seq<>();
        Groups.build.each(b -> {
            if (b.team == team) drills.add(b);
        });

        for (Building drill : drills) {
            if (!(drill.block instanceof Drill)) continue;
            Drill d = (Drill) drill.block;

            if (drill.enabled && drill.items.total() == 0) {
                // Item currentOre = (Item) drill.config;
                // boolean hasDeposit = depositsByItem.containsKey(currentOre);

                // if (!hasDeposit) {
                //     Item bestOre = findBestOreToMine(core, depositsByItem);
                //     if (bestOre != null && bestOre != currentOre) {
                //         drill.configure(bestOre);
                //         Log.info("[Kryptos] SmartDrill: switched drill at @,@ to mine @", drill.tile.x, drill.tile.y, bestOre.name);
                //     }
                // }
            }
        }
    }

    private static Item findBestOreToMine(Building core, ObjectMap<Item, Seq<OreDeposit>> depositsByItem) {
        Team team = Vars.player.team();
        Building coreBuild = team.core();
        Item bestItem = null;
        int bestAmount = -1;

        for (Item item : depositsByItem.keys()) {
            int amount = coreBuild.items.get(item);
            if (amount > bestAmount) {
                bestAmount = amount;
                bestItem = item;
            }
        }

        return bestItem;
    }

    private static void executePlans(Seq<DrillPlan> plans, Building core) {
        Unit unit = Vars.player.unit();
        if (unit == null) return;

        for (DrillPlan plan : plans) {
            Seq<BuildPlan> buildPlans = new Seq<>();

            buildPlans.add(new BuildPlan(plan.drillX, plan.drillY, 0, plan.drillType));

            for (int i = 0; i < plan.path.size; i++) {
                int x = Point2.x(plan.path.items[i]);
                int y = Point2.y(plan.path.items[i]);
                Tile tile = world.tile(x, y);
                if (tile == null || tile.block() instanceof Conveyor) continue;

                int rotation;
                if (i < plan.path.size - 1) {
                    int nx = Point2.x(plan.path.items[i + 1]);
                    int ny = Point2.y(plan.path.items[i + 1]);
                    rotation = rotationFor(nx - x, ny - y);
                } else {
                    rotation = rotationTowardCore(x, y, core.tile.x, core.tile.y);
                }

                Block conveyorType = selectConveyorType(i, plan.path.size, tile);
                buildPlans.add(new BuildPlan(x, y, rotation, conveyorType));
            }

            for (BuildPlan bp : buildPlans) {
                unit.addBuild(bp);
            }

            Log.info("[Kryptos] SmartDrill: queued @ belts + drill for @ (@ tiles)",
                buildPlans.size - 1, plan.item.name, plan.coveredOre);
        }
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
        return Vars.player != null && Vars.player.team().core().items.get(Items.titanium) > 50;
    }

    private static int rotationFor(int dx, int dy) {
       
