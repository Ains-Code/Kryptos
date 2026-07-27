package kryptos.automation;

import arc.Events;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import kryptos.content.KryptosUnits;
import kryptos.ui.KryptosAutomationPanel;
import kryptos.ui.KryptosHud;
import kryptos.world.KryptosDefenseGeometry;
import kryptos.world.KryptosDefenseGeometry.Style;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.environment.OreBlock;

import static mindustry.Vars.spawner;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;

/**
 * Autonomously builds a 2-layer defensive line just outside each ground
 * spawn point's "drop zone" -- the circle drawn around every spawn on the
 * map/minimap. Outer layer (closer to the spawn, hit first) is pure wall;
 * inner layer (one tile further toward the core) is turret+wall, physically
 * screened by the outer layer's wall body -- Mindustry turrets fire past a
 * friendly wall in front of them just fine, so this keeps the turrets from
 * taking direct hits while the wall in front absorbs them instead.
 *
 * IMPORTANT, verified against Mindustry's own WaveSpawner source: that circle
 * is not just a visual indicator. Every time a wave spawns, the game deals
 * ~infinite damage (a "shockwave") to everything inside rules.dropZoneRadius
 * of the spawn point specifically to clear it -- this is the game's built-in
 * counter to "wall the spawn shut" strategies. Building INSIDE that circle
 * means it gets demolished the moment the next wave spawns. So this module
 * deliberately builds just OUTSIDE it instead, on the side facing the
 * player's core, forming a line the marching enemies have to pass through on
 * their way in -- a normal defensive kill-line, not a spawn wall.
 *
 * Build order matters: the outer wall row is queued (and therefore built by
 * the drone) entirely before the inner turret row. If a wave interrupts
 * construction partway through, the shield is already up rather than a
 * half-built, exposed turret line with nothing in front of it yet.
 *
 * Line width, turret density, and per-cycle build budget are no longer one
 * fixed shape for every spawn -- each spawn's approach terrain is classified
 * by {@link kryptos.world.KryptosDefenseGeometry}, which traces the same
 * ground flowfield the enemy AI itself follows to tell whether that spawn's
 * path to the core is a natural chokepoint (keep the line narrow and dense)
 * or open ground (widen it and add more turrets, since there's no terrain
 * doing the concentrating for us). See {@link #lineHalfWidthFor},
 * {@link #turretEveryFor}, and {@link #maxBuildsFor} for the per-style
 * values.
 *
 * One drone per spawn point, not one shared drone visiting every spawn in
 * turn: {@link #helperUnits} always has exactly as many drones as there are
 * currently-known spawn points (index i is dedicated to spawner.getSpawns()'s
 * i-th entry). 1 spawn on the map -> 1 drone; 2 spawns -> 2 drones, one each;
 * 3+ -> it keeps spawning one more per additional spawn point automatically.
 * Each still uses the same {@link KryptosDroneAI} + BuildPlan pattern as
 * everything else in this package.
 *
 * Unlike SmartDrill's ore deposits, a defense line is never "done" for good
 * -- enemies can destroy sections of it. So instead of claiming a spawn
 * point once and never looking again, every scan re-checks both layers'
 * intended tiles and only queues BuildPlans for whichever ones are currently
 * missing/destroyed. This doubles as auto-repair for free.
 */
public final class KryptosPerimeterDefense {

    public enum State { IDLE, SCANNING, BUILDING }
    private static State state = State.IDLE;

    public static State state() { return state; }

    private static final float SCAN_INTERVAL_TICKS = 60f * 10f;
    private static float lastScanTime = -SCAN_INTERVAL_TICKS;

    // Extra margin beyond rules.dropZoneRadius, in tiles, so the line sits
    // comfortably clear of the shockwave instead of right on its edge
    // (rounding/line-width could otherwise put a corner tile just inside it).
    private static final int SAFETY_MARGIN_TILES = 3;

    // Total tiles the defense line spans, centered on the spawn-to-core
    // axis, per classified terrain Style. CHOKEPOINT keeps the original
    // width -- enemies are naturally forced tight there, so a narrow, dense
    // line is enough. HYBRID and OPEN widen it to cover a broader approach
    // front on sectors where the terrain doesn't do that funneling for us.
    private static final int LINE_HALF_WIDTH_CHOKEPOINT = 4;
    private static final int LINE_HALF_WIDTH_HYBRID = 6;
    private static final int LINE_HALF_WIDTH_OPEN = 9;

    // Every Nth position is a turret instead of a wall. Open ground gets a
    // denser ratio -- there's no natural bottleneck concentrating enemies
    // onto a couple of turrets' worth of range, so more of the line needs
    // to be able to shoot at once.
    private static final int TURRET_EVERY_CHOKEPOINT = 3;
    private static final int TURRET_EVERY_HYBRID = 3;
    private static final int TURRET_EVERY_OPEN = 2;

    // Caps how many BuildPlans get queued PER SPAWN POINT in a single scan --
    // per-spawn now that each has its own dedicated drone, so one spawn's
    // line can't eat the whole cycle's budget and starve every other spawn's
    // drone out of getting any work queued this round.
    // OPEN sectors have a much longer line to fill than CHOKEPOINT's, so
    // they get a higher cap -- otherwise they'd take proportionally many
    // more scan cycles (10s each) to finish than a narrow line would.
    private static final int MAX_BUILDS_CHOKEPOINT = 8;
    private static final int MAX_BUILDS_HYBRID = 8;
    private static final int MAX_BUILDS_OPEN = 12;

    /** Half-width of the defense line for a given classified terrain style. UNKNOWN falls back to CHOKEPOINT -- the original, already-shipped shape -- rather than guessing wide. */
    private static int lineHalfWidthFor(Style style) {
        return switch (style) {
            case OPEN -> LINE_HALF_WIDTH_OPEN;
            case HYBRID -> LINE_HALF_WIDTH_HYBRID;
            case CHOKEPOINT, UNKNOWN -> LINE_HALF_WIDTH_CHOKEPOINT;
        };
    }

    /** Turret-to-wall ratio (1-in-N) for a given classified terrain style. */
    private static int turretEveryFor(Style style) {
        return switch (style) {
            case OPEN -> TURRET_EVERY_OPEN;
            case HYBRID -> TURRET_EVERY_HYBRID;
            case CHOKEPOINT, UNKNOWN -> TURRET_EVERY_CHOKEPOINT;
        };
    }

    /** Per-scan BuildPlan budget for a given classified terrain style. */
    private static int maxBuildsFor(Style style) {
        return switch (style) {
            case OPEN -> MAX_BUILDS_OPEN;
            case HYBRID -> MAX_BUILDS_HYBRID;
            case CHOKEPOINT, UNKNOWN -> MAX_BUILDS_CHOKEPOINT;
        };
    }

    // helperUnits.get(i) is dedicated to spawner.getSpawns().get(i). Resized
    // to match the current spawn count every scan (see ensureHelpers()).
    private static final Seq<Unit> helperUnits = new Seq<>();

    private KryptosPerimeterDefense() {}

    public static void init() {
        Events.on(WorldLoadEvent.class, e -> reset());
        Events.run(Trigger.update, KryptosPerimeterDefense::update);
    }

    public static void requestImmediateScan() {
        lastScanTime = -SCAN_INTERVAL_TICKS - 1f;
        ensureHelpers();
    }

    /** Grows/shrinks helperUnits to match the current spawn count, spawning new drones or reusing existing ones slot-by-slot. */
    private static void ensureHelpers() {
        if (Vars.player == null) return;
        if (spawner == null) return;

        int wanted = spawner.getSpawns().size;
        Team team = Vars.player.team();

        // setSize both grows (new slots default to null) and truncates
        // (extras just dropped) -- verified against Arc's Seq source.
        helperUnits.setSize(wanted);

        for (int i = 0; i < helperUnits.size; i++) {
            helperUnits.set(i, KryptosBuilderUnits.getOrSpawn(helperUnits.get(i), team, KryptosUnits.defenseBuilder));
        }
    }

