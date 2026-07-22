package kryptos;

import arc.util.Log;
import kryptos.automation.KryptosAutoConveyor;
import kryptos.automation.KryptosSmartDrill;
import kryptos.world.KryptosOreGenerator;
import kryptos.ui.KryptosAutomationPanel;
import kryptos.ui.KryptosHealthBar;
import kryptos.ui.KryptosHud;
import kryptos.ui.KryptosPathIndicator;
import kryptos.ui.KryptosRangeDisplay;
import kryptos.ui.KryptosTheme;
import kryptos.ui.KryptosTimeControl;

public final class KryptosBootstrap {

    private static boolean initialized = false;

    private KryptosBootstrap() {
        // Utility class
    }

    public static void init() {

        if (initialized) return;
        initialized = true;

        Log.info("Initializing Kryptos systems...");
        Log.info("Kryptos build timestamp: @", KryptosBuildConfig.BUILD_TIMESTAMP);

        // ===========================
        // UI
        // ===========================

        KryptosTheme.apply();
        KryptosHud.build();
        KryptosAutomationPanel.build();
        KryptosPathIndicator.init();
        KryptosHealthBar.init();
        KryptosRangeDisplay.init();
        KryptosTimeControl.init();

        // ===========================
        // World
        // ===========================
        
        KryptosOreGenerator.init();
        KryptosAutoConveyor.init();
        KryptosSmartDrill.init();

        Log.info("Kryptos systems initialized.");
    }
}
