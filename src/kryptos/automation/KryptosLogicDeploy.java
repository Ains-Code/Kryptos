package kryptos.automation;

import arc.Events;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.world.blocks.logic.LogicBlock;

/**
 * Physically deploys the Kryptos logic-driven automation setup: a shared
 * Memory Cell plus two Micro Processors (Smart Drill, Conveyor Maker),
 * pre-loaded with their mlog programs and linked to each other and the
 * core. This mirrors what you'd otherwise do by hand in the logic editor
 * (paste code, drag links) -- see smart-drill.mlog / conveyor-maker.mlog
 * for the source of truth these strings are copied from.
 *
 * This is intentionally separate from KryptosAutoConveyor / KryptosSmartDrill:
 * those are direct Java-side automation. This module instead builds real
 * in-world units + logic blocks that run the mlog VM, per the "gusto ilagay
 * mo yan sa java mismo" request -- the automation logic itself still lives
 * in mlog, Java is only responsible for building and wiring it up.
 *
 * Usage: call requestDeploy() once (e.g. from a button in
 * KryptosAutomationPanel). It queues build plans on the player's unit for
 * the three blocks, then configures them with code + links automatically
 * once construction finishes.
 */
public final class KryptosLogicDeploy {

    private static boolean pending = false;
    private static int cellX, cellY, drillProcX, drillProcY, convProcX, convProcY;

    private KryptosLogicDeploy() {}

    public static void init() {
        Events.run(Trigger.update, KryptosLogicDeploy::update);
    }

    public static void requestDeploy() {
        if (pending) {
            Log.info("[Kryptos] Logic deploy already in progress.");
            return;
        }

        Unit unit = Vars.player.unit();
        Building core = Vars.player.team().core();
        if (unit == null || core == null) {
            Log.warn("[Kryptos] Cannot deploy logic units: no player unit or no core.");
            return;
        }

        int coreX = core.tile.x, coreY = core.tile.y;

        // Keep everything close together and close to the core -- microProcessor
        // link range is limited, and this avoids the placements colliding with
        // the core's own footprint.
        cellX = coreX + 4;
        cellY = coreY;
        drillProcX = coreX + 4;
        drillProcY = coreY + 2;
        convProcX = coreX + 4;
        convProcY = coreY + 4;

        unit.addBuild(new BuildPlan(cellX, cellY, 0, Blocks.memoryCell));
        unit.addBuild(new BuildPlan(drillProcX, drillProcY, 0, Blocks.microProcessor));
        unit.addBuild(new BuildPlan(convProcX, convProcY, 0, Blocks.microProcessor));

        pending = true;
        Log.info("[Kryptos] Queued Memory Cell + Smart Drill + Conveyor Maker processors near core.");
    }

    private static void update() {
        if (!pending) return;

        Tile cellTile = Vars.world.tile(cellX, cellY);
        Tile drillTile = Vars.world.tile(drillProcX, drillProcY);
        Tile convTile = Vars.world.tile(convProcX, convProcY);
        if (cellTile == null || drillTile == null || convTile == null) {
            pending = false;
            return;
        }

        boolean cellDone = cellTile.build != null && cellTile.build.block == Blocks.memoryCell;
        boolean drillDone = drillTile.build != null && drillTile.build.block == Blocks.microProcessor;
        boolean convDone = convTile.build != null && convTile.build.block == Blocks.microProcessor;
        if (!(cellDone && drillDone && convDone)) return; // still under construction, keep waiting

        Building core = Vars.player.team().core();
        if (core == null) {
            pending = false;
            return;
        }
        int coreX = core.tile.x, coreY = core.tile.y;

        // LogicLink x/y are offsets RELATIVE to the processor, not absolute
        // world coordinates -- matches the name each mlog script reads
        // ("cell1" and "core") in smart-drill.mlog / conveyor-maker.mlog.
        Seq<LogicBlock.LogicLink> drillLinks = new Seq<>();
        drillLinks.add(new LogicBlock.LogicLink(cellX - drillProcX, cellY - drillProcY, "cell1", true));
        drillLinks.add(new LogicBlock.LogicLink(coreX - drillProcX, coreY - drillProcY, "core", true));
        drillTile.build.configure(LogicBlock.compress(SMART_DRILL_CODE, drillLinks));

        Seq<LogicBlock.LogicLink> convLinks = new Seq<>();
        convLinks.add(new LogicBlock.LogicLink(cellX - convProcX, cellY - convProcY, "cell1", true));
        convLinks.add(new LogicBlock.LogicLink(coreX - convProcX, coreY - convProcY, "core", true));
        convTile.build.configure(LogicBlock.compress(CONVEYOR_MAKER_CODE, convLinks));

        Log.info("[Kryptos] Smart Drill + Conveyor Maker logic deployed and linked.");
        pending = false;
    }

