package ca.waltermiller.mcpapi.terrain;

/** Shared convolution helpers for {@link HeightFilter}. */
final class Kernels {
    private Kernels() {}

    interface Weight {
        double of(int cx, int cz, int x, int z);
    }

    static double[][] spatial(int r, double sigma) {
        double[][] k = new double[2 * r + 1][2 * r + 1];
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                k[dx + r][dz + r] = Math.exp(-(dx * dx + dz * dz) / (2 * sigma * sigma));
            }
        }
        return k;
    }

    /** Normalised convolution over non-fixed neighbours; the centre always contributes. */
    static double[][] convolve(double[][] h, boolean[][] fixed, int r, Weight weight) {
        int sx = h.length, sz = h[0].length;
        double[][] out = new double[sx][sz];
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (fixed[x][z]) {
                    out[x][z] = h[x][z];
                    continue;
                }
                double sum = 0, total = 0;
                for (int wx = Math.max(0, x - r); wx <= Math.min(sx - 1, x + r); wx++) {
                    for (int wz = Math.max(0, z - r); wz <= Math.min(sz - 1, z + r); wz++) {
                        if (fixed[wx][wz]) continue;
                        double w = weight.of(x, z, wx, wz);
                        sum += w * h[wx][wz];
                        total += w;
                    }
                }
                out[x][z] = sum / total;
            }
        }
        return out;
    }
}
