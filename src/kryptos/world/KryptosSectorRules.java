package kryptos.world;

import mindustry.Vars;
import mindustry.world.Tile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    /**
     * Whether a tile's floor counts as valid ground for a Kryptos deposit
     * to occupy. Kept as an interface (rather than a bare {@code Set<String>})
     * so the matching strategy -- keyword/substring today -- can change later
     * (exact names, {@code Block} references, predicates) without touching
     * {@link SectorRule} or the generator's call site.
     */
    public interface AllowedFloors {
        boolean isValid(Tile tile);
    }

    /**
     * Matches a floor by whether its {@code name} contains any of a fixed
     * set of keywords -- the same matching strategy the generator used to
     * hardcode, now data instead of code. Two instances can be combined
     * with {@link #plus} so a sector can extend a shared base list instead
     * of repeating it.
     */
    public static final class KeywordFloors implements AllowedFloors {
        private final Set<String> keywords;

        public KeywordFloors(String... keywords) {
            this.keywords = new HashSet<>(Arrays.asList(keywords));
        }

        private KeywordFloors(Set<String> keywords) {
            this.keywords = keywords;
        }

        @Override
        public boolean isValid(Tile tile) {
            String floor = tile.floor().name;
            for (String keyword : keywords) {
                if (floor.contains(keyword)) return true;
            }
            return false;
        }

        /** A new set of keywords equal to this one plus {@code extra}. */
        public KeywordFloors plus(String... extra) {
            Set<String> combined = new HashSet<>(keywords);
            combined.addAll(Arrays.asList(extra));
            return new KeywordFloors(combined);
        }
    }

    /**
     * Base terrain every sector accepts unless overridden: the vanilla
     * "bare rock" floor families. This is the global/physical layer --
     * floors that are valid ground for ore regardless of biome. Sector
     * entries extend this with {@link KeywordFloors#plus} to add whatever
     * their own biome contributes (e.g. snow, moss) rather than repeating
     * the base list.
     */
    private static final KeywordFloors BASE_FLOORS = new KeywordFloors(
        "stone", "shale", "dacite", "ferric", "regolith", "ice"
    );

    /** One sector's full configuration: how much ore, and where it may sit. */
    public static final class SectorRule {
        public final Density density;
        public final AllowedFloors floors;

        public SectorRule(Density density, AllowedFloors floors) {
            this.density = density;
            this.floors = floors;
        }
    }

    private static final Map<String, SectorRule> SECTORS = new HashMap<>();

    static {
        // Verified against Mindustry v159.5 source: mindustry.content.SectorPresets
        // field names (which are also the MappableContent.name string passed to
        // each SectorPreset's constructor -- confirmed independently by the
        // sector.<name>.description keys in core/assets/bundles/bundle.properties,
        // and by this mod's own "Sector: frozenForest" runtime log).
        // "craters" / "frozen-forest" / "ruinous-shores" were never real IDs --
        // the in-game names ("The Craters", "Frozen Forest", "Ruinous Shores")
        // don't match the content system's camelCase, no-hyphen identifiers.
        SECTORS.put("crateredBattleground",
            new SectorRule(Density.COMMON, BASE_FLOORS)); // "The Craters" / "Cratered Battleground"

        // frozenForest's terrain is dominated by snow/moss, which BASE_FLOORS
        // alone does not cover -- confirmed by the floor histogram
        // (snow=443, moss=234 rejected vs. dacite=30 accepted) during
        // debugging. Extended here, in sector config, instead of widening
        // the shared base for every sector.
        SECTORS.put("frozenForest",
            new SectorRule(Density.MEDIUM, BASE_FLOORS.plus("snow", "moss")));

        SECTORS.put("ruinousShores",
            new SectorRule(Density.RARE, BASE_FLOORS));

        // Future sectors (Kryptos Planet, Kryptos Campaign, etc.) get
        // added here as one line each -- see class docs.
    }

    private KryptosSectorRules() {
    }

    private static SectorRule currentRule() {
        return SECTORS.get(currentSectorId());
    }

    /** Whether Kryptos Ore may generate on the currently loaded sector at all. */
    public static boolean canGenerate() {
        return SECTORS.containsKey(currentSectorId());
    }

    /** The current sector's deposit shape, defaulting to RARE if queried off-list. */
    public static Density density() {
        SectorRule rule = currentRule();
        return rule != null ? rule.density : Density.RARE;
    }

    /**
     * Whether {@code tile}'s floor is valid ground for a Kryptos deposit on
     * the currently loaded sector, defaulting to {@link #BASE_FLOORS} if
     * queried off-list (matching {@link #density()}'s off-list default).
     */
    public static boolean isValidFloor(Tile tile) {
        SectorRule rule = currentRule();
        AllowedFloors floors = rule != null ? rule.floors : BASE_FLOORS;
        return floors.isValid(tile);
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
