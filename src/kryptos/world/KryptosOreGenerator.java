package kryptos.world;

import arc.Core;
import arc.Events;
import arc.util.Log;
import arc.util.Timer;
import kryptos.content.KryptosBlocks;
import kryptos.util.KryptosNoise;
import mindustry.content.Blocks;
import mindustry.game.EventType.SaveLoadEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.OreBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static mindustry.Vars.world;

/**
 * Places Kryptos Ore onto the loaded world as multiple separate, irregular
 * deposits -- never touching Copper, Lead, Coal, Titanium, Thorium, Scrap,
 * or any other ore overlay already sitting on a tile.
 *
 * <h2>Why the previous version was replaced, not tuned</h2>
 * The old algorithm was: for every tile, sample one noise field, place ore
 * if the value cleared a threshold. That is architecturally unable to
 * produce vanilla-style deposits, no matter how the threshold is tuned,
 * because a single noise field is statistically <b>homogeneous</b> --
 * its mean and spatial statistics don't change across the map. Thresholding
 * a homogeneous field therefore makes tiles pass at the same rate
 * <i>everywhere</i>: lowering the threshold shrinks each patch but does not
 * stop patches from being scattered evenly across 100% of the eligible
 * ground. Measured directly against that old code: a 500x500 map produced
 * 844 separate patches spread completely uniformly across the whole
 * sector. Individually small, but with no ore-free stretches anywhere --
 * which reads as "the entire area got converted", exactly as reported.
 * That is a structural property of thresholding one field, not a bad
 * constant, which is why retuning the threshold could never have fixed it.
 *
 * <h2>The replacement pipeline</h2>
 * <pre>
 * coarse "region" noise  -->  decides which parts of the sector may
 *                             hold ore at all (most of the map holds none)
 *          |
 *          v
 * jittered seed lattice  -->  candidate deposit centers inside those
 *                             regions, rejecting any candidate too close
 *                             to an already-accepted one
 *          |
 *          v
 * per-seed radius + fine  -->  each accepted seed grows into one irregular
 * noise edge perturbation      patch, capped at a hard tile limit
 *          |
 *          v
 *   tile.setOverlay()     -->  only on tiles with no existing ore overlay
 * </pre>
 * This is what actually produces vanilla-like results: large ore-free
 * stretches (gated out by the region field), a bounded number of visibly
 * separate deposits (the seed lattice, with a minimum-separation check
 * that makes two patches merging into one mass impossible), organic
 * non-circular edges (the per-tile noise perturbation), and no deposit
 * that can grow without bound (the hard per-patch tile cap).
 *
 * <h2>Determinism</h2>
 * Every random-looking choice -- which lattice cells become seeds, each
 * seed's jitter and radius, and each tile's edge perturbation -- is
 * derived from {@link KryptosNoise}, seeded once from the world's
 * dimensions and the current sector id (see {@link #worldSeed()}). There
 * is no {@code java.util.Random} anywhere in this class, so a given map
 * on a given sector produces the exact same deposits on every load.
 *
 * <h2>Duplicate generation</h2>
 * {@code generate()} still guards against running more than once for the
 * same loaded world (see {@link #generatedFor}). Mindustry's own
 * {@code WorldLoadEvent} javadoc states it fires once tiles finish
 * loading, and no other code path in this project ever calls
 * {@code setOverlay} -- this class is the only one that does, in exactly
 * one place. The guard is kept anyway as a correctness invariant that
 * costs one reference comparison: it makes "runs at most once per world"
 * provably true instead of true only as long as that single-fire
 * assumption keeps holding.
 */
public final class KryptosOreGenerator {

    // ---- Region field: coarse noise deciding WHERE deposits may exist ----
    private static final int REGION_OCTAVES = 2;
    private static final double REGION_PERSISTENCE = 0.5;
    private static final double REGION_SCALE = 1.0 / 140.0;

    // ---- Edge field: fine noise perturbing each patch's boundary ----
    private static final int EDGE_OCTAVES = 2;
    private static final double EDGE_PERSISTENCE = 0.5;
    private static final double EDGE_SCALE = 1.0 / 8.0;
    private static final double EDGE_PERTURBATION = 0.35;

