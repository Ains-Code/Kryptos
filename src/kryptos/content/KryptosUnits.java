package kryptos.content;

import mindustry.ai.UnitCommand;
import mindustry.type.UnitType;

/**
 * Content-side unit definitions for Kryptos. Currently just the shared
 * "builder drone" used by {@link kryptos.automation.KryptosAutoConveyor} and
 * {@link kryptos.automation.KryptosSmartDrill} -- each of those modules spawns
 * (and reuses) one of these instead of forcing the player's own unit to fly
 * out and build things.
 *
 * Sprite is a placeholder (sprites/units/kryptos-builder.png) until the real
 * one comes out of the sprite generator pipeline; swap the PNG later without
 * touching this file.
 */
public class KryptosUnits {
    public static UnitType builder;

    public static void load() {
        builder = new UnitType("kryptos-builder") {{
            // rebuildCommand -> BuilderAI: the drone will fly to whatever
            // BuildPlan is queued on it (via unit.addBuild(...)) and
            // construct it on its own, no player control needed.
            defaultCommand = UnitCommand.rebuildCommand;

            flying = true;
            lowAltitude = true;
            isEnemy = false;
            controlSelectGlobal = false;

            hitSize = 9f;
            health = 160f;

            speed = 2.1f;
            accel = 0.1f;
            drag = 0.06f;
            rotateSpeed = 12f;

            engineOffset = 5.5f;
            engineSize = 1.6f;

            // Utility drone: no weapons, just building.
            buildSpeed = 1.15f;
            buildRange = 60f;
        }};
    }
}
