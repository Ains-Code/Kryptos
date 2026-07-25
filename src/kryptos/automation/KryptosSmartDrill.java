package kryptos.automation;

import arc.Events;
import arc.math.geom.Point2;
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
import kryptos.content.KryptosBlocks;
import kryptos.content.KryptosItems;
import kryptos.content.KryptosUnits;
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
    // Blocks.itemBridge ("bridge-conveyor"): range = 4, no power required --
    // verified against Mindustry's own ItemBridge/Blocks source. Two bridges
    // must share the exact same row or column to link, distance 2..range
    // (1 would just be a normal adjacent tile, no bridge needed).
    private static final int BRIDGE_RANGE = 4;

    private static final int[] DX4 = {1, 0, -1, 0};
    private static final int[] DY4 = {0, 1, 0, -1};

    // Explicit state, updated only at the points below -- never set randomly,
    // never inferred. IDLE: toggle off, or toggle on with nothing queued and
    // no scan due. SCANNING: actively inside scanAndManageDrills() this tick.
    // BUILDING: toggle on, not scanning this tick, but the drone still has
    // queued build plans left to execute. Exposed read-only so UI/logging
    // can show what each agent is actually doing right now.
    public enum State { IDLE, SCANNING, BUILDING }
    private static State state = State.IDLE;

    public static State state() { return state; }

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
        helperUnit = KryptosBuilderUnits.getOrSpawn(helperUnit, Vars.player.team(), KryptosUnits.smartDrillBuilder);
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

        if (!KryptosHud.autoplay || !KryptosAutomationPanel.autoSmartDrill) {
            state = State.IDLE;
            return;
        }
        if (Vars.player == null) return;

        ensureHelper();
        if (helperUnit == null) {
            state = State.IDLE;
            return;
        }

        float now = Time.time;
        if (now - lastScanTime < SCAN_INTERVAL_TICKS) {
            state = helperUnit.buildPlan() != null ? State.BUILDING : State.IDLE;
            return;
        }
        lastScanTime = now;
        state = State.SCANNING;

        try {
            scanAndManageDrills();
        } catch (Throwable t) {
            Log.err("[Kryptos] SmartDrill scan failed, disabling module to avoid repeat crashes", t);
            KryptosAutomationPanel.autoSmartDrill = false;
            state = State.IDLE;
            return;
        }

        state = helperUnit.buildPlan() != null ? State.BUILDING : State.IDLE;
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

                OreDeposit dep = deposits.get(i);
                KryptosOreRegistry.claim(dep.key);
                // Tiles the whole cluster with as many drills as fit, instead of
                // stopping at a single "best" drill and abandoning the rest of the
                // ore -- claim above covers the entire deposit either way, so a
                // single-drill plan previously left the remainder permanently unmined.
                Seq<DrillPlan> depositPlans = tileDeposit(dep, core);
                plans.addAll(depositPlans);
                attemptsThisCycle += Math.max(1, depositPlans.size);
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

    // Cap on how many drills a single deposit can be tiled with in one cycle --
    // without this, a huge cluster would keep consuming the whole cycle's path
    // budget and starve every other deposit.
    private static final int MAX_DRILLS_PER_DEPOSIT = 6;

    // Tiles the deposit's whole bounding box with a repeating size-spaced grid
    // and keeps every cell that overlaps ore, instead of hill-climbing to a
    // single "best" position one drill at a time. This is the same idea as
    // Ains-Code/mod-mindustry's SmartDrillFeature.isDrillTile() -- a fixed
    // periodic stamp (there: hardcoded mod-6 for 2x2 drills specifically) --
    // generalized here to search the best grid phase for whatever drill size
    // KryptosFieldTier picked, so it works the same for a size-2 mechanical
    // drill or a size-4 laser drill without hardcoding either.
    private static Seq<DrillPlan> tileDeposit(OreDeposit deposit, Building core) {
        Seq<DrillPlan> result = new Seq<>();

        Drill bestDrill = findBestDrillForItem(deposit.item);
        if (bestDrill == null) return result;

        int size = bestDrill.size;
        int half = size / 2;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < deposit.cluster.size; i++) {
            int x = Point2.x(deposit.cluster.items[i]);
            int y = Point2.y(deposit.cluster.items[i]);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }

        // Try every phase of the grid (0..size-1 on each axis) and keep
        // whichever lines up with the most ore -- an odd-shaped vein tiles
        // more completely under one phase than another, and there's no way
        // to know which without checking.
        int bestOffX = 0, bestOffY = 0, bestScore = -1;
        for (int offX = 0; offX < size; offX++) {
            for (int offY = 0; offY < size; offY++) {
                int score = 0;
                for (int gx = minX - size + 1 + offX; gx <= maxX; gx += size) {
                    for (int gy = minY - size + 1 + offY; gy <= maxY; gy += size) {
                        score += countOreInFootprint(gx, gy, half, deposit.item);
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestOffX = offX;
                    bestOffY = offY;
                }
            }
        }

        IntSet reservedTiles = new IntSet();
        // Belt tiles queued by an earlier drill in THIS pass. A later drill's
        // path is allowed to stop here instead of running all the way to the
        // core -- Mindustry conveyors merge fine when a short belt feeds into
        // the side of an existing line, so once one drill has a route to the
        // core, its neighbors just need to reach THAT line, not repeat the
        // whole trip. Without this, a 6-drill cluster could queue 6 separate
        // full-length belt runs criss-crossing back to the core individually.
        IntSet trunkTiles = new IntSet();

        gridScan:
        for (int gx = minX - size + 1 + bestOffX; gx <= maxX; gx += size) {
            for (int gy = minY - size + 1 + bestOffY; gy <= maxY; gy += size) {
                if (result.size >= MAX_DRILLS_PER_DEPOSIT) break gridScan;

                if (countOreInFootprint(gx, gy, half, deposit.item) <= 0) continue;
                if (!canPlaceDrill(gx, gy, size, reservedTiles)) continue;

                Tile conveyorTile = findBestConveyorTile(gx, gy, size, core.tile.x, core.tile.y, reservedTiles);
                if (conveyorTile == null) continue;

                IntSeq path = findPathAStar(conveyorTile.x, conveyorTile.y, core, reservedTiles, trunkTiles);

                DrillPlan plan;
                if (path != null && path.size > 0 && path.size <= MAX_PATH_LENGTH) {
                    int lastX = Point2.x(path.items[path.size - 1]);
                    int lastY = Point2.y(path.items[path.size - 1]);
                    int exitRotation = rotationTowardGoal(lastX, lastY, core.tile.x, core.tile.y, trunkTiles);

                    plan = DrillPlan.viaPath(gx, gy, conveyorTile.x, conveyorTile.y, bestDrill, deposit.item, path, deposit.key, exitRotation);

                    for (int i = 0; i < path.size; i++) {
                        reservedTiles.add(path.items[i]);
                        trunkTiles.add(path.items[i]);
                    }
                } else {
                    // No ground route around the other drills already packed
                    // into this cluster (or it exceeded MAX_PATH_LENGTH) --
                    // before giving up on this drill entirely, try hopping
                    // straight over whatever's blocking it with an Item
                    // Bridge instead. This is what actually makes dense
                    // packing work: a bridge doesn't care what's sitting
                    // between its two ends, unlike a belt.
                    int[] bridgeFar = findBridgeHop(conveyorTile.x, conveyorTile.y, core, reservedTiles, trunkTiles);
                    if (bridgeFar == null) continue;

                    plan = DrillPlan.viaBridge(gx, gy, conveyorTile.x, conveyorTile.y, bridgeFar[0], bridgeFar[1], bestDrill, deposit.item, deposit.key);

                    reservedTiles.add(Point2.pack(conveyorTile.x, conveyorTile.y));
                    reservedTiles.add(Point2.pack(bridgeFar[0], bridgeFar[1]));
                    trunkTiles.add(Point2.pack(bridgeFar[0], bridgeFar[1]));
                }

                result.add(plan);

                for (int dx = -half; dx <= half; dx++) {
                    for (int dy = -half; dy <= half; dy++) {
                        reservedTiles.add(Point2.pack(gx + dx, gy + dy));
                    }
                }
            }
        }

        return result;
    }

    // Counts ore tiles of `item` strictly inside a drill's actual footprint
    // (not the wider "nearby" radius countOreCovered uses for hill-climbing)
    // -- for a fixed grid stamp we only care whether this exact cell has ore
    // under it, not whether ore is somewhere nearby.
    private static int countOreInFootprint(int cx, int cy, int half, Item item) {
        int count = 0;
        for (int dx = -half; dx <= half; dx++) {
            for (int dy = -half; dy <= half; dy++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= world.width() || y >= world.height()) continue;
                Tile t = world.tile(x, y);
                if (t == null) continue;
                Block overlay = t.overlay();
                if (overlay instanceof OreBlock) {
                    Item oreItem = getItemFromOre((OreBlock) overlay);
                    if (oreItem == item) count++;
                }
            }
        }
        return count;
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

    /** Checks a drill footprint is placeable, also rejecting tiles already reserved by a drill placed earlier in the same tiling pass (see tileDeposit). */
    private static boolean canPlaceDrill(int x, int y, int size, IntSet reservedTiles) {
        int half = size / 2;
        for (int dx = -half; dx <= half; dx++) {
            for (int dy = -half; dy <= half; dy++) {
                int tx = x + dx, ty = y + dy;
                if (reservedTiles.contains(Point2.pack(tx, ty))) return false;
                Tile t = world.tile(tx, ty);
                if (t == null) return false;
                if (t.block() != Blocks.air && !(t.block() instanceof OreBlock)) return false;
                if (t.floor().isLiquid) return false;
                if (t.build != null && !(t.build.block instanceof OreBlock)) return false;
            }
        }
        return true;
    }

    /** Counts nearby ore tiles of `item` within a drill-sized radius -- used for DrillPlan's logged coverage count. */
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

    // Scans every tile orthogonally touching the drill's footprint (the full
    // perimeter ring, corners excluded) instead of one fixed point per side.
    // The old version checked only the single tile centered on each side --
    // for a 1x1 drill that's the only option anyway, but for anything bigger
    // (or when tileDeposit has already packed a neighboring drill right up
    // against one side), that one exact point is very often the tile that's
    // blocked, even though the rest of that side is wide open. Scanning the
    // whole ring picks the closest walkable tile on ANY side instead of
    // giving up because of one specific blocked spot.
    private static Tile findBestConveyorTile(int drillX, int drillY, int drillSize, int coreX, int coreY, IntSet reservedTiles) {
        int half = drillSize / 2;
        int ring = half + 1;
        Tile best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int dx = -ring; dx <= ring; dx++) {
            for (int dy = -ring; dy <= ring; dy++) {
                boolean onXEdge = dx == -ring || dx == ring;
                boolean onYEdge = dy == -ring || dy == ring;
                if (onXEdge == onYEdge) continue; // skip corners (both/neither edge) -- keep only orthogonal-adjacent tiles

                int cx = drillX + dx, cy = drillY + dy;
                if (cx < 0 || cy < 0 || cx >= world.width() || cy >= world.height()) continue;
                if (reservedTiles.contains(Point2.pack(cx, cy))) continue;

                Tile t = world.tile(cx, cy);
                if (t == null || !isConveyorWalkable(t)) continue;

                int dist = Math.abs(cx - coreX) + Math.abs(cy - coreY);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = t;
                }
            }
        }

        return best;
    }

    // Looks straight out from (startX, startY) in each of the 4 cardinal
    // directions, 2..BRIDGE_RANGE tiles, for a tile that's (a) actually free
    // to build on and (b) adjacent to the core or an existing trunk tile --
    // i.e. a valid far end for an Item Bridge. Unlike findPathAStar, this
    // doesn't care what's sitting in between the two points (a bridge
    // legitimately skips over whatever's there, including other drills'
    // bodies), so it's specifically useful for a drill whose only exit tile
    // is boxed in on the ground by its own neighbors in the same cluster.
    private static int[] findBridgeHop(int startX, int startY, Building core, IntSet reservedTiles, IntSet trunkTiles) {
        for (int dir = 0; dir < 4; dir++) {
            for (int dist = 2; dist <= BRIDGE_RANGE; dist++) {
                int fx = startX + DX4[dir] * dist;
                int fy = startY + DY4[dir] * dist;
                if (fx < 0 || fy < 0 || fx >= world.width() || fy >= world.height()) break;

                if (reservedTiles.contains(Point2.pack(fx, fy))) continue;
                Tile t = world.tile(fx, fy);
                if (!isConveyorWalkable(t)) continue;

                if (touchesCore(fx, fy, core) || touchesTrunk(fx, fy, trunkTiles)) {
                    return new int[]{fx, fy};
                }
            }
        }
        return null;
    }

    private static IntSeq findPathAStar(int startX, int startY, Building core, IntSet reservedTiles, IntSet trunkTiles) {
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

            // Reaching the core is always a valid goal; reaching a belt tile
            // another drill in this same pass already queued is ALSO a valid
            // goal -- joining that line gets items to the core just as well,
            // usually for a fraction of the distance. heuristic() below still
            // only measures distance-to-core, so this doesn't perfectly bias
            // the search toward the nearest trunk tile, but since every trunk
            // tile is itself already on a shortest path to the core, a
            // straight-line search toward the core tends to run right past
            // (or very near) them anyway.
            if (touchesCore(x, y, core) || touchesTrunk(x, y, trunkTiles)) {
                goalIdx = idx;
                break;
            }

            for (int dir = 0; dir < 4; dir++) {
                int nx = x + DX4[dir];
                int ny = y + DY4[dir];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;

                int nIdx = ny * w + nx;
                if (closed[nIdx]) continue;
                // Blocks routing through a sibling drill's footprint or belt
                // path from the same tiling pass -- that tile is only empty
                // air in the CURRENT world state because nothing has been
                // built yet, not because it's actually free. Without this,
                // the second/third drill in a cluster could plan a belt
                // straight across the first drill's not-yet-built footprint;
                // the plan then gets silently dropped by KryptosDroneAI the
                // moment the drone reaches it and finds the tile occupied,
                // which is what read as "random" drone behavior.
                if (reservedTiles.contains(Point2.pack(nx, ny))) continue;

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

    private static boolean touchesTrunk(int x, int y, IntSet trunkTiles) {
        for (int dir = 0; dir < 4; dir++) {
            if (trunkTiles.contains(Point2.pack(x + DX4[dir], y + DY4[dir]))) return true;
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

            if (plan.usesBridge()) {
                // Near end carries the link as a relative Point2 offset (see
                // ItemBridge's Point2 config handler in the engine source) --
                // this is what makes it pre-linked the instant it's built,
                // no player drag-to-link step needed.
                Point2 offset = new Point2(plan.bridgeFarX - plan.conveyorX, plan.bridgeFarY - plan.conveyorY);
                buildPlans.add(new BuildPlan(plan.conveyorX, plan.conveyorY, 0, Blocks.itemBridge, offset));
                // Far end needs no config -- once it receives items it just
                // dumps them onward through its own open side (the trunk
                // belt, or straight into the core if it landed next to it),
                // exactly like any other building's default dump behavior.
                buildPlans.add(new BuildPlan(plan.bridgeFarX, plan.bridgeFarY, 0, Blocks.itemBridge));

                for (BuildPlan bp : buildPlans) {
                    unit.addBuild(bp);
                }

                Log.info("[Kryptos] SmartDrill: queued bridge + drill for @ (@ tiles)", plan.item.name, plan.coveredOre);
                continue;
            }

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
                    rotation = plan.exitRotation;
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

        // No belts on the field yet to match tier against -- pick the best
        // unlocked/affordable conveyor available (Plastanium, Armored, etc.),
        // not just a hardcoded titanium-or-basic choice.
        Block best = KryptosFieldTier.bestUnlockedConveyor(Vars.player.team());
        return best != null ? best : Blocks.conveyor;
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

    // Same as rotationTowardCore, but also checks for an adjacent trunk tile
    // (a belt already queued by an earlier drill in this tiling pass) --
    // whichever one findPathAStar actually stopped next to is the direction
    // the last belt in the path needs to face.
    private static int rotationTowardGoal(int x, int y, int coreX, int coreY, IntSet trunkTiles) {
        for (int dir = 0; dir < 4; dir++) {
            int nx = x + DX4[dir];
            int ny = y + DY4[dir];
            if (trunkTiles.contains(Point2.pack(nx, ny))) return dir;
        }
        return rotationTowardCore(x, y, coreX, coreY);
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
        final IntSeq path; // null when using a bridge instead -- see bridgeFarX/Y
        final int depositKey;
        final int coveredOre;
        // Rotation for the LAST belt tile in path, computed back in
        // tileDeposit() while trunkTiles is still in scope -- by the time
        // executePlans() runs, trunkTiles (which drills joined which belt)
        // no longer exists, so it can't be recomputed here. Unused when path
        // is null (bridges don't need a facing rotation -- see findBridgeHop).
        final int exitRotation;
        // Set only when this drill has no ground route to the core/trunk and
        // is instead reached via an Item Bridge hop from (conveyorX, conveyorY)
        // to (bridgeFarX, bridgeFarY). -1 when path is used instead.
        final int bridgeFarX, bridgeFarY;

        private DrillPlan(int dx, int dy, int cx, int cy, Drill drill, Item item, IntSeq path, int key,
                int exitRotation, int bridgeFarX, int bridgeFarY) {
            this.drillX = dx;
            this.drillY = dy;
            this.conveyorX = cx;
            this.conveyorY = cy;
            this.drillType = drill;
            this.item = item;
            this.path = path;
            this.depositKey = key;
            this.coveredOre = countOreCovered(dx, dy, drill.size, item);
            this.exitRotation = exitRotation;
            this.bridgeFarX = bridgeFarX;
            this.bridgeFarY = bridgeFarY;
        }

        static DrillPlan viaPath(int dx, int dy, int cx, int cy, Drill drill, Item item, IntSeq path, int key, int exitRotation) {
            return new DrillPlan(dx, dy, cx, cy, drill, item, path, key, exitRotation, -1, -1);
        }

        static DrillPlan viaBridge(int dx, int dy, int cx, int cy, int farX, int farY, Drill drill, Item item, int key) {
            return new DrillPlan(dx, dy, cx, cy, drill, item, null, key, 0, farX, farY);
        }

        boolean usesBridge() {
            return path == null;
        }
    }

    private static class Node {
        final int idx;
        final float f;
        Node(int idx, float f) { this.idx = idx; this.f = f; }
    }
}