    // ---- Interior density falloff: keeps the core solid, thins the ring ----
    // near effectiveRadius instead of every accepted tile being placed
    // unconditionally. density = 1 - normalized^2 is 1.0 at the seed
    // center and 0.0 at dist == seed.radius; the noise term only ever
    // contributes +/-0.25, so at FILL_THRESHOLD = 0.0 no tile with
    // normalized <= ~0.866 (dist <= ~0.866 * radius, ~75% of the patch's
    // area) can ever fail this check regardless of the noise sample --
    // thinning is confined to the outer ring by construction.
    private static final double FILL_THRESHOLD = 0.0;

    // ---- Seed lattice: candidate deposit centers ----
    private static final int GRID_SPACING = 36;
    private static final double JITTER_FRACTION = 0.6;

    // ---- Hard shape/size limits ----
    // MIN_GAP was previously 6 tiles. That is enough to keep two patches
    // from literally touching (so they count as separate connected
    // components), but 6 tiles is not enough gap to read as separate to
    // the eye once several such patches sit next to each other inside one
    // ore-bearing region -- the result was a wall of near-adjacent 90-tile
    // patches that looked like one continuous field, which is exactly what
    // the in-game screenshots showed. 18 tiles is wide enough to visibly
    // read as bare rock between deposits.
    private static final float MIN_GAP_BETWEEN_PATCHES = 18f;
    // Hard upper bound on *placed* ore tiles per patch -- this is what
    // keeps deposit size, and therefore world balance, predictable. It
    // used to also double as the loop's early-exit condition, which was
    // the bug: because offsets are visited nearest-first, the loop hit
    // this cap while still inside the interior, where the density gate
    // (see FILL_THRESHOLD below) rarely rejects anything -- so the gate
    // never got to run on the outer ring where it actually does work.
    // createPatch() now evaluates every visited offset against validity,
    // radius, and the density gate first, and only afterwards truncates
    // the resulting nearest-first qualifying list down to this many
    // tiles. So this constant still bounds deposit size exactly as
    // before; it just no longer decides when to stop *looking*.
    private static final int MAX_PATCH_TILES = 85;
    // Separate, purely-performance safety cap on how many offsets we'll
    // evaluate at all, sized comfortably above the largest offset list we
    // can produce today (~1017 candidates, for a COMMON-density patch at
    // maxRadius=13: reach = ceil(13 * 1.35) = 18, pi*18^2 ~ 1017), so in
    // practice every candidate gets evaluated and this never binds under
    // current sector rules.
    private static final int MAX_VISITED_OFFSETS = 2000;

    // Salts so jitter-x, jitter-y, and radius don't all collapse onto the
    // same value when hashed from the same (x, y) lattice point.
    private static final long SALT_JITTER_X = 0x1L;
    private static final long SALT_JITTER_Y = 0x2L;
    private static final long SALT_RADIUS = 0x3L;

    /**
     * TEMPORARY MIGRATION SWITCH -- must be {@code false} before release.
     *
     * Existing Campaign sectors already have Kryptos Ore baked into their
     * saved tile data from earlier builds of this generator. Since
     * {@code createPatch} only ever writes to tiles with no existing ore
     * overlay, loading one of those sectors again with a fixed algorithm
     * places approximately zero new ore -- every tile the new algorithm
     * would want is already "occupied" by ore from the old run, so there
     * is no way to visually verify a generator change on them.
     * <p>
     * With this set to {@code true}, {@link #generate()} first strips ONLY
     * {@link KryptosBlocks#oreCustom} overlays (by reference, not
     * {@code instanceof OreBlock} -- see {@link #clearPreviousKryptosOre(OreBlock)})
     * from the currently loaded sector, then immediately runs the normal
     * pipeline as if the sector had never had Kryptos Ore on it. Copper,
     * Lead, Coal, Titanium, Thorium, Scrap, and any other ore are never
     * touched by this, regardless of this flag.
     * <p>
     * This adds one extra full-tile-array scan on load, which is why it's
     * gated behind a flag instead of always running -- it exists purely to
     * let old saves be re-checked against generator changes without
     * starting a new sector, and should be flipped back to {@code false}
     * once verification is done.
     */
    private static final boolean DEBUG_REGENERATE = true;

