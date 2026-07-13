package kryptos;

import arc.Core;
import arc.Events;
import arc.util.Log;
import kryptos.content.KryptosBlocks;
import kryptos.content.KryptosItems;
import kryptos.content.KryptosOreInjector;
import kryptos.ui.KryptosHealthBar;
import kryptos.ui.KryptosHud;
import kryptos.ui.KryptosPathIndicator;
import kryptos.ui.KryptosRangeDisplay;
import kryptos.ui.KryptosTheme;
import kryptos.ui.KryptosTimeControl;
import kryptos.ui.KryptosUpdateChecker;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.Mod;
import mindustry.mod.Mods.LoadedMod;

public class KryptosMod extends Mod {
    private static final String SETTING_LAST_VERSION = "kryptos-last-seen-version";
    private static final float UPDATE_TOAST_DURATION = 5f;

    public KryptosMod() {
        Log.info("Kryptos mod loaded.");
        Events.on(ClientLoadEvent.class, e -> {
            KryptosTheme.apply();
            KryptosHud.build();
            KryptosPathIndicator.init();
            KryptosHealthBar.init();
            KryptosRangeDisplay.init();
            KryptosTimeControl.init();
            KryptosUpdateChecker.check();
            KryptosOreInjector.init();
            announceIfUpdated();
        });
    }

    @Override
    public void loadContent() {
        KryptosItems.load();
        KryptosBlocks.load();
    }

    /**
     * Shows a small fading toast the first time the player launches the game
     * after Kryptos was updated to a new version. Stays silent on a totally
     * fresh install (no version recorded yet) so new players don't get an
     * "updated!" message for a mod they just installed for the first time.
     */
    private void announceIfUpdated() {
        LoadedMod self = Vars.mods.getMod(KryptosMod.class);
        if (self == null || self.meta.version == null) {
            return;
        }

        String currentVersion = self.meta.version;
        String lastSeenVersion = Core.settings.getString(SETTING_LAST_VERSION, null);

        if (lastSeenVersion != null && !lastSeenVersion.equals(currentVersion)) {
            Vars.ui.showInfoToast("[accent]Kryptos[] updated to v" + currentVersion + "!", UPDATE_TOAST_DURATION);
        }

        Core.settings.put(SETTING_LAST_VERSION, currentVersion);
    }
}
