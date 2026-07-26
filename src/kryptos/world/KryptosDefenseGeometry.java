package kryptos.world;

import mindustry.Vars;
import mindustry.ai.Pathfinder;
import mindustry.game.Team;
import mindustry.world.Tile;

import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;

/**
 * Classifies the terrain around each enemy spawn point as a chokepoint,
 * open ground, or somewhere in between, by tracing the same ground
 * flowfield ({@link Pathfinder}) that enemy units themselves follow to
 * reach the core -- the identical technique
 * {@link kryptos.ui.KryptosPathIndicator} already uses to draw the
 * on-screen route preview, repurposed here to produce a classification
 * instead of a line.
 *
 * The idea: fan a wide spread of sample points out across the spawn's
 * approach line (wider than any defense line would actually be built),
 * then trace each one forward a short distance along the real flowfield.
 * <ul>
 *   <li>If the terrain is a narrow pass, most traces converge into a
 *   tight band, and some samples may not even have a valid path at all
 *   (they started on unwalkable ground) -- {@link Style#CHOKEPOINT}.</li>
 *   <li>If the terrain is open, the traces stay roughly as spread out as
 *   they started -- {@link Style#OPEN}.</li>
 *   <li>Anything in between -- {@link Style#HYBRID}.</li>
 * </ul>
 *
 * {@link kryptos.automation.KryptosPerimeterDefense} never touches
 * Tile/Pathfinder internals for this itself -- it only ever asks
 * {@link #classify(Tile, Tile)} and reacts to the returned {@link Style},
 * the same "single source of truth, ask-don't-inspect" pattern
 * {@link KryptosSectorRules} already established for ore rules.
 *
 * Classification is re-run every scan rather than cached forever, same
 * philosophy as PerimeterDefense's own re-scanning: if the player later
 * walls off a previously-open flank (or something blows a new gap through
 * a cliff), the next scan picks up the new shape automatically.
 */
public final class KryptosDefenseGeometry {

    /** How a spawn's approach terrain behaves, from the enemy flowfield's point of view. */
    public enum Style {
        /** Terrain naturally funnels enemies into a narrow lane -- concentrate firepower. */
        CHOKEPOINT,
        /** Neither strongly funnels nor stays fully spread -- moderate widening. */
        HYBRID,
        /** Enemies can approach across a broad front -- spread coverage, no natural bottleneck to lean on. */
        OPEN,
        /** No pathfinder data available yet, or not enough valid samples to judge -- caller should fall back to its own safe default. */
        UNKNOWN
    }

    // How far out (perpendicular to the spawn->core axis) the sample fan
    // reaches, in tiles either side of center, and the spacing between
    // samples. Deliberately wider than KryptosPerimeterDefense's own
    // widest line (its LINE_HALF_WIDTH_OPEN) -- this needs to test whether
    // terrain funnels a spread WIDER than anything we'd build anyway,
    // otherwise a sector that's merely "as wide as the line" would never
    // register as open.
    private static final int SAMPLE_HALF_WIDTH = 12;
    private static final int SAMPLE_STEP = 2;

    // How many flowfield steps each sample is traced forward. Short on
    // purpose -- this only needs to see whether nearby lanes merge or
    // stay apart, not trace all the way to the core (that's
    // KryptosPathIndicator's job, with a much larger step budget).
    private static final int TRACE_STEPS = 10;

    // Extra margin beyond dropZoneRadius, in tiles. Kept identical to
    // KryptosPerimeterDefense's own SAFETY_MARGIN_TILES so the sample fan
    // sits at the same radius the defense line itself gets built on -- we
    // want to classify the terrain where the line actually goes, not
    // somewhere else nearby that might look different.
    private static final int SAFETY_MARGIN_TILES = 3;

    // If at least this fraction of the wide sample fan can't path at all
    // (started on unwalkable ground -- rock, cliff, deep water), the
    // terrain counts as chokepoint-narrow regardless of how the samples
    // that DID path behaved.
    private static final float BLOCKED_FRACTION_CHOKEPOINT = 0.5f;

