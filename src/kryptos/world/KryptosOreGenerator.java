package kryptos.world;

import arc.Events;
import kryptos.content.KryptosBlocks;
import kryptos.util.KryptosNoise;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.OreBlock;

import static mindustry.Vars.world;

/**
 * Places Kryptos Ore onto the loaded world as irregular, noise-shaped
 * veins -- never touching Copper, Lead, Coal, Titanium, Thorium, Scrap,
 * or any other ore overlay already sitting on a tile.
 *
 * <h2>How it decides where ore goes</h2>
 * Generation only runs at all on campaign sectors whitelisted in
 * {@link KryptosSectorRules}, and that same class supplies each sector's
 * vein density -- this file never hardcodes a sector name or a spawn
 * rate.
 *
 * The world is scanned exactly once per load. For every tile that is
 * (a) on an allowed sector, (b) sitting on Kryptos-compatible ground,
 * and (c) not already holding any ore overlay, a 2D noise field
 * ({@link KryptosNoise}) is sampled at that tile's coordinates. Tiles
 * whose noise value clears the sector's threshold become Kryptos Ore.
 *
 * Because the noise field is spatially smooth, qualifying tiles cluster
 * into organic blobs and veins entirely on their own -- no flood fill,
 * random walk, or grid-cell hack needed, and nothing about this approach
 * can produce straight lines, square cells, or checkerboard patterns.
 *
 * <h2>Determinism</h2>
 * The noise field's seed is derived purely from the world's dimensions
 * and the current sector id, so a given map on a given sector always
 * produces the exact same Kryptos veins, on every load, with no reliance
 * on run-to-run RNG state.
 *
 * <h2>Extending this later</h2>
 * Multiple Kryptos ores, a Kryptos planet, or per-biome floor rules can
 * all be added by generalizing {@link #generate()} to loop over a list of
 * ore/sector configurations instead of the single hardcoded ore below --
 * the tile scan, noise sampling, and sector lookup all stay the same.
 */
public final class KryptosOreGenerator {

    /** How many noise layers are blended per sample; more = finer detail. */
    private static final int NOISE_OCTAVES = 3;
    /** How much each successive octave's amplitude shrinks. */
    private static final double NOISE_PERSISTENCE = 0.5;
    /** Base noise frequency; smaller values produce larger, smoother blobs. */
    private static final double NOISE_SCALE = 1.0 / 24.0;

    private KryptosOreGenerator() {
    }

    public static void init() {
        Events.on(WorldLoadEvent.class, e -> generate());
    }

    /**
     * Single-pass world scan: every eligible tile is sampled against the
     * noise field once and converted to Kryptos Ore if it qualifies.
     */
    private static void generate() {
        if (!KryptosSectorRules.canGenerate()) return;

        OreBlock ore = KryptosBlocks.oreCustom;
        if (ore == null) return;

        KryptosNoise noise = new KryptosNoise(worldSeed());

        for (Tile tile : world.tiles) {
            if (tile == null) continue;
            if (tile.overlay() instanceof OreBlock) continue;
            if (!isValidFloor(tile)) continue;
            if (!passesNoise(noise, tile.x, tile.y)) continue;

            createPatch(tile, ore);
        }
    }

    /** Converts a single qualifying tile into Kryptos Ore. */
    private static void createPatch(Tile tile, OreBlock ore) {
        tile.setOverlay(ore);
    }

    /**
     * Whether the tile's floor is ground Kryptos Ore is allowed to sit on.
     * Deliberately conservative -- bare rock/ice-type floors only -- so
     * Kryptos never shows up on ground vanilla ore doesn't favor either.
     */
    private static boolean isValidFloor(Tile tile) {
        String floor = tile.floor().name;

        return floor.contains("stone")
            || floor.contains("shale")
            || floor.contains("dacite")
            || floor.contains("ferric")
            || floor.contains("regolith")
            || floor.contains("ice");
    }

    /**
     * Samples the noise field at a tile's coordinates and checks it
     * against the current sector's threshold. This check alone is what
     * produces vein-shaped clusters instead of scattered dots -- the
     * noise field is continuous, so neighboring tiles tend to pass or
     * fail together.
     */
    private static boolean passesNoise(KryptosNoise noise, int x, int y) {
        double value = noise.octaves(x, y, NOISE_OCTAVES, NOISE_PERSISTENCE, NOISE_SCALE);
        return value > getSpawnChance();
    }

    /**
     * The noise threshold a tile must clear to become Kryptos Ore on the
     * current sector. Looked up from {@link KryptosSectorRules} so this
     * class never hardcodes a per-sector rate -- Common/Medium/Rare
     * sectors just resolve to different thresholds here.
     */
    private static float getSpawnChance() {
        return KryptosSectorRules.density().threshold;
    }

    /**
     * Deterministic seed built from world size plus the current sector
     * id, so the same map on the same sector always yields identical
     * Kryptos veins, while different sectors sharing map dimensions
     * still get distinct fields.
     */
    private static long worldSeed() {
        long w = world.width();
        long h = world.height();
        String sector = KryptosSectorRules.currentSectorId();
        long sectorHash = sector != null ? sector.hashCode() : 0;

        return w * 341873128712L + h * 132897987541L + sectorHash * 668265263L;
    }
}