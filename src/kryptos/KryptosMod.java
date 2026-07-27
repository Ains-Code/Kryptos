package kryptos;

import arc.Core;
import arc.Events;
import arc.util.Log;
import kryptos.content.KryptosBlocks;
import kryptos.content.KryptosItems;
import kryptos.content.KryptosTechTree;
import kryptos.content.KryptosUnits;
import kryptos.ui.KryptosUpdateChecker;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.Mod;
import mindustry.mod.Mods.LoadedMod;

public class KryptosMod extends Mod {

    private static final String SETTING_LAST_VERSION = "kryptos-last-seen-version";
    private static final float UPDATE_TOAST_DURATION = 5f;

    public KryptosMod() {
    Log.info("Loading Kryptos Mod...");

    Events.on(ClientLoadEvent.class, e -> {

        KryptosBootstrap.init();

        KryptosUpdateChecker.check();
        announceIfUpdated();

        Log.info("Kryptos initialization complete.");
    });
    }

    @Override
    public void loadContent() {
        Log.info("Loading Kryptos content...");

        KryptosItems.load();
        KryptosUnits.load();
        KryptosBlocks.load();

        // Must run here, not in init(). Planet.init() walks the tech tree
        // exactly once (right after createModContent(), before Mod.init()
        // is ever called) and tags each node's content as "shown on this
        // planet." Nodes attached after that one-time pass are structurally
        // correct but permanently invisible in the Database -- silently, no
        // error. loadContent() runs before that pass; init() runs after it.
        KryptosTechTree.load();

        Log.info("Kryptos content loaded.");
    }

    private void announceIfUpdated() {
        LoadedMod self = Vars.mods.getMod(KryptosMod.class);

        if (self == null || self.meta.version == null) {
            return;
        }

        String currentVersion = self.meta.version;
        String lastSeenVersion = Core.settings.getString(SETTING_LAST_VERSION, null);

        if (lastSeenVersion != null && !lastSeenVersion.equals(currentVersion)) {
            Vars.ui.showInfoToast(
                "[accent]Kryptos[] updated to v" + currentVersion + "!",
                UPDATE_TOAST_DURATION
            );
        }

        Core.settings.put(SETTING_LAST_VERSION, currentVersion);
    }
}
