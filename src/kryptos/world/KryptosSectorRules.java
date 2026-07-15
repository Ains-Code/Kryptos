package kryptos.world;

import mindustry.Vars;

import java.util.HashMap;
import java.util.Map;

/**
 * Single source of truth for which campaign sectors Kryptos Ore is
 * allowed to appear on, and how each sector's deposits are shaped.
 *
 * {@link KryptosOreGenerator} never hardcodes a sector name or a rarity
 * value -- it only ever asks {@link #canGenerate()} and {@link #density()}.
 * Adding, removing, or re-tuning a sector means editing the
 * {@code SECTORS} map below and nothing else in the mod.
 */
public final class KryptosSectorRules {

    /**
     * Per-sector shape of Kryptos deposits.
     *
     * <ul>
     *   <li>{@code regionThreshold} -- the coarse "region" noise cutoff a
     *   point must clear before a deposit is even allowed to start there.
     *   Higher means less of the sector can hold ore at all, which is what
     *   actually varies rarity here (not the size of individual patches).</li>
     *   <li>{@code minRadius}/{@code maxRadius} -- the range an individual
     *   deposit's radius is drawn from before noise-based edge perturbation
     *   and the generator's hard per-patch tile cap are applied.</li>
     * </ul>
     *
     * Values are pre-calibrated for the noise parameters used in
     * {@link KryptosOreGenerator} (region field: 2 octaves, 0.5
     * persistence, 1/140 scale).
     */
    public enum Density {
        COMMON(0.2029f, 6f, 13f),
        MEDIUM(0.3492f, 5f, 10f),
        RARE(0.4862f, 4f, 7f);

        public final float regionThreshold;
        public final float minRadius;
        public final float maxRadius;

        Density(float regionThreshold, float minRadius, float maxRadius) {
            this.regionThreshold = regionThreshold;
            this.minRadius = minRadius;
            this.maxRadius = maxRadius;
        }
    }

    private static final Map<String, Density> SECTORS = new HashMap<>();

    static {
        // Verified against Mindustry v159.5 source: mindustry.content.SectorPresets
        // field names (which are also the MappableContent.name string passed to
        // each SectorPreset's constructor -- confirmed independently by the
        // sector.<name>.description keys in core/assets/bundles/bundle.properties,
        // and by this mod's own "Sector: frozenForest" runtime log).
        // "craters" / "frozen-forest" / "ruinous-shores" were never real IDs --
        // the in-game names ("The Craters", "Frozen Forest", "Ruinous Shores")
        // don't match the content system's camelCase, no-hyphen identifiers.
        SECTORS.put("crateredBattleground", Density.COMMON); // "The Craters" / "Cratered Battleground"
        SECTORS.put("frozenForest", Density.MEDIUM);
        SECTORS.put("ruinousShores", Density.RARE);

        // Future sectors (Kryptos Planet, Kryptos Campaign, etc.) get
        // added here as one line each -- see class docs.
    }

    private KryptosSectorRules() {
    }

    /** Whether Kryptos Ore may generate on the currently loaded sector at all. */
    public static boolean canGenerate() {
        return SECTORS.containsKey(currentSectorId());
    }

    /** The current sector's deposit shape, defaulting to RARE if queried off-list. */
    public static Density density() {
        Density d = SECTORS.get(currentSectorId());
        return d != null ? d : Density.RARE;
    }

    /** The current sector's preset name, or null if not on a campaign sector. */
    public static String currentSectorId() {
        if (Vars.state == null) return null;
        if (Vars.state.rules == null) return null;
        if (Vars.state.rules.sector == null) return null;
        if (Vars.state.rules.sector.preset == null) return null;

        return Vars.state.rules.sector.preset.name;
    }
}