    // convergenceRatio = spread of trace endpoints / spread of starting
    // points. Low ratio = traces merged together = chokepoint. High ratio
    // = traces stayed apart = open. Between the two = hybrid.
    private static final float CONVERGENCE_CHOKEPOINT = 0.35f;
    private static final float CONVERGENCE_OPEN = 0.65f;

    private KryptosDefenseGeometry() {}

    /**
     * Classifies the approach terrain between {@code spawn} and
     * {@code coreTile}. Returns {@link Style#UNKNOWN} if there isn't
     * enough pathfinder data yet to judge -- callers should treat that
     * the same as their current safe default, not as "open."
     */
    public static Style classify(Tile spawn, Tile coreTile) {
        if (spawn == null || coreTile == null) return Style.UNKNOWN;
        if (Vars.pathfinder == null) return Style.UNKNOWN;
        if (Vars.state == null || Vars.state.rules == null) return Style.UNKNOWN;

        float dx = coreTile.x - spawn.x;
        float dy = coreTile.y - spawn.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return Style.UNKNOWN;
        dx /= dist;
        dy /= dist;
        float px = -dy;
        float py = dx;

        float radiusTiles = Vars.state.rules.dropZoneRadius / tilesize + SAFETY_MARGIN_TILES;
        float centerX = spawn.x + dx * radiusTiles;
        float centerY = spawn.y + dy * radiusTiles;

        Team enemyTeam = Vars.state.rules.waveTeam;
        Pathfinder.Flowfield field = Vars.pathfinder.getField(enemyTeam, Pathfinder.costGround, Pathfinder.fieldCore);
        if (field == null) return Style.UNKNOWN;

        int totalSamples = 0;
        int validSamples = 0;
        float minProj = Float.MAX_VALUE;
        float maxProj = -Float.MAX_VALUE;

        for (int offset = -SAMPLE_HALF_WIDTH; offset <= SAMPLE_HALF_WIDTH; offset += SAMPLE_STEP) {
            totalSamples++;

            int lx = Math.round(centerX + px * offset);
            int ly = Math.round(centerY + py * offset);
            Tile start = world.tile(lx, ly);
            if (start == null) continue; // off the map edge

            Tile endTile = traceForward(start, field);
            if (endTile == null || endTile == start) continue; // couldn't move at all -- unwalkable start

            validSamples++;

            // Project the endpoint onto the perpendicular axis relative to
            // the sample fan's own center, so this measures "how spread
            // out did the traces end up", not "how far did they travel
            // toward the core".
            float ex = endTile.x - centerX;
            float ey = endTile.y - centerY;
            float proj = ex * px + ey * py;

            if (proj < minProj) minProj = proj;
            if (proj > maxProj) maxProj = proj;
        }

        if (totalSamples == 0) return Style.UNKNOWN;

        float blockedFraction = 1f - ((float) validSamples / totalSamples);
        if (blockedFraction >= BLOCKED_FRACTION_CHOKEPOINT) return Style.CHOKEPOINT;
        if (validSamples < 2) return Style.UNKNOWN; // not enough survivors to measure a spread at all

        float initialSpread = SAMPLE_HALF_WIDTH * 2f;
        float finalSpread = maxProj - minProj;
        float convergenceRatio = finalSpread / initialSpread;

        if (convergenceRatio <= CONVERGENCE_CHOKEPOINT) return Style.CHOKEPOINT;
        if (convergenceRatio >= CONVERGENCE_OPEN) return Style.OPEN;
        return Style.HYBRID;
    }

    /**
     * Walks {@code field} forward from {@code start} for a fixed step
     * budget, returning the furthest tile reached. Returns {@code start}
     * itself if the very first step fails -- the caller treats that as
     * "couldn't path at all" (see the {@code endTile == start} check
     * above), which is exactly right whether that's because this tile has
     * no flowfield entry or because it's genuinely a dead end.
     */
    private static Tile traceForward(Tile start, Pathfinder.Flowfield field) {
        Tile current = start;
        for (int i = 0; i < TRACE_STEPS; i++) {
            Tile next = Vars.pathfinder.getTargetTile(current, field);
            if (next == null || next == current) break;
            current = next;
        }
        return current;
    }
}