    // Keep in sync with smart-drill.mlog -- copy-paste, don't hand-edit both.
    private static final String SMART_DRILL_CODE = """
        set QUEUE_CAP 16
        loop:
        ubind @mono
        sensor isDead @unit @dead
        jump loop equal isDead 1
        ulocate ore core true @copper oreX oreY found oreBuilding
        jump loop equal found 0
        ucontrol approach oreX oreY 3
        sensor ux @unit @x
        sensor uy @unit @y
        op sub ddx ux oreX
        op sub ddy uy oreY
        op len dist ddx ddy
        jump loop greaterThan dist 5
        ucontrol build oreX oreY @mechanical-drill 0 0 0
        wait 0.5
        read widx cell1 0
        op mod slot widx QUEUE_CAP
        op mul slotOff slot 2
        op add xSlot slotOff 2
        op add ySlot xSlot 1
        write oreX cell1 xSlot
        write oreY cell1 ySlot
        op add widx widx 1
        write widx cell1 0
        jump loop always
        """;

    // Keep in sync with conveyor-maker.mlog -- copy-paste, don't hand-edit both.
    private static final String CONVEYOR_MAKER_CODE = """
        set QUEUE_CAP 16
        loop:
        ubind @poly
        sensor isDead @unit @dead
        jump loop equal isDead 1
        read widx cell1 0
        read ridx cell1 1
        jump loop equal widx ridx
        op mod slot ridx QUEUE_CAP
        op mul slotOff slot 2
        op add xSlot slotOff 2
        op add ySlot xSlot 1
        read dx cell1 xSlot
        read dy cell1 ySlot
        sensor cx core @x
        sensor cy core @y
        set curX dx
        set curY dy
        legX:
        op sub diffX cx curX
        jump legXdone equal diffX 0
        jump legXpos greaterThan diffX 0
        set stepX -1
        set rotX 2
        jump legXgo always
        legXpos:
        set stepX 1
        set rotX 0
        legXgo:
        op add nextX curX stepX
        ucontrol approach nextX curY 3
        sensor ux @unit @x
        sensor uy @unit @y
        op sub adx ux nextX
        op sub ady uy curY
        op len distX adx ady
        jump legX greaterThan distX 5
        ucontrol build curX curY @conveyor rotX 0
        set curX nextX
        jump legX always
        legXdone:
        legY:
        op sub diffY cy curY
        jump legYdone equal diffY 0
        jump legYpos greaterThan diffY 0
        set stepY -1
        set rotY 3
        jump legYgo always
        legYpos:
        set stepY 1
        set rotY 1
        legYgo:
        op add nextY curY stepY
        ucontrol approach curX nextY 3
        sensor ux2 @unit @x
        sensor uy2 @unit @y
        op sub bdx ux2 curX
        op sub bdy uy2 nextY
        op len distY bdx bdy
        jump legY greaterThan distY 5
        ucontrol build curX curY @conveyor rotY 0
        set curY nextY
        jump legY always
        legYdone:
        op add ridx ridx 1
        write ridx cell1 1
        jump loop always
        """;
}
