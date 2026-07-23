package kryptos.automation;

import arc.math.Mathf;
import kryptos.content.KryptosUnits;
import kryptos.ui.KryptosAutomationPanel;
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
            // Guards against drones that survived from before this fix (e.g.
            // loaded from an existing save) and are still stuck on the stock
            // BuilderAI or some other controller -- force our controller
            // back on instead of leaving them to their old behavior.
            // Skipped while Manual Drone Control is on, since that's the
            // player deliberately taking the wheel.
            if (!KryptosAutomationPanel.manualDroneControl && !(current.controller() instanceof KryptosDroneAI)) {
                current.controller(new KryptosDroneAI());
            }
            return current;
        }

        Building core = team.core();
        if (core == null) return null;

        Unit unit = KryptosUnits.builder.create(team);
        unit.set(core.x + Mathf.range(SPAWN_JITTER), core.y + Mathf.range(SPAWN_JITTER));
        unit.rotation = 90f;
        // Force our own controller instead of whatever create() assigned by
        // default -- unless Manual Drone Control is on, in which case leave
        // it as the player-controllable CommandAI create() already set up.
        if (!KryptosAutomationPanel.manualDroneControl) {
            unit.controller(new KryptosDroneAI());
        }
        unit.add();
        return unit;
    }
}