    /**
     * Identity of the {@code world.tiles} instance generation last ran
     * for. A genuinely new world load gets a new {@code Tiles} object, so
     * comparing by reference tells "a new world just loaded" apart from
     * "this world's load event fired again" with no per-tile bookkeeping.
     */
    private static Object generatedFor = null;

    private KryptosOreGenerator() {
    }

    public static void init() {
        Events.on(WorldLoadEvent.class, e -> generate());
        Events.on(SaveLoadEvent.class, e -> logOreBreakdown("AFTER SAVE RELOAD"));
    }

    /**
     * Runs the whole pipeline at most once per distinct loaded world:
     * gather candidate deposit centers, then grow each into a patch.
     */
    private static void generate() {
        Log.info("[Kryptos] WorldLoadEvent fired.");
        Log.info("[Kryptos] Sector: @", KryptosSectorRules.currentSectorId());
        Log.info("[Kryptos] world=@x@", world.width(), world.height());
        Log.info("[Kryptos] DEBUG_REGENERATE=@", DEBUG_REGENERATE);
        Log.info("[Kryptos] tilesIdentity=@", System.identityHashCode(world.tiles));

        logOverlayIdentities();
        logOreBreakdown("BEFORE generate()");

        if (!KryptosSectorRules.canGenerate()) {
            Log.info("[Kryptos] canGenerate()=false, aborting generation.");
            return;
        }
        if (world.tiles == generatedFor) {
            Log.info("[Kryptos] generatedFor already matches this world.tiles instance, aborting generation.");
            return;
        }

        OreBlock ore = KryptosBlocks.oreCustom;
        if (ore == null) {
            Log.info("[Kryptos] KryptosBlocks.oreCustom is null, aborting generation.");
            return;
        }

        // Marked before the work runs, not after, so the guard is airtight
        // against re-entry rather than merely unlikely.
        generatedFor = world.tiles;

        int beforeClear = countKryptosOre(ore);
        Log.info("[Kryptos] Before clear: @", beforeClear);

        int removed = 0;
        if (DEBUG_REGENERATE) {
            removed = clearPreviousKryptosOre(ore);
        }
        Log.info("[Kryptos] Removed: @", removed);

        int afterClear = countKryptosOre(ore);
        Log.info("[Kryptos] After clear: @", afterClear);

        long seed = worldSeed();
        KryptosNoise noise = new KryptosNoise(seed);
        KryptosSectorRules.Density density = KryptosSectorRules.density();

        int[] candidateCells = new int[1];
        int[] rejectionCounts = new int[3]; // [0]=out-of-bounds jitter, [1]=regionThreshold, [2]=overlapsExisting
        List<Seed> seeds = collectSeeds(noise, density, seed, candidateCells, rejectionCounts);
        Log.info("[Kryptos] Candidate cells: @", candidateCells[0]);
        Log.info("[Kryptos]   Rejected (out-of-bounds jitter): @", rejectionCounts[0]);
        Log.info("[Kryptos]   Rejected (regionThreshold): @", rejectionCounts[1]);
        Log.info("[Kryptos]   Rejected (overlapsExisting): @", rejectionCounts[2]);
        Log.info("[Kryptos] Accepted seeds: @", seeds.size());

        int placedTotal = 0;
        Map<String, Integer> rejectedFloorHistogram = new HashMap<>();
        Map<String, Integer> acceptedFloorHistogram = new HashMap<>();
        for (Seed s : seeds) {
            placedTotal += createPatch(noise, s, ore, rejectedFloorHistogram, acceptedFloorHistogram);
        }
        Log.info("[Kryptos] Placed tiles: @", placedTotal);

        logFloorHistogram("Rejected floors:", rejectedFloorHistogram);
        logFloorHistogram("Accepted floors:", acceptedFloorHistogram);

        int finalCount = countKryptosOre(ore);
        Log.info("[Kryptos] Final Kryptos Ore tiles: @", finalCount);

        logOreBreakdown("AFTER generate()");
        Core.app.post(() -> logOreBreakdown("ONE FRAME LATER"));
        Timer.schedule(() -> logOreBreakdown("AFTER 5 SECONDS"), 5f);
    }

