package kryptos.automation;

import arc.Events;
import arc.math.geom.Point2;
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
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.environment.OreBlock;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.Junction;
import mindustry.world.blocks.distribution.Router;
import mindustry.world.blocks.distribution.MassDriver;

import java.util.ArrayDeque;
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

    // The drone that actually flies out and builds -- spawned the moment
    // Smart Drill is switched on (see requestImmediateScan()), reused for as
    // long as it's alive. Separate from KryptosAutoConveyor's own drone.
    // See KryptosBuilderUnits.getOrSpawn().
    private static Unit helperUnit;

    private KryptosSmartDrill() {}

    public static void init() {
        Events.on(WorldLoadEvent.class, e -> reset());
        Events.run(Trigger.update, KryptosSmartDrill::update);
    }

    public static void requestImmediateScan() {
        Log.info("[Kryptos] SmartDrill: requestImmediateScan() called.");
        lastScanTime = -SCAN_INTERVAL_TICKS - 1f;
        ensureHelper();
        Log.info("[Kryptos] SmartDrill: helperUnit after ensureHelper() = @", helperUnit);
    }

    private static void ensureHelper() {
        if (Vars.player == null) return;
        helperUnit = KryptosBuilderUnits.getOrSpawn(helperUnit, Vars.player.team());
    }

    private static void reset() {
        lastScanTime = -SCAN_INTERVAL_TICKS;
        helperUnit = null;
        // Kills any drone left over from a previous session/save that our
        // static reference above doesn't know about -- see
        // KryptosBuilderUnits.killAll() for why this matters. Shared with
        // KryptosAutoConveyor's reset(), so this may run twice per load,
        // which is harmless (killing an already-dead unit is a no-op).
        KryptosBuilderUnits.killAll();
        // Shared with KryptosAutoConveyor; see its reset() for why clearing
        // here too is safe.
        KryptosOreRegistry.reset();
    }

    private static float lastGateLogTime = -1e9f;

    private static void update() {
        if (!Vars.state.isGame()) return;

        // Diagnostic: prints the live state of both gate flags every ~5s
        // regardless of whether the toggle UI is actually wired up right.
        // If this never shows autoSmartDrill=true after toggling it on in
        // the panel, the break is in the UI callback, not in this class.
        if (Time.time - lastGateLogTime > 300f) {
            lastGateLogTime = Time.time;
            Log.info("[Kryptos] SmartDrill gate check: autoplay=@ autoSmartDrill=@ helperUnit=@",
                KryptosHud.autoplay, KryptosAutomationPanel.autoSmartDrill, helperUnit);
        }

        if (!KryptosHud.autoplay || !KryptosAutomationPanel.autoSmartDrill) return;
        if (Vars.player == null) return;

        ensureHelper();
        if (helperUnit == null) return;

        float now = Time.time;
        if (now - lastScanTime < SCAN_INTERVAL_TICKS) return;
        lastScanTime = now;

        try {
            scanAndManageDrills();
        } catch (Throwable t) {
            Log.err("[Kryptos] SmartDrill scan failed, disabling module to avoid repeat crashes", t);
            KryptosAutomationPanel.autoSmartDrill = false;
        }
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
                if (KryptosOreRegistry.isClaimed(key)) continue;

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
                // Note: not claiming in KryptosOreRegistry here -- only deposits actually
                // attempted below (within the per-cycle cap) get claimed, so any deposit
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
                KryptosOreRegistry.claim(dep.key);
                DrillPlan plan = createDrillPlan(dep, core);
                if (plan != null) plans.add(plan);
            }
        }

        // Runs regardless of whether there are new deposits to build --
        // this handles drills that already exist on the field, which is a
        // completely separate job from claiming new deposits below. Before
        // this fix, an empty `plans` (e.g. because AutoConveyor already
        // claimed every nearby deposit through the shared
        // KryptosOreRegistry) caused an early return that skipped this
        // entirely -- the module would then have nothing to do at all and
        // just sit idle, which is the "Smart Drill drone isn't moving" bug.
        manageExistingDrills(core, depositsByItem);

        if (plans.isEmpty()) return;

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

    private static DrillPlan createDrillPlan(OreDeposit deposit, Building core) {
        int coreX = core.tile.x;
        int coreY = core.tile.y;

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

        IntSeq path = findPathAStar(bestConveyorX, bestConveyorY, core);
        if (path == null || path.size == 0 || path.size > MAX_PATH_LENGTH) return null;

        return new DrillPlan(
            bestDrillX, bestDrillY,
            bestConveyorX, bestConveyorY,
            bestDrill, deposit.item,
            path, deposit.key
        );
    }

    private static Drill findBestDrillForItem(Item item) {
        Drill existing = KryptosFieldTier.matchExistingDrill(Vars.player.team(), item);
        if (existing != null) return existing;

        Seq<Block> blocks = Vars.content.blocks();
        Seq<Drill> candidates = new Seq<>();

        for (Block block : blocks) {
            if (!(block instanceof Drill)) continue;
            Drill drill = (Drill) block;
            if (!drill.unlockedNow() && !Vars.state.rules.infiniteResources) continue;
            if (drill.drillTime <= 0) continue;
            candidates.add(drill);
        }

        if (candidates.isEmpty()) return findAnyDrill();
        if (Vars.state.rules.infiniteResources) {
            candidates.sort((a, b) -> Integer.compare(b.tier, a.tier));
            return candidates.first();
        }

        // Highest tier first, then walk down until we find one the core can
        // actually afford right now -- unlocked doesn't mean buildable, and
        // queuing a plan for materials we don't have yet just leaves it
        // sitting unbuilt. Falls back to the lowest tier (usually the free
        // Mechanical Drill) if nothing pricier is affordable yet.
        candidates.sort((a, b) -> Integer.compare(b.tier, a.tier));

        Building core = Vars.player.team().core();
        if (core != null) {
            for (Drill drill : candidates) {
                if (canAfford(core, drill)) return drill;
            }
        }

        return candidates.peek();
    }

    private static boolean canAfford(Building core, Block block) {
        for (ItemStack stack : block.requirements) {
            if (core.items.get(stack.item) < stack.amount) return false;
        }
        return true;
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

    private static IntSeq findPathAStar(int startX, int startY, Building core) {
        int w = world.width();
        int h = world.height();
        int coreX = core.tile.x;
        int coreY = core.tile.y;
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

    private static boolean touchesCore(int x, int y, Building core) {
        for (int dir = 0; dir < 4; dir++) {
            Tile n = world.tile(x + DX4[dir], y + DY4[dir]);
            if (n != null && n.build == core) return true;
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

            // Only reconsider drills that are running but yielding nothing.
            // This previously had no effect at all (logic below was fully
            // commented out) -- an idle drill just sat there forever.
            if (!drill.enabled || drill.items.total() > 0) continue;

            Object config = drill.config();
            if (!(config instanceof Item)) continue; // not a re-targetable drill

            Item currentOre = (Item) config;
            boolean stillHasDeposit = depositsByItem.containsKey(currentOre);
            if (stillHasDeposit) continue;

            Item bestOre = findBestOreToMine(core, depositsByItem);
            if (bestOre != null && bestOre != currentOre) {
                drill.configure(bestOre);
                Log.info("[Kryptos] SmartDrill: switched idle drill at @,@ from @ to @",
                    drill.tile.x, drill.tile.y, currentOre.name, bestOre.name);
            }
        }
    }

    // Prefers items the core is SHORT on, not the ones it already has
    // plenty of -- reassigning an idle drill to double down on an
    // already-abundant resource wastes the reassignment.
    private static Item findBestOreToMine(Building core, ObjectMap<Item, Seq<OreDeposit>> depositsByItem) {
        Team team = Vars.player.team();
        Building coreBuild = team.core();
        Item bestItem = null;
        int lowestAmount = Integer.MAX_VALUE;

        for (Item item : depositsByItem.keys()) {
            int amount = coreBuild.items.get(item);
            if (amount < lowestAmount) {
                lowestAmount = amount;
                bestItem = item;
            }
        }

        return bestItem;
    }

    private static void executePlans(Seq<DrillPlan> plans, Building core) {
        Unit unit = helperUnit;
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
        return Vars.player != null && Vars.player.team().core().items.get(Items.titanium) > 50;
    }

    private static int rotationFor(int dx, int dy) {
        for (int dir = 0; dir < 4; dir++) {
            if (DX4[dir] == dx && DY4[dir] == dy) return dir;
        }
        return 0;
    }

    private static int rotationTowardCore(int x, int y, int coreX, int coreY) {
        for (int dir = 0; dir < 4; dir++) {
            int nx = x + DX4[dir];
            int ny = y + DY4[dir];
            if (nx == coreX && ny == coreY) return dir;
        }
        return 0;
    }

    private static Item getItemFromOre(OreBlock ore) {
        if (ore == Blocks.oreCopper) return Items.copper;
        if (ore == Blocks.oreLead) return Items.lead;
        if (ore == Blocks.oreCoal) return Items.coal;
        if (ore == Blocks.oreTitanium) return Items.titanium;
        if (ore == Blocks.oreThorium) return Items.thorium;
        if (ore == Blocks.oreScrap) return Items.scrap;
        if (ore == KryptosBlocks.oreCustom) return KryptosItems.customOre;
        return null;
    }

    private static class OreDeposit {
        final int key;
        final IntSeq cluster;
        final Item item;
        final int centerX, centerY;
        final int coreDist;

        OreDeposit(int key, IntSeq cluster, Item item, int cx, int cy, int dist) {
            this.key = key;
            this.cluster = cluster;
            this.item = item;
            this.centerX = cx;
            this.centerY = cy;
            this.coreDist = dist;
        }
    }

    private static class DrillPlan {
        final int drillX, drillY;
        final int conveyorX, conveyorY;
        final Drill drillType;
        final Item item;
        final IntSeq path;
        final int depositKey;
        final int coveredOre;

        DrillPlan(int dx, int dy, int cx, int cy, Drill drill, Item item, IntSeq path, int key) {
            this.drillX = dx;
            this.drillY = dy;
            this.conveyorX = cx;
            this.conveyorY = cy;
            this.drillType = drill;
            this.item = item;
            this.path = path;
            this.depositKey = key;
            this.coveredOre = countOreCovered(dx, dy, drill.size, item);
        }
    }

    private static class Node {
        final int idx;
        final float f;
        Node(int idx, float f) { this.idx = idx; this.f = f; }
    }
}
