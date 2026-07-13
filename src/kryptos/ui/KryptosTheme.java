package kryptos.ui;

import arc.graphics.Color;
import mindustry.graphics.Pal;

/**
 * Mindustry's UI leans on a small set of shared {@link Pal} colors for
 * almost every highlight, border, and "[accent]" bbcode tag across the whole
 * game -- not just mod-added panels. Overwriting those in place (not
 * reassigning the field, since other classes already hold references to the
 * same Color object) reskins the entire interface to match Kryptos without
 * touching a single vanilla file.
 */
public class KryptosTheme {
    private static final String ACCENT = "8ff5ff";
    private static final String ACCENT_BACK = "2f8fa8";

    private static boolean applied = false;

    public static void apply() {
        if (applied) {
            return;
        }
        applied = true;

        Pal.accent.set(Color.valueOf(ACCENT));
        Pal.accentBack.set(Color.valueOf(ACCENT_BACK));
    }
}
