package kryptos.util;

/**
 * Self-contained, deterministic 2D Simplex noise generator.
 *
 * This is a from-scratch implementation of the classic Gustavson/Eastman
 * simplex noise algorithm (public domain technique; no code copied from
 * any licensed source). It intentionally does NOT depend on
 * {@code arc.util.noise.Simplex} or any other engine noise class, so its
 * output -- and therefore where Kryptos Ore appears -- never changes out
 * from under the mod if Arc/Mindustry's internal noise implementation
 * changes between versions.
 *
 * Each instance is built from a single {@code long} seed and produces a
 * fixed permutation table from it, so the same seed always produces the
 * exact same noise field. That's what lets {@link kryptos.world.KryptosOreGenerator}
 * regenerate identical Kryptos veins for the same world every time it
 * loads, without persisting anything to disk.
 */
public final class KryptosNoise {

    private static final int[][] GRAD = {
        {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
    private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;

    // Duplicated permutation table (0..511) so lookups never need a
    // second modulo -- every index used below is already safe once
    // masked with & 255 or & 511.
    private final int[] perm = new int[512];

    public KryptosNoise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;

        // Deterministic Fisher-Yates shuffle driven by a splitmix64-style
        // stream: same seed in -> same permutation table out, always.
        long state = seed;
        for (int i = 255; i > 0; i--) {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = z ^ (z >>> 31);
            int j = (int) ((z & Long.MAX_VALUE) % (i + 1));
            int tmp = p[i];
            p[i] = p[j];
            p[j] = tmp;
        }

        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    /** Single-octave simplex noise, roughly in [-1, 1]. */
    public double raw(double x, double y) {
        double s = (x + y) * F2;
        int i = fastFloor(x + s);
        int j = fastFloor(y + s);

        double t = (i + j) * G2;
        double x0 = x - (i - t);
        double y0 = y - (j - t);

        int i1, j1;
        if (x0 > y0) {
            i1 = 1;
            j1 = 0;
        } else {
            i1 = 0;
            j1 = 1;
        }

        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2;
        double y2 = y0 - 1.0 + 2.0 * G2;

        int ii = i & 255;
        int jj = j & 255;

        double n0 = corner(ii, jj, x0, y0);
        double n1 = corner(ii + i1, jj + j1, x1, y1);
        double n2 = corner(ii + 1, jj + 1, x2, y2);

        return 70.0 * (n0 + n1 + n2);
    }

    /**
     * Fractal (layered) noise: several octaves of {@link #raw} summed at
     * shrinking amplitude and growing frequency, normalized back to
     * roughly [-1, 1]. This is what gives Kryptos veins soft, organic
     * edges instead of the harder look a single octave produces.
     *
     * @param octaves     number of layers to blend (more = finer detail)
     * @param persistence how much each successive octave's amplitude shrinks (0..1)
     * @param scale       base frequency; smaller values produce larger, smoother blobs
     */
    public double octaves(double x, double y, int octaves, double persistence, double scale) {
        double total = 0;
        double amplitude = 1;
        double frequency = scale;
        double max = 0;

        for (int i = 0; i < octaves; i++) {
            total += raw(x * frequency, y * frequency) * amplitude;
            max += amplitude;
            amplitude *= persistence;
            frequency *= 2;
        }

        return max == 0 ? 0 : total / max;
    }

    private double corner(int gi, int gj, double x, double y) {
        double t = 0.5 - x * x - y * y;
        if (t < 0) return 0;

        int gradIndex = perm[(gi & 255) + perm[gj & 255]] & 7;
        int[] g = GRAD[gradIndex];

        t *= t;
        return t * t * (g[0] * x + g[1] * y);
    }

    private static int fastFloor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}