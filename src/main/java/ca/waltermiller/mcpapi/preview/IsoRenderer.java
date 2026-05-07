package ca.waltermiller.mcpapi.preview;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 2:1 isometric voxel renderer ported from nbt-image-gen's render_isometric.
 * +X goes right-and-down on screen; +Z goes left-and-down; +Y goes up. Visible
 * faces per cube: top (+Y), left (+Z), right (+X). Painter's order iterates y
 * ascending so higher voxels paint over lower ones; within a layer the 2:1
 * projection guarantees no overlap.
 */
public final class IsoRenderer {

    public static final int DEFAULT_SCALE = 6;

    private IsoRenderer() {}

    public static byte[] renderPng(BlockGrid grid, int scale) throws IOException {
        return renderPng(grid, scale, PreviewViewDirection.SOUTH);
    }

    public static byte[] renderPng(BlockGrid grid, int scale, PreviewViewDirection viewDirection) throws IOException {
        BufferedImage img = render(grid, scale, viewDirection);
        if (viewDirection != PreviewViewDirection.SOUTH) {
            img = render(grid, scale, viewDirection);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }

    static BufferedImage render(BlockGrid grid, int scale) {
        return render(grid, scale, PreviewViewDirection.SOUTH);
    }

    static BufferedImage render(BlockGrid grid, int scale, PreviewViewDirection viewDirection) {
        int w = Math.max(grid.width(), 1);
        int h = Math.max(grid.height(), 1);
        int d = Math.max(grid.depth(), 1);

        int imgWUnits = 2 * (w + d);
        int imgHUnits = w + d + 2 * h;
        int offX = 2 * d;
        int offY = 2 * h;

        int imgW = imgWUnits * scale;
        int imgH = imgHUnits * scale;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

            if (grid.isEmpty()) {
                return img;
            }

            for (int y = 0; y < h; y++) {
                for (int z = d - 1; z >= 0; z--) {
                    for (int x = 0; x < w; x++) {
                        String id = grid.blockAt(grid.minX() + x, grid.minY() + y, grid.minZ() + z);
                        if (id == null) continue;

                        int[] rgb = Palette.colorFor(id);
                        Color topC = new Color(rgb[0], rgb[1], rgb[2]);
                        Color leftC = scaleColor(rgb, 0.85);
                        Color rightC = scaleColor(rgb, 0.65);

                        int[] bbl = project(x, y, z, w, d, viewDirection, offX, offY, scale);
                        int[] bbr = project(x + 1, y, z, w, d, viewDirection, offX, offY, scale);
                        int[] bfl = project(x, y, z + 1, w, d, viewDirection, offX, offY, scale);
                        int[] bfr = project(x + 1, y, z + 1, w, d, viewDirection, offX, offY, scale);
                        int[] tbl = project(x, y + 1, z, w, d, viewDirection, offX, offY, scale);
                        int[] tbr = project(x + 1, y + 1, z, w, d, viewDirection, offX, offY, scale);
                        int[] tfl = project(x, y + 1, z + 1, w, d, viewDirection, offX, offY, scale);
                        int[] tfr = project(x + 1, y + 1, z + 1, w, d, viewDirection, offX, offY, scale);

                        if (grid.isAir(grid.minX() + x, grid.minY() + y + 1, grid.minZ() + z)) {
                            g.setColor(topC);
                            fillPoly(g, tbl, tbr, tfr, tfl);
                        }
                        if (grid.isAir(grid.minX() + x, grid.minY() + y, grid.minZ() + z + 1)) {
                            g.setColor(leftC);
                            fillPoly(g, bfl, bfr, tfr, tfl);
                        }
                        if (grid.isAir(grid.minX() + x + 1, grid.minY() + y, grid.minZ() + z)) {
                            g.setColor(rightC);
                            fillPoly(g, bbr, bfr, tfr, tbr);
                        }
                    }
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    private static int[] project(
            int px,
            int py,
            int pz,
            int width,
            int depth,
            PreviewViewDirection viewDirection,
            int offX,
            int offY,
            int scale) {
        int[] rotated = rotate(px, pz, width, depth, viewDirection);
        return project(rotated[0], py, rotated[1], offX, offY, scale);
    }

    private static int[] project(int px, int py, int pz, int offX, int offY, int scale) {
        int sx = (px - pz) * 2 + offX;
        int sy = (px + pz) - 2 * py + offY;
        return new int[] {sx * scale, sy * scale};
    }

    private static int[] rotate(int px, int pz, int width, int depth, PreviewViewDirection viewDirection) {
        return switch (viewDirection) {
            case SOUTH -> new int[] {px, pz};
            case WEST -> new int[] {pz, width - px};
            case NORTH -> new int[] {width - px, depth - pz};
            case EAST -> new int[] {depth - pz, px};
        };
    }

    private static void fillPoly(Graphics2D g, int[]... points) {
        int[] xs = new int[points.length];
        int[] ys = new int[points.length];
        for (int i = 0; i < points.length; i++) {
            xs[i] = points[i][0];
            ys[i] = points[i][1];
        }
        g.fillPolygon(xs, ys, points.length);
    }

    private static Color scaleColor(int[] rgb, double factor) {
        return new Color(
                clamp((int) (rgb[0] * factor)),
                clamp((int) (rgb[1] * factor)),
                clamp((int) (rgb[2] * factor)));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
