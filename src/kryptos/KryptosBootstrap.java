package kryptos;

import arc.util.Log;
import kryptos.automation.KryptosLogicDeploy;
import kryptos.automation.KryptosSmartDrill;
import kryptos.content.KryptosBlocks;
import kryptos.world.KryptosOreGenerator;
import kryptos.ui.KryptosAutomationPanel;
import kryptos.ui.KryptosHealthBar;
import kryptos.ui.KryptosHud;
import kryptos.ui.KryptosPathIndicator;
import kryptos.ui.KryptosRangeDisplay;
import kryptos.ui.KryptosTheme;
import kryptos.ui.KryptosTimeControl;
import kryptos.util.KryptosCrashLogger;
import mindustry.content.TechTree.TechNode;
import mindustry.ctype.UnlockableContent;

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
        run("KryptosSmartDrill.init", KryptosSmartDrill::init);
        run("KryptosLogicDeploy.init", KryptosLogicDeploy::init);

        // ===========================
        // Diagnostics
        // ===========================

        run("KryptosBootstrap.diagnoseTechTree", KryptosBootstrap::diagnoseTechTree);

        Log.info("Kryptos systems initialized.");
    }

    /**
     * TEMP DIAGNOSTIC -- remove once the Database/tech-tree visibility issue
     * is confirmed fixed. Dumps the actual post-init runtime state of each
     * Kryptos block's TechNode wiring, since the Database screen's behavior
     * depends on state set by Planet.init() (shownPlanets) and
     * UnlockableContent.postInit() (databaseTabs), neither of which we can
     * inspect just by reading the source.
     */
    private static void diagnoseTechTree() {
        dumpNode("KryptosBlocks.oreFactory", KryptosBlocks.oreFactory);
        dumpNode("KryptosBlocks.factory", KryptosBlocks.factory);
    }

    private static void dumpNode(String label, UnlockableContent content) {
        if (content == null) {
            Log.info("[Kryptos][diag] @ -> content reference itself is null!", label);
            return;
        }

        TechNode node = content.techNode;

        Log.info("[Kryptos][diag] @", label);
        Log.info("[Kryptos][diag]   techNode: @", node == null ? "NULL (no TechNode at all)" : "present, parent=" + (node.parent == null ? "none (root)" : node.parent.content.name));
        Log.info("[Kryptos][diag]   shownPlanets: @", content.shownPlanets.isEmpty() ? "EMPTY" : content.shownPlanets.toString());
        Log.info("[Kryptos][diag]   databaseTabs: @", content.databaseTabs.isEmpty() ? "EMPTY" : content.databaseTabs.toString());
        Log.info("[Kryptos][diag]   unlocked(): @", content.unlocked());
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