    /**
     * Diagnostic-only: prints a floor-name -&gt; tile-count histogram, sorted
     * highest-count first. Deliberately avoids java.util.stream (the
     * Android jar's d8 desugaring for this project only targets --min-api
     * 14, which doesn't reliably backport the streams API), using a plain
     * ArrayList + Collections.sort instead.
     */
    private static void logFloorHistogram(String label, Map<String, Integer> histogram) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(histogram.entrySet());
        Collections.sort(entries, (a, b) -> b.getValue() - a.getValue());

        Log.info("[Kryptos] @", label);
        for (Map.Entry<String, Integer> entry : entries) {
            Log.info("[Kryptos]   @ = @", entry.getKey(), entry.getValue());
        }
    }

    /** Diagnostic-only: increments a String-&gt;count histogram entry by one. */
    private static void incrementHistogram(Map<String, Integer> histogram, String key) {
        Integer current = histogram.get(key);
        histogram.put(key, current == null ? 1 : current + 1);
    }

    /**
     * Diagnostic-only: counts every overlaid tile on the map, broken down
     * by known vanilla ore plus Kryptos Ore, tagged with {@code label} so
     * the same snapshot shape can be compared across lifecycle points
     * (before/after generation, a frame later, 5 seconds later, after a
     * save reload). Kryptos is counted two ways -- by reference equality
     * to {@link KryptosBlocks#oreCustom} and by overlay name -- so a
     * mismatch between the two numbers is direct proof that some tiles
     * carry a *different* "ore-kryptos" Block instance than the one this
     * generator currently holds a reference to (e.g. stale content after
     * a reload).
     */
    private static void logOreBreakdown(String label) {
        OreBlock ore = KryptosBlocks.oreCustom;
        // Mindustry's content loader prefixes non-vanilla content names with
        // the mod's internal name (Vars.content.transformName), so the
        // runtime name is "kryptos-ore-kryptos", not the "ore-kryptos"
        // literal passed into the OreBlock constructor in KryptosBlocks.
        // Read it off the live block instead of hardcoding either form, so
        // this diagnostic can't drift out of sync with the actual name again.
        String expectedName = ore != null ? ore.name : null;

        int totalOverlay = 0;
        int copper = 0, lead = 0, coal = 0, titanium = 0, thorium = 0, scrap = 0;
        int kryptosByRef = 0, kryptosByName = 0;

        for (Tile tile : world.tiles) {
            if (tile == null) continue;

            Block overlay = tile.overlay();
            if (overlay == null || overlay == Blocks.air) continue;

            totalOverlay++;

            if (overlay == Blocks.oreCopper) copper++;
            else if (overlay == Blocks.oreLead) lead++;
            else if (overlay == Blocks.oreCoal) coal++;
            else if (overlay == Blocks.oreTitanium) titanium++;
            else if (overlay == Blocks.oreThorium) thorium++;
            else if (overlay == Blocks.oreScrap) scrap++;

            if (ore != null && overlay == ore) kryptosByRef++;
            if (expectedName != null && expectedName.equals(overlay.name)) kryptosByName++;
        }

        Log.info("[Kryptos] Ore breakdown @", label);
        Log.info("[Kryptos]   Total overlay tiles: @", totalOverlay);
        Log.info("[Kryptos]   Copper: @", copper);
        Log.info("[Kryptos]   Lead: @", lead);
        Log.info("[Kryptos]   Coal: @", coal);
        Log.info("[Kryptos]   Titanium: @", titanium);
        Log.info("[Kryptos]   Thorium: @", thorium);
        Log.info("[Kryptos]   Scrap: @", scrap);
        Log.info("[Kryptos]   Kryptos (by reference ==): @", kryptosByRef);
        Log.info("[Kryptos]   Kryptos (by name match): @", kryptosByName);
        if (kryptosByRef != kryptosByName) {
            Log.info("[Kryptos]   WARNING: reference count != name count -- a different 'ore-kryptos' Block instance is present on some tiles (stale content reload?).");
        }
    }

    /** Diagnostic-only: counts tiles whose overlay is this specific Kryptos Ore instance. */
    private static int countKryptosOre(OreBlock ore) {
        int count = 0;
        for (Tile tile : world.tiles) {
            if (tile == null) continue;
            if (tile.overlay() == ore) count++;
        }
        return count;
    }

    /**
     * Diagnostic-only: prints identity info for the first 20 tiles carrying
     * any overlay at all, plus the identity hash of {@link KryptosBlocks#oreCustom}
     * itself, so overlay reference-equality issues (e.g. a stale/duplicate
     * {@code oreCustom} instance from a reload) can be verified directly
     * from the logs instead of inferred.
     */
    private static void logOverlayIdentities() {
        int printed = 0;
        for (Tile tile : world.tiles) {
            if (printed >= 20) break;
            if (tile == null) continue;

            Block overlay = tile.overlay();
            if (overlay == null || overlay == Blocks.air) continue;

            Log.info("[Kryptos] Overlay:");
            Log.info("[Kryptos] @,@", tile.x, tile.y);
            Log.info("[Kryptos] @", overlay.name);
            Log.info("[Kryptos] @", overlay.getClass().getSimpleName());
            Log.info("[Kryptos] @", System.identityHashCode(overlay));

            printed++;
        }

        Log.info("[Kryptos] KryptosBlocks.oreCustom identity:");
        Log.info("[Kryptos] @", System.identityHashCode(KryptosBlocks.oreCustom));
    }

    /**
     * Migration-only step (see {@link #DEBUG_REGENERATE}): removes Kryptos
     * Ore left over from a previous generator run on this exact sector, so
     * the pipeline below can place fresh deposits as if starting clean.
     * <p>
     * Compares by reference to {@code ore} (this mod's one custom ore
     * block), not {@code instanceof OreBlock} -- that distinction is the
     * whole point. Copper/Lead/Coal/Titanium/Thorium/Scrap are also
     * {@code OreBlock} instances; an {@code instanceof} check here would
     * strip them right along with Kryptos, which is exactly what must
     * never happen. Only a tile whose overlay <i>is</i> this specific ore
     * object gets reset, and it's reset to {@code Blocks.air} -- Mindustry's
     * "no overlay" value -- not {@code null}.
     */
    private static int clearPreviousKryptosOre(OreBlock ore) {
        int removed = 0;
        for (Tile tile : world.tiles) {
            if (tile == null) continue;
            if (tile.overlay() == ore) {
                tile.setOverlay(Blocks.air);
                removed++;
            }
        }
        return removed;
    }

    /**
     * Scatters candidate deposit centers on a jittered lattice, keeping
     * only the ones inside an ore-bearing region (per {@code density}'s
     * threshold on the coarse region field) and far enough from every
     * previously accepted seed that their eventual patches cannot touch.
     */
    /**
     * Scatters candidate deposit centers on a jittered lattice, keeping
     * only the ones inside an ore-bearing region (per {@code density}'s
     * threshold on the coarse region field) and far enough from every
     * previously accepted seed that their eventual patches cannot touch.
     *
     * {@code rejectionCountsOut} is diagnostic-only: index 0 tallies cells
     * rejected for jittering out of map bounds, index 1 tallies cells
     * rejected by the region-noise threshold, index 2 tallies cells
     * rejected by {@link #overlapsExisting}. Every branch's accept/reject
     * outcome is unchanged -- only a counter increment was added next to
     * each existing {@code continue}.
     */
    private static List<Seed> collectSeeds(KryptosNoise noise, KryptosSectorRules.Density density, long seed, int[] candidateCellsOut, int[] rejectionCountsOut) {
        List<Seed> accepted = new ArrayList<>();

        int width = world.width();
        int height = world.height();

        for (int gx = 0; gx < width; gx += GRID_SPACING) {
            for (int gy = 0; gy < height; gy += GRID_SPACING) {
                candidateCellsOut[0]++;

                double jitterX = (KryptosNoise.hash01(gx, gy, seed ^ SALT_JITTER_X) - 0.5) * GRID_SPACING * JITTER_FRACTION;
                double jitterY = (KryptosNoise.hash01(gx, gy, seed ^ SALT_JITTER_Y) - 0.5) * GRID_SPACING * JITTER_FRACTION;

                int x = (int) Math.round(gx + GRID_SPACING / 2.0 + jitterX);
                int y = (int) Math.round(gy + GRID_SPACING / 2.0 + jitterY);
                if (x < 0 || x >= width || y < 0 || y >= height) {
                    rejectionCountsOut[0]++; // out-of-bounds jitter
                    continue;
                }

                double region = noise.octaves(x, y, REGION_OCTAVES, REGION_PERSISTENCE, REGION_SCALE);
                if (region <= density.regionThreshold) {
                    rejectionCountsOut[1]++; // regionThreshold
                    continue;
                }

                float radius = (float) (density.minRadius
                    + KryptosNoise.hash01(x, y, seed ^ SALT_RADIUS) * (density.maxRadius - density.minRadius));

                if (overlapsExisting(accepted, x, y, radius)) {
                    rejectionCountsOut[2]++; // overlapsExisting()
                    continue;
                }

                accepted.add(new Seed(x, y, radius));
            }
        }

        return accepted;
    }

    /** Whether a candidate seed sits too close to any already-accepted one for their patches to stay separate. */
    private static boolean overlapsExisting(List<Seed> accepted, int x, int y, float radius) {
        for (Seed other : accepted) {
            double dx = x - other.x;
            double dy = y - other.y;
            double minDist = radius + other.radius + MIN_GAP_BETWEEN_PATCHES;

            if (dx * dx + dy * dy < minDist * minDist) return true;
        }
        return false;
    }

    /**
     * Grows one irregular patch outward from a seed point. Candidate
     * tiles are visited nearest-first so the hard tile cap trims the
     * ragged outer edge instead of leaving random holes in the middle;
     * each tile's effective inclusion radius is perturbed by fine noise
     * so the boundary is never a perfect circle, square, or straight edge.
     *
     * Diagnostic-only: tallies why each candidate offset tile did *not*
     * end up placed (existing OreBlock, invalid floor, outside the
     * noise-perturbed effective radius, or rejected by the interior
     * density falloff) alongside the accepted count, and logs the seed's
     * x/y/radius/placed summary, plus per-floor-name histograms of
     * rejected vs. accepted tiles. None of these counters feed back into
     * the placement decision.
     */
    private static int createPatch(KryptosNoise noise, Seed seed, OreBlock ore, Map<String, Integer> rejectedFloorHistogram, Map<String, Integer> acceptedFloorHistogram) {
        int reach = (int) Math.ceil(seed.radius * (1 + EDGE_PERTURBATION));

        List<int[]> offsets = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                if (dx * dx + dy * dy <= reach * reach) {
                    offsets.add(new int[]{dx, dy});
                }
            }
        }
        offsets.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));

        int visited = 0;
        int skippedOreExists = 0;
        int skippedInvalidFloor = 0;
        int skippedOutsideRadius = 0;
        int skippedDensity = 0;
        double maxNormalizedReached = 0.0;

        // Pass 1: evaluate every visited offset against validity, radius,
        // and the density gate, independent of any placement cap. Tiles
        // that pass everything go into `qualifying`, which stays in the
        // same nearest-first order as `offsets` -- this is what makes the
        // gate reachable, since a tile far out at normalized ~0.9 is
        // judged on its own merits instead of the loop having already
        // stopped looking.
        List<Tile> qualifying = new ArrayList<>();
        List<Double> qualifyingNormalized = new ArrayList<>();

        for (int[] offset : offsets) {
            if (visited >= MAX_VISITED_OFFSETS) break;
            visited++;

            int tx = seed.x + offset[0];
            int ty = seed.y + offset[1];

            Tile tile = world.tile(tx, ty);
            if (tile == null) continue;
            if (tile.overlay() instanceof OreBlock) {
                skippedOreExists++;
                continue;
            }
            if (!KryptosSectorRules.isValidFloor(tile)) {
                skippedInvalidFloor++;
                incrementHistogram(rejectedFloorHistogram, tile.floor().name);
                continue;
            }

            double dist = Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1]);
            double normalized = dist / seed.radius;
            if (normalized > maxNormalizedReached) {
                maxNormalizedReached = normalized;
            }

            double edgeNoise = noise.octaves(tx, ty, EDGE_OCTAVES, EDGE_PERSISTENCE, EDGE_SCALE);
            double effectiveRadius = seed.radius + edgeNoise * seed.radius * EDGE_PERTURBATION;

            if (dist < effectiveRadius) {
                double densityFalloff = 1.0 - normalized * normalized;
                double fill = densityFalloff
                    + noise.octaves(tx, ty, EDGE_OCTAVES, EDGE_PERSISTENCE, EDGE_SCALE * 3) * 0.25;

                if (fill < FILL_THRESHOLD) {
                    skippedDensity++;
                    continue;
                }

                qualifying.add(tile);
                qualifyingNormalized.add(normalized);
            } else {
                skippedOutsideRadius++;
            }
        }

        // Snapshot the qualifying set exactly as it stood before the cap
        // is applied, and bucket it by normalized radius. This is the
        // statistic that answers "is the density gate actually shaping
        // the interior/edge split, or is the cap doing all the work?" --
        // if qualifying tiles are overwhelmingly interior (buckets 0-0.75)
        // and the cap removes only the [0.75, 1.0) / overflow buckets,
        // the gate is mostly invisible and the falloff formula itself
        // needs retuning rather than the pipeline. Bucket 4 (>=1.0)
        // exists because EDGE_PERTURBATION lets effectiveRadius exceed
        // seed.radius, so a tile can still be inside the perturbed
        // boundary with normalized > 1.0.
        int qualifyingBeforeCap = qualifying.size();
        int[] qualifyingRadiusHistogram = new int[5];
        for (double n : qualifyingNormalized) {
            int bucket;
            if (n < 0.25) bucket = 0;
            else if (n < 0.5) bucket = 1;
            else if (n < 0.75) bucket = 2;
            else if (n < 1.0) bucket = 3;
            else bucket = 4;
            qualifyingRadiusHistogram[bucket]++;
        }

        // Pass 2: place ore on the nearest-first qualifying tiles, up to
        // the hard deposit-size cap. Everything beyond MAX_PATCH_TILES in
        // `qualifying` is farther from the seed than everything placed,
        // by construction, so this truncation is still "keep the dense
        // center, trim the edge" -- it's just deciding among tiles that
        // already survived the density gate, instead of deciding before
        // the gate ever ran.
        int placed = Math.min(qualifying.size(), MAX_PATCH_TILES);
        for (int i = 0; i < placed; i++) {
            Tile tile = qualifying.get(i);
            tile.setOverlay(ore);
            incrementHistogram(acceptedFloorHistogram, tile.floor().name);
        }
        int skippedCapped = qualifyingBeforeCap - placed;

        Log.info("[Kryptos] Seed @,@ radius=@ placed=@", seed.x, seed.y, seed.radius, placed);
        Log.info("[Kryptos]   skippedOreExists=@ skippedInvalidFloor=@ skippedOutsideRadius=@ skippedDensity=@ skippedCapped=@ accepted=@",
            skippedOreExists, skippedInvalidFloor, skippedOutsideRadius, skippedDensity, skippedCapped, placed);
        Log.info("[Kryptos]   visitedOffsets=@/@ maxNormalizedDistReached=@",
            visited, offsets.size(), String.format("%.3f", maxNormalizedReached));
        Log.info("[Kryptos]   qualifyingTotal=@ qualifyingBeforeCap=@ removedByCap=@",
            qualifying.size(), qualifyingBeforeCap, skippedCapped);
        Log.info("[Kryptos]   qualifyingByNormalizedRadius: [0,.25)=@ [.25,.5)=@ [.5,.75)=@ [.75,1.0)=@ [>=1.0)=@",
            qualifyingRadiusHistogram[0], qualifyingRadiusHistogram[1], qualifyingRadiusHistogram[2],
            qualifyingRadiusHistogram[3], qualifyingRadiusHistogram[4]);

        return placed;
    }

    /**
     * Deterministic seed built from world size plus the current sector
     * id, so the same map on the same sector always yields identical
     * Kryptos deposits, while different sectors sharing map dimensions
     * still get distinct fields.
     */
    private static long worldSeed() {
        long w = world.width();
        long h = world.height();
        String sector = KryptosSectorRules.currentSectorId();
        long sectorHash = sector != null ? sector.hashCode() : 0;

        return w * 341873128712L + h * 132897987541L + sectorHash * 668265263L;
    }

    /** One accepted deposit center and the radius it will grow to. */
    private static final class Seed {
        final int x;
        final int y;
        final float radius;

        Seed(int x, int y, float radius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
        }
    }
    }
