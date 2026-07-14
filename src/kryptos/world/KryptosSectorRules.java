package kryptos.world;

import mindustry.Vars;

import java.util.HashMap;
import java.util.Map;

/**
 * Single source of truth for which campaign sectors Kryptos Ore is
 * allowed to appear on, and how dense its veins are on each one.
 *
 * {@link KryptosOreGenerator} never hardcodes a sector name -- it only
 * ever asks {@link #canGenerate()} and {@link #density()}. Adding,
 * removing, or re-tuning a sector means editing the {@code SECTORS} map
 * below and nothing else in the mod.
 */
public final class KryptosSectorRules {

    /**
     * Relative Kryptos vein density for a sector. {@code threshold} is
     * the noise cutoff a tile's noise sample must clear to become ore --
     * lower threshold means more tiles qualify, i.e. a denser sector.
     * Values are pre-calibrated for the noise parameters used in
     * {@link KryptosOreGenerator} (3 octaves, 0.5 persistence, 1/24 scale).
     */
    public enum Density {
        COMMON(0.47f),
        MEDIUM(0.55f),
        RARE(0.62f);

        public final float threshold;

        Density(float threshold) {
            this.threshold = threshold;
        }
    }

    private static final Map<String, Density> SECTORS = new HashMap<>();

    static {
        SECTORS.put("craters", Density.COMMON);
        SECTORS.put("frozen-forest", Density.MEDIUM);
        SECTORS.put("ruinous-shores", Density.RARE);

        // Future sectors (Kryptos Planet, Kryptos Campaign, etc.) get
        // added here as one line each -- see class docs.
    }

    private KryptosSectorRules() {
    }

    /** Whether Kryptos Ore may generate on the currently loaded sector at all. */
    public static boolean canGenerate() {
        return SECTORS.containsKey(currentSectorId());
    }

    /** The current sector's vein density, defaulting to RARE if queried off-list. */
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