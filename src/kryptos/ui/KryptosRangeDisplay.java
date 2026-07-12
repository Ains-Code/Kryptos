package kryptos.ui;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.world.Block;
import mindustry.world.blocks.defense.BuildTurret;
import mindustry.world.blocks.defense.MendProjector;
import mindustry.world.blocks.defense.OverdriveProjector;
import mindustry.world.blocks.defense.OverdriveProjector.OverdriveBuild;
import mindustry.world.blocks.defense.RegenProjector;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.defense.turrets.Turret.TurretBuild;
import mindustry.world.blocks.distribution.MassDriver;

import static mindustry.Vars.player;
import static mindustry.Vars.state;

/**
 * Draws range circles for placed turrets/defenses (as before), plus a live
 * preview circle that follows the cursor while a turret or other
 * range-having block is selected for placement, so the range is visible
 * before you commit to a spot.
 */
public class KryptosRangeDisplay {
    private static final float OPACITY = 0.35f;

    public static void init() {
        Events.run(Trigger.draw, KryptosRangeDisplay::draw);
    }

    private static void draw() {
        if (!KryptosHud.rangeDisplay || !state.isGame() || Vars.ui.hudfrag == null || !Vars.ui.hudfrag.shown) {
            return;
        }

        Draw.z(Layer.overlayUI);

        float cx = Core.camera.position.x;
        float cy = Core.camera.position.y;
        float cw = Core.camera.width;
        float ch = Core.camera.height;
        float margin = 2000f;

        Groups.unit.intersect(cx - cw / 2f - margin, cy - ch / 2f - margin, cw + margin * 2, ch + margin * 2,
                KryptosRangeDisplay::drawUnitRange);

        Vars.indexer.eachBlock(null, cx, cy, Math.max(cw, ch), b -> true, KryptosRangeDisplay::drawBuildingRange);

        drawPlacementPreview();

        Draw.reset();
    }

    private static void drawUnitRange(Unit unit) {
        if (!unit.isValid() || unit == player.unit()) {
            return;
        }
        if (unit.range() > 0) {
            drawCircle(unit.x, unit.y, unit.range(), unit.team.color);
        }
    }

    private static void drawBuildingRange(Building build) {
        if (!build.isValid()) {
            return;
        }

        boolean isTurret = build.block instanceof Turret;
        boolean canShoot = build instanceof TurretBuild bt
                && ((bt.canConsume() && bt.hasAmmo()) || (!build.enabled && bt.peekAmmo() != null));
        float range = rangeOf(build.block, build);
        boolean circle = !(build.block instanceof RegenProjector);

        if (range <= 0) {
            return;
        }

        Color color = (isTurret && !canShoot) ? Color.gray : build.team.color;
        if (circle) {
            drawCircle(build.x, build.y, range, color);
        } else {
            drawSquare(build.x, build.y, range, color);
        }
    }

    /** Range value for a placed building, using live ammo bonus when available. */
    private static float rangeOf(Block block, Building build) {
        if (block instanceof Turret turret) {
            float range = turret.range;
            if (build instanceof TurretBuild tb) {
                var ammo = tb.peekAmmo();
                if (ammo != null) {
                    range += ammo.rangeChange;
                }
            }
            return range;
        }
        return rangeOfPassive(block, build);
    }

    /** Range value from block stats alone, for placement preview (no live building yet). */
    private static float rangeOfPassive(Block block, Building build) {
        if (block instanceof OverdriveProjector od) {
            float boost = build instanceof OverdriveBuild ob ? ob.phaseHeat * od.phaseRangeBoost : 0f;
            return od.range + boost;
        }
        if (block instanceof MassDriver massDriver) {
            return massDriver.range;
        }
        if (block instanceof BuildTurret bt) {
            return bt.range;
        }
        if (block instanceof MendProjector mp) {
            return mp.range;
        }
        if (block instanceof RegenProjector rp) {
            return rp.range * Vars.tilesize;
        }
        return 0f;
    }

    /** Follows the placement cursor while a range-having block is selected to place. */
    private static void drawPlacementPreview() {
        Block block = Vars.control.input.block;
        if (block == null) {
            return;
        }

        float range = block instanceof Turret turret ? turret.range : rangeOfPassive(block, null);
        if (range <= 0) {
            return;
        }

        var worldPos = Core.input.mouseWorld();
        int tx = Vars.world.toTile(worldPos.x);
        int ty = Vars.world.toTile(worldPos.y);
        float x = tx * Vars.tilesize + (block.size % 2 == 0 ? 0f : Vars.tilesize / 2f);
        float y = ty * Vars.tilesize + (block.size % 2 == 0 ? 0f : Vars.tilesize / 2f);

        boolean circle = !(block instanceof RegenProjector);
        if (circle) {
            drawCircle(x, y, range, player.team().color);
        } else {
            drawSquare(x, y, range, player.team().color);
        }
    }

    private static void drawSquare(float x, float y, float range, Color color) {
        Tmp.c2.set(color).a(OPACITY);
        Lines.stroke(1f, Tmp.c2);
        Lines.rect(x - range / 2, y - range / 2, range, range);
        Draw.reset();
    }

    private static void drawCircle(float x, float y, float range, Color color) {
        Tmp.c2.set(color).a(OPACITY);
        Lines.stroke(1f, Tmp.c2);
        Lines.circle(x, y, range);
        Draw.reset();
    }
}
