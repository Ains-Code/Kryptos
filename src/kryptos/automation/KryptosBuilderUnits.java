package kryptos.automation;

import arc.math.Mathf;
import kryptos.content.KryptosUnits;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;

/**
 * Spawns and hands out the shared Kryptos builder drone for
 * {@link KryptosAutoConveyor} and {@link KryptosSmartDrill}. Each module owns
 * exactly one drone: spawned the moment its toggle is switched on, reused for
 * as long as it's alive, and quietly replaced if it dies. Both modules keep
 * their own separate drone (they call this independently), so turning both
 * toggles on gives you 2 drones total, matching one-drone-per-toggle.
 */
public final class KryptosBuilderUnits {

    private static final float SPAWN_JITTER = 12f;

    private KryptosBuilderUnits() {}

    /**
     * Returns {@code current} if it's still alive and on the right team,
     * otherwise spawns a fresh drone near the core and returns that instead.
     * Returns null only if there's no core to spawn next to.
     */
    public static Unit getOrSpawn(Unit current, Team team) {
        if (current != null && current.isValid() && current.team == team) {
            return current;
        }

        Building core = team.core();
        if (core == null) return null;

        Unit unit = KryptosUnits.builder.create(team);
        unit.set(core.x + Mathf.range(SPAWN_JITTER), core.y + Mathf.range(SPAWN_JITTER));
        unit.rotation = 90f;
        // Force our own controller instead of the stock BuilderAI that
        // create() would otherwise assign -- see KryptosDroneAI for why.
        unit.controller(new KryptosDroneAI());
        unit.add();
        return unit;
    }
}

