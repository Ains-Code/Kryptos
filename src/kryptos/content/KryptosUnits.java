package kryptos.content;

import mindustry.ai.UnitCommand;
import mindustry.type.UnitType;

/**
 * Content-side unit definitions for Kryptos. Just one "builder drone" type
 * now -- {@link #defenseBuilder}, used by
 * {@link kryptos.automation.KryptosPerimeterDefense} (one spawned per
 * detected enemy spawn point, see KryptosPerimeterDefense's per-spawn drone
 * scaling).
 *
 * KryptosAutoConveyor has been removed, and KryptosSmartDrill no longer uses
 * a dedicated drone at all -- it now queues its BuildPlans onto whatever unit
 * the player is directly controlling instead (matching mod-mindustry's
 * SmartDrillFeature, which does the same via Vars.player.unit().addBuild()).
 * The old {@code builder} and {@code smartDrillBuilder} types those two used
 * are gone; their placeholder sprite files are left in sprites/units/ but are
 * no longer referenced by any content.
 */
public class KryptosUnits {
    public static UnitType defenseBuilder;

    public static void load() {
        // Sprite: sprites/units/kryptos-defense-builder.png -- currently just
        // a copy of the kryptos-builder placeholder (no dedicated art yet).
        // Swap the PNG later without touching this file.
        defenseBuilder = new UnitType("kryptos-defense-builder") {{
            applyBuilderDroneStats(this);
        }};
    }

    private static void applyBuilderDroneStats(UnitType type) {
        // rebuildCommand -> BuilderAI: the drone will fly to whatever
        // BuildPlan is queued on it (via unit.addBuild(...)) and
        // construct it on its own, no player control needed.
        type.defaultCommand = UnitCommand.rebuildCommand;

        type.flying = true;
        type.lowAltitude = true;
        type.isEnemy = false;
        type.controlSelectGlobal = false;
        // Automation only -- the drone can never be selected or
        // commanded by the player via the RTS Command panel, so it can
        // only ever do what KryptosPerimeterDefense explicitly queues on
        // it (see KryptosDroneAI).
        type.playerControllable = false;

        type.hitSize = 9f;
        type.health = 160f;

        type.speed = 2.1f;
        type.accel = 0.1f;
        type.drag = 0.06f;
        type.rotateSpeed = 12f;

        type.engineOffset = 5.5f;
        type.engineSize = 1.6f;

        // Utility drone: no weapons, just building.
        type.buildSpeed = 5.5f;
        type.buildRange = 120f;
    }
}
