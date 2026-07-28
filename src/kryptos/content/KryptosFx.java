package kryptos.content;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import mindustry.entities.Effect;

/**
 * Custom Kryptos visual effects. Replaces vanilla teleport bursts (a plain
 * expanding circle, see Fx.teleport / Fx.teleportOut) with a "scanning"
 * sci-fi look matching the mod's Glacial Precursor / Lost Technology
 * aesthetic: a hex outline with a horizontal scan-line sweeping across it,
 * instead of a generic radial burst.
 */
public class KryptosFx {

    public static final Effect

    /** Played at the source block: item "dematerializes" -- scan line
     *  sweeps downward across a shrinking hex outline. Sized to stay within
     *  a 1x1 (8-unit) block footprint instead of spilling past its edges. */
    scanTeleportOut = new Effect(26f, 14f, e -> {
        Draw.color(e.color);
        float size = 2.2f + e.fout() * 1.8f; // max radius 4 -> fits an 8-wide tile

        // Hex outline, fading out as the item leaves.
        Draw.alpha(e.fout());
        Lines.stroke(1.1f);
        Lines.poly(e.x, e.y, 6, size, 0f);

        // Scan line sweeping top-to-bottom across the hex, synced to e.fin().
        float sweepY = Mathf.lerp(size, -size, e.fin());
        Draw.alpha(1f);
        Lines.stroke(1.3f * e.fslope() + 0.25f);
        Lines.line(e.x - size, e.y + sweepY, e.x + size, e.y + sweepY);
    }),

    /** Played at the destination block: item "rematerializes" -- scan line
     *  sweeps upward across a growing hex outline. Sized to stay within a
     *  1x1 (8-unit) block footprint instead of spilling past its edges. */
    scanTeleport = new Effect(30f, 16f, e -> {
        Draw.color(e.color);
        float size = 2f + e.fin() * 2f; // max radius 4 -> fits an 8-wide tile

        // Hex outline, fading in as the item arrives.
        Draw.alpha(e.fin());
        Lines.stroke(1.2f);
        Lines.poly(e.x, e.y, 6, size, 0f);

        // Scan line sweeping bottom-to-top across the hex, synced to e.fin().
        float sweepY = Mathf.lerp(-size, size, e.fin());
        Draw.alpha(1f);
        Lines.stroke(1.3f * e.fslope() + 0.25f);
        Lines.line(e.x - size, e.y + sweepY, e.x + size, e.y + sweepY);
    });

    private KryptosFx() {}
}