    private static boolean anyHelperBuilding() {
        for (Unit u : helperUnits) {
            if (u != null && u.buildPlan() != null) return true;
        }
        return false;
    }

    private static void reset() {
        lastScanTime = -SCAN_INTERVAL_TICKS;
        helperUnits.clear();
        KryptosBuilderUnits.killAll();
    }

    private static void update() {
        if (!Vars.state.isGame()) return;
        if (!Vars.state.rules.waves) {
            // No wave spawns on this map/ruleset at all -- nothing to defend against.
            state = State.IDLE;
            return;
        }

        if (!KryptosHud.autoplay || !KryptosAutomationPanel.autoPerimeterDefense) {
            state = State.IDLE;
            return;
        }
        if (Vars.player == null) return;

        ensureHelpers();
        if (helperUnits.isEmpty()) {
            state = State.IDLE;
            return;
        }

        float now = Time.time;
        if (now - lastScanTime < SCAN_INTERVAL_TICKS) {
            state = anyHelperBuilding() ? State.BUILDING : State.IDLE;
            return;
        }
        lastScanTime = now;
        state = State.SCANNING;

        try {
            scanAndBuildDefenses();
        } catch (Throwable t) {
            Log.err("[Kryptos] PerimeterDefense scan failed, disabling module to avoid repeat crashes", t);
            KryptosAutomationPanel.autoPerimeterDefense = false;
            state = State.IDLE;
            return;
        }

        state = anyHelperBuilding() ? State.BUILDING : State.IDLE;
    }

    private static void scanAndBuildDefenses() {
        Team team = Vars.player.team();
        Building core = team.core();
        if (core == null) return;

        if (spawner == null || spawner.getSpawns().isEmpty()) return;

        Block turretType = bestUnlockedTurret(core);
        Block wallType = bestUnlockedWall(core);
        if (turretType == null && wallType == null) return;

        // Outer row (closer to the spawn, hit first) is pure wall -- a
        // shield with nothing valuable exposed on it. Inner row (one tile
        // further toward the core) is where the turrets actually go,
        // physically screened by the outer row's wall body sitting between
        // them and the approaching enemies. Mindustry turrets can still fire
        // past a friendly wall in front of them just fine.
        float outerRadiusTiles = Vars.state.rules.dropZoneRadius / tilesize + SAFETY_MARGIN_TILES;
        float innerRadiusTiles = outerRadiusTiles + 1f;

        int totalQueued = 0;
        Seq<Tile> spawns = spawner.getSpawns();

        for (int spawnIndex = 0; spawnIndex < spawns.size; spawnIndex++) {
            Tile spawn = spawns.get(spawnIndex);
            Unit drone = spawnIndex < helperUnits.size ? helperUnits.get(spawnIndex) : null;
            if (drone == null) continue; // ensureHelpers() couldn't spawn this slot (e.g. no core) -- skip for now, try again next scan

            float dx = core.tile.x - spawn.x;
            float dy = core.tile.y - spawn.y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < 1f) continue; // spawn is on top of the core somehow -- nothing sane to compute

            dx /= dist;
            dy /= dist;
            // perpendicular to the spawn->core direction
            float px = -dy;
            float py = dx;

            Style style;
            try {
                style = KryptosDefenseGeometry.classify(spawn, core.tile);
            } catch (Throwable t) {
                // Isolated on purpose: a bug in the newer geometry classifier
                // should never be able to take down the whole (already
                // proven) drone-building loop below it. Falls back to
                // CHOKEPOINT via the lookup helpers' UNKNOWN case, same as
                // before this feature existed.
                Log.err("[Kryptos] PerimeterDefense: geometry classification failed for spawn #" + spawnIndex
                    + ", falling back to CHOKEPOINT this scan", t);
                style = Style.UNKNOWN;
            }
            int halfWidth = lineHalfWidthFor(style);
            int turretEvery = turretEveryFor(style);
            int maxBuildsThisSpawn = maxBuildsFor(style);

            int queuedForThisSpawn = 0;

            // Pass 1: outer wall row. Queued (and therefore built by the
            // drone) before anything in pass 2, so a wave that interrupts
            // construction mid-way still finds a completed shield rather
            // than a half-built, unprotected turret line.
            if (wallType != null) {
                float centerX = spawn.x + dx * outerRadiusTiles;
                float centerY = spawn.y + dy * outerRadiusTiles;

                for (int offset = -halfWidth; offset <= halfWidth; offset++) {
                    if (queuedForThisSpawn >= maxBuildsThisSpawn) break;

                    int lx = Math.round(centerX + px * offset);
                    int ly = Math.round(centerY + py * offset);

                    Tile tile = world.tile(lx, ly);
                    if (tile == null) continue;
                    if (tile.block() == wallType) continue; // already built
                    if (!isBuildable(tile)) continue;

                    drone.addBuild(new BuildPlan(lx, ly, 0, wallType));
                    queuedForThisSpawn++;
                }
            }

            // Pass 2: inner turret+wall row, screened by pass 1's wall.
            {
                float centerX = spawn.x + dx * innerRadiusTiles;
                float centerY = spawn.y + dy * innerRadiusTiles;

                for (int offset = -halfWidth; offset <= halfWidth; offset++) {
                    if (queuedForThisSpawn >= maxBuildsThisSpawn) break;

                    int lx = Math.round(centerX + px * offset);
                    int ly = Math.round(centerY + py * offset);

                    boolean wantTurret = (offset % turretEvery == 0) && turretType != null;
                    Block wanted = wantTurret ? turretType : wallType;
                    if (wanted == null) continue;

                    Tile tile = world.tile(lx, ly);
                    if (tile == null) continue;
                    if (tile.block() == wanted) continue;
                    if (!isBuildable(tile)) continue;

                    drone.addBuild(new BuildPlan(lx, ly, 0, wanted));
                    queuedForThisSpawn++;
                }
            }

            totalQueued += queuedForThisSpawn;

            if (queuedForThisSpawn > 0) {
                Log.info("[Kryptos] PerimeterDefense: spawn #@ classified @ (half-width @, turret-every @), queued @ placement(s)",
                    spawnIndex, style, halfWidth, turretEvery, queuedForThisSpawn);
            }
        }

