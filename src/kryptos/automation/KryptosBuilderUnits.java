package kryptos.automation;

import arc.math.Mathf;
import kryptos.content.KryptosUnits;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

/**
 * Spawns and hands out Kryptos builder drones for
 * {@link KryptosPerimeterDefense} -- one per detected enemy spawn point (see
 * that class's per-spawn drone scaling). Each slot owns exactly one drone:
 * spawned the moment it's needed, reused for as long as it's alive, and
 * quietly replaced if it dies.
 *
 * KryptosAutoConveyor has been removed, and KryptosSmartDrill no longer uses
 * a dedicated drone -- it now queues its BuildPlans onto whatever unit the
 * player is directly controlling instead (matching mod-mindustry's
 * SmartDrillFeature). This class is kept for PerimeterDefense's drones only.
 *
 * Automation only -- every drone here is always locked to
 * {@link KryptosDroneAI} and is never player-controllable (see
 * {@code playerControllable = false} on {@link KryptosUnits#defenseBuilder}).
 */
public final class KryptosBuilderUnits {

    private static final float SPAWN_JITTER = 12f;

    private KryptosBuilderUnits() {}

    /**
     * Returns {@code current} if it's still alive, on the right team, and
     * still the right type, otherwise spawns a fresh drone of {@code type}
     * near the core and returns that instead. Returns null only if there's
     * no core to spawn next to.
     */
    public static Unit getOrSpawn(Unit current, Team team, UnitType type) {
        if (current != null && current.isValid() && current.team == team && current.type == type) {
            // Guards against drones that survived from before this fix (e.g.
            // loaded from an existing save) and are still stuck on the stock
            // BuilderAI or some other controller -- force our controller
            // back on instead of leaving them to their old behavior.
            if (!(current.controller() instanceof KryptosDroneAI)) {
                current.controller(new KryptosDroneAI());
            }
            return current;
        }

        Building core = team.core();
        if (core == null) return null;

        Unit unit = type.create(team);
        unit.set(core.x + Mathf.range(SPAWN_JITTER), core.y + Mathf.range(SPAWN_JITTER));
        unit.rotation = 90f;
        // Force our own controller instead of the stock BuilderAI that
        // create() would otherwise assign -- see KryptosDroneAI for why.
        unit.controller(new KryptosDroneAI());
        unit.add();
        return unit;
    }

    /**
     * Kills every existing drone of our type on load, no exceptions. Without
     * this, a drone left over from a previous session/save (spawned before a
     * fix existed, or orphaned when the module's static reference was reset)
     * just sits in the world running whatever old/default behavior it had --
     * completely invisible to and unmanaged by the current code. Called from
     * KryptosPerimeterDefense's reset() on WorldLoadEvent, so the world
     * always starts with zero drones and the next getOrSpawn() call is
     * guaranteed to create a clean one.
     */
    public static void killAll() {
        Groups.unit.each(u -> {
            if (u.type == KryptosUnits.defenseBuilder) u.kill();
        });
    }
}
