package kryptos.content;

import mindustry.ai.UnitCommand;
import mindustry.type.UnitType;

/**
 * Content-side unit definitions for Kryptos. Two "builder drone" types --
 * {@link #builder} for {@link kryptos.automation.KryptosAutoConveyor} and
 * {@link #smartDrillBuilder} for {@link kryptos.automation.KryptosSmartDrill}
 * -- each module spawns (and reuses) its own instead of forcing the player's
 * own unit to fly out and build things.
 *
 * They're functionally identical, split into two types only so the two
 * modules' drones are visually distinguishable in-game -- previously both
 * used the exact same placeholder sprite, so there was no way to tell which
 * drone belonged to which module just by looking at it.
 */
public class KryptosUnits {
    public static UnitType builder;
    public static UnitType smartDrillBuilder;

    public static void load() {
        builder = new UnitType("kryptos-builder") {{
            applyBuilderDroneStats(this);
        }};

        // Sprite: sprites/units/kryptos-smartdrill.png
        smartDrillBuilder = new UnitType("kryptos-smartdrill") {{
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
        // only ever do what KryptosSmartDrill/KryptosAutoConveyor
        // explicitly queue on it (see KryptosDroneAI).
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
