package kryptos;

import arc.util.Log;
import kryptos.automation.KryptosAutoConveyor;
import kryptos.automation.KryptosLogicDeploy;
import kryptos.automation.KryptosSmartDrill;
import kryptos.world.KryptosOreGenerator;
import kryptos.ui.KryptosAutomationPanel;
import kryptos.ui.KryptosHealthBar;
import kryptos.ui.KryptosHud;
import kryptos.ui.KryptosPathIndicator;
import kryptos.ui.KryptosRangeDisplay;
import kryptos.ui.KryptosTheme;
import kryptos.ui.KryptosTimeControl;
import kryptos.util.KryptosCrashLogger;

public final class KryptosBootstrap {

    private static boolean initialized = false;

    private KryptosBootstrap() {
        // Utility class
    }

    public static void init() {

        if (initialized) return;
        initialized = true;

        // Installed first, before any other subsystem, so it can catch
        // crashes coming from anything below -- including things outside
        // Kryptos entirely.
        KryptosCrashLogger.install();

        Log.info("Initializing Kryptos systems...");
        Log.info("Kryptos build timestamp: @", KryptosBuildConfig.BUILD_TIMESTAMP);

        // ===========================
        // UI
        // ===========================

        run("KryptosTheme.apply", KryptosTheme::apply);
        run("KryptosHud.build", KryptosHud::build);
        run("KryptosAutomationPanel.build", KryptosAutomationPanel::build);
        run("KryptosPathIndicator.init", KryptosPathIndicator::init);
        run("KryptosHealthBar.init", KryptosHealthBar::init);
        run("KryptosRangeDisplay.init", KryptosRangeDisplay::init);
        run("KryptosTimeControl.init", KryptosTimeControl::init);

        // ===========================
        // World
        // ===========================

        run("KryptosOreGenerator.init", KryptosOreGenerator::init);
        run("KryptosAutoConveyor.init", KryptosAutoConveyor::init);
        run("KryptosSmartDrill.init", KryptosSmartDrill::init);
        run("KryptosLogicDeploy.init", KryptosLogicDeploy::init);

        Log.info("Kryptos systems initialized.");
    }

    /**
     * Runs a single subsystem's init/build step in isolation. If it throws,
     * the failure is logged with its full stack trace and every other
     * subsystem still gets a chance to load, instead of one bad component
     * silently taking down the entire mod's UI/world setup.
     */
    private static void run(String name, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            Log.err("[Kryptos] " + name + " failed to initialize:", t);
        }
    }
}