        if (totalQueued > 0) {
            Log.info("[Kryptos] PerimeterDefense: queued @ turret/wall placements across @ spawn point(s), @ drone(s) active",
                totalQueued, spawns.size, helperUnits.size);
        }
    }

    private static boolean isBuildable(Tile tile) {
        if (tile.block() != Blocks.air) return false;
        if (tile.floor().isLiquid) return false;
        if (tile.overlay() instanceof OreBlock) return false; // leave ore for SmartDrill
        return true;
    }

    /** Highest-range unlocked+affordable turret, or null if none qualify. */
    private static Block bestUnlockedTurret(Building core) {
        Block best = null;
        float bestRange = -1f;

        for (Block block : Vars.content.blocks()) {
            if (!(block instanceof BaseTurret turret)) continue;
            if (!turret.unlockedNow() && !Vars.state.rules.infiniteResources) continue;
            if (!Vars.state.rules.infiniteResources && !canAfford(core, turret)) continue;
            if (turret.range > bestRange) {
                bestRange = turret.range;
                best = turret;
            }
        }
        return best;
    }

    /** Highest-health unlocked+affordable wall, or null if none qualify. */
    private static Block bestUnlockedWall(Building core) {
        Block best = null;
        float bestHealth = -1f;

        for (Block block : Vars.content.blocks()) {
            if (!(block instanceof Wall wall)) continue;
            if (wall.size != 1) continue; // keep the line single-tile-wide and easy to reason about
            if (!wall.unlockedNow() && !Vars.state.rules.infiniteResources) continue;
            if (!Vars.state.rules.infiniteResources && !canAfford(core, wall)) continue;
            if (wall.health > bestHealth) {
                bestHealth = wall.health;
                best = wall;
            }
        }
        return best;
    }

    private static boolean canAfford(Building core, Block block) {
        for (ItemStack stack : block.requirements) {
            if (core.items.get(stack.item) < stack.amount) return false;
        }
        return true;
    }
}
