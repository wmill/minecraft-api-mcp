package ca.waltermiller.mcpapi.terrain;

import java.util.Arrays;

/**
 * Neighbourhood filters over a heightfield {@code h[x][z]}. Fixed cells keep their own
 * value and are left out of every neighbourhood, so a building's roof never pulls the
 * ground beside it upwards.
 */
public sealed interface HeightFilter {
    int MAX_RADIUS = 16;

    /** Cells either side of the centre that the filter reads. */
    int radius();

    double[][] apply(double[][] h, boolean[][] fixed);

    /** Weighted mean with Gaussian falloff: general-purpose rolling smoothing. */
    record Gaussian(double sigma) implements HeightFilter {
        public int radius() { return Math.min(MAX_RADIUS, (int) Math.ceil(3 * sigma)); }

        public double[][] apply(double[][] h, boolean[][] fixed) {
            double[][] kernel = Kernels.spatial(radius(), sigma);
            return Kernels.convolve(h, fixed, radius(), (cx, cz, x, z) -> kernel[x - cx + radius()][z - cz + radius()]);
        }
    }

    /** Median of the square window: removes spikes and pits while keeping straight cliff lines. */
    record Median(int radius) implements HeightFilter {
        public double[][] apply(double[][] h, boolean[][] fixed) {
            int sx = h.length, sz = h[0].length;
            double[][] out = new double[sx][sz];
            double[] window = new double[(2 * radius + 1) * (2 * radius + 1)];
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; z++) {
                    if (fixed[x][z]) {
                        out[x][z] = h[x][z];
                        continue;
                    }
                    int n = 0;
                    for (int wx = Math.max(0, x - radius); wx <= Math.min(sx - 1, x + radius); wx++) {
                        for (int wz = Math.max(0, z - radius); wz <= Math.min(sz - 1, z + radius); wz++) {
                            if (!fixed[wx][wz]) window[n++] = h[wx][wz];
                        }
                    }
                    Arrays.sort(window, 0, n);
                    out[x][z] = n % 2 == 1 ? window[n / 2] : (window[n / 2 - 1] + window[n / 2]) / 2;
                }
            }
            return out;
        }
    }

    /**
     * Gaussian in space times Gaussian in height difference: neighbours much higher or
     * lower than the centre barely count, so small bumps flatten while big steps survive.
     */
    record Bilateral(double sigma, double sigmaRange) implements HeightFilter {
        public int radius() { return Math.min(MAX_RADIUS, (int) Math.ceil(3 * sigma)); }

        public double[][] apply(double[][] h, boolean[][] fixed) {
            double[][] kernel = Kernels.spatial(radius(), sigma);
            double rangeDenominator = 2 * sigmaRange * sigmaRange;
            return Kernels.convolve(h, fixed, radius(), (cx, cz, x, z) -> {
                double d = h[x][z] - h[cx][cz];
                return kernel[x - cx + radius()][z - cz + radius()] * Math.exp(-d * d / rangeDenominator);
            });
        }
    }
}
