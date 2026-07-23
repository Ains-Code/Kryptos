package kryptos.automation;

import mindustry.entities.units.AIController;
import mindustry.entities.units.BuildPlan;
import mindustry.world.Build;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;

/**
 * Controller for the Kryptos helper drone (see {@link KryptosBuilderUnits}).
 *
 * Stock Mindustry builder units run {@code BuilderAI}, which -- whenever it
 * has nothing queued -- helps itself to the team's shared block-plan queue
 * and even copies whatever build plan the nearest other builder unit is
 * working on. That means the drone could end up constructing things nobody
 * told SmartDrill/AutoConveyor to build.
 *
 * This controller strips all of that out: the drone does ONLY what's
 * explicitly queued on it via {@code unit.addBuild(...)} by KryptosSmartDrill
 * or KryptosAutoConveyor. If nothing is queued, it just sits idle -- it never
 * scavenges the shared plan queue, never follows/mimics another builder, and
 * never wanders off on its own.
 */
public class KryptosDroneAI extends AIController {

    private static final float BUILD_RADIUS = 1500f;

    @Override
    public void updateMovement() {
        unit.updateBuilding = true;

        BuildPlan req = unit.buildPlan();
        if (req == null) return; // nothing queued by our automation -- stay put

        boolean valid =
            (req.tile() != null && req.tile().build instanceof ConstructBuild cons && cons.current == req.block) ||
            (req.breaking
                ? Build.validBreak(unit.team(), req.x, req.y)
                : Build.validPlace(req.block, unit.team(), req.x, req.y, req.rotation));

        if (!valid) {
            unit.plans.removeFirst();
            return;
        }

        float range = Math.min(unit.type.buildRange - unit.type.hitSize * 2f, BUILD_RADIUS);
        moveTo(req.tile(), range, 20f);
    }

    @Override
    public boolean shouldShoot() {
        return false;
    }
}

