package ca.waltermiller.mcpapi.preview;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Top-down terrain maps (north up, +X to the right) designed to be read by a vision
 * model rather than admired: a greyscale hillshaded relief map whose heights are given
 * by printed contour labels and spot heights, and a categorical surface-material map.
 * Both carry world-coordinate grid labels so a spot on the image translates directly
 * to block coordinates. Heights are the heightmap's first-air Y (the standing level).
 */
public final class TerrainMapRenderer {

    public static final int MIN_PX_PER_BLOCK = 1;
    public static final int MAX_PX_PER_BLOCK = 16;
    public static final int MIN_CONTOUR_INTERVAL = 1;
    public static final int MAX_CONTOUR_INTERVAL = 100;

    static final int TARGET_MAP_PX = 768;
    static final int[] CONTOUR_STEPS = {1, 2, 5, 10, 20, 50};
    static final int MAX_CONTOUR_LINES = 15;
    static final int INDEX_EVERY = 5;
    static final int[] GRID_STEPS = {4, 8, 16, 32, 64, 128, 256, 512, 1024};
    static final int MIN_GRID_PX = 64;

    static final int MARGIN_LEFT = 52;
    static final int MARGIN_TOP = 54;
    static final int MARGIN_RIGHT = 14;
    static final int MARGIN_BOTTOM = 34;
    static final int PANEL_WIDTH = 170;
    static final int MIN_IMAGE_HEIGHT = 360;
    static final int MIN_IMAGE_WIDTH = 520;

    static final int[] GROUND_SEARCH_RADII = {8, 16, 32};
    static final int MAX_PAVING_RISE = 1;
    static final int KEY_BAR_HEIGHT = 150;
    static final int MIN_KEY_TICK_GAP = 16;

    private static final int MAX_CONTOUR_LABELS = 60;
    private static final int SAME_LEVEL_LABEL_SPACING = 140;

    private static final Color BACKGROUND = new Color(0xF7F7F5);
    private static final Color INK = new Color(0x202020);
    private static final Color GRID = new Color(30, 60, 150, 120);
    private static final Color MINOR_CONTOUR = new Color(40, 40, 40, 150);
    private static final Color INDEX_CONTOUR = new Color(0, 0, 0, 235);
    private static final Color CATEGORY_EDGE = new Color(0, 0, 0, 110);
    private static final Color LABEL_BOX = new Color(255, 255, 255, 230);
    private static final int WATER_TINT = 0x9EC9F5;

    private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final Font AXIS_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    private static final Font INDEX_LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 10);

    private TerrainMapRenderer() {}

    /** Null fields pick automatic values; at least one layer should be requested. */
    public record Options(Integer pxPerBlock, Integer contourInterval, boolean relief, boolean surface) {}

    /** Coverage maps category label to fraction of columns, largest first; empty without surface blocks. */
    public record Meta(int pxPerBlock, int contourInterval, int gridStep, int minY, int medianY, int maxY,
                       Map<String, Double> coverage, boolean labels) {}

    /** PNGs for the requested layers (null when not requested) plus the numbers behind them. */
    public record Result(byte[] reliefPng, byte[] surfacePng, Meta meta) {}

    /**
     * @param heights       heights[x][z] relative to the origin, rectangular
     * @param surfaceBlocks block ids of the top block per column (same shape), or null
     */
    public static Result render(int[][] heights, String[][] surfaceBlocks, int originX, int originZ,
                                Options options) throws IOException {
        if (heights == null || heights.length == 0 || heights[0] == null || heights[0].length == 0) {
            throw new IllegalArgumentException("Terrain map needs at least one column");
        }
        int sizeX = heights.length;
        int sizeZ = heights[0].length;
        for (int[] column : heights) {
            if (column == null || column.length != sizeZ) {
                throw new IllegalArgumentException("Terrain map heights must be rectangular");
            }
        }
        SurfaceCategory[][] categories = surfaceBlocks == null
            ? null : splitBuilt(heights, classify(surfaceBlocks, sizeX, sizeZ));

        int[] sorted = Arrays.stream(heights).flatMapToInt(Arrays::stream).sorted().toArray();
        int minY = sorted[0];
        int maxY = sorted[sorted.length - 1];
        int medianY = sorted[sorted.length / 2];

        int px = options.pxPerBlock() != null ? options.pxPerBlock() : autoPxPerBlock(sizeX, sizeZ);
        int interval = options.contourInterval() != null
            ? options.contourInterval() : autoContourInterval(minY, maxY);
        Layout layout = new Layout(originX, originZ, sizeX, sizeZ, px, gridStep(px));
        double[][] shade = hillshade(heights);

        byte[] relief = null;
        byte[] surface = null;
        boolean labels = true;
        if (options.relief()) {
            Canvas canvas = new Canvas(layout);
            labels = canvas.textOk;
            drawRelief(canvas, heights, categories, shade, minY, maxY, interval);
            relief = canvas.toPng();
        }
        Map<SurfaceCategory, Integer> counts = categories == null ? Map.of() : countCategories(categories);
        if (options.surface() && categories != null) {
            Canvas canvas = new Canvas(layout);
            labels &= canvas.textOk;
            drawSurface(canvas, categories, shade, counts);
            surface = canvas.toPng();
        }

        Meta meta = new Meta(px, interval, layout.gridStep, minY, medianY, maxY,
            coverage(counts, sizeX * sizeZ), labels);
        return new Result(relief, surface, meta);
    }

    static int autoPxPerBlock(int sizeX, int sizeZ) {
        return clamp(TARGET_MAP_PX / Math.max(sizeX, sizeZ), MIN_PX_PER_BLOCK, MAX_PX_PER_BLOCK);
    }

    /** Smallest "nice" interval that keeps the map at or under {@link #MAX_CONTOUR_LINES} lines. */
    static int autoContourInterval(int minY, int maxY) {
        int range = maxY - minY;
        for (int step : CONTOUR_STEPS) {
            if (range / step <= MAX_CONTOUR_LINES) {
                return step;
            }
        }
        return CONTOUR_STEPS[CONTOUR_STEPS.length - 1];
    }

    /** Smallest world-aligned grid spacing whose lines are at least {@link #MIN_GRID_PX} apart. */
    static int gridStep(int px) {
        for (int step : GRID_STEPS) {
            if (step * px >= MIN_GRID_PX) {
                return step;
            }
        }
        return GRID_STEPS[GRID_STEPS.length - 1];
    }

    /** Highest multiple of {@code step} in (min(a,b), max(a,b)], or null when the edge crosses none. */
    static Integer crossedLevel(int a, int b, int step) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        int level = Math.floorDiv(hi, step) * step;
        return level > lo ? level : null;
    }

    /**
     * Brightness factor per column for a light from the north-west, 45 degrees up:
     * 1.0 on flat ground, above 1 on slopes facing the light, below 1 facing away.
     */
    static double[][] hillshade(int[][] h) {
        int sx = h.length;
        int sz = h[0].length;
        final double lx = -0.5, ly = Math.sqrt(0.5), lz = -0.5;
        double[][] out = new double[sx][sz];
        for (int x = 0; x < sx; x++) {
            int x0 = Math.max(x - 1, 0), x1 = Math.min(x + 1, sx - 1);
            for (int z = 0; z < sz; z++) {
                int z0 = Math.max(z - 1, 0), z1 = Math.min(z + 1, sz - 1);
                double dx = x1 == x0 ? 0 : (h[x1][z] - h[x0][z]) / (double) (x1 - x0);
                double dz = z1 == z0 ? 0 : (h[x][z1] - h[x][z0]) / (double) (z1 - z0);
                double len = Math.sqrt(dx * dx + 1 + dz * dz);
                double dot = (-dx * lx + ly - dz * lz) / len;
                out[x][z] = Math.max(0.45, Math.min(1.35, Math.max(0, dot) / ly));
            }
        }
        return out;
    }

    private static SurfaceCategory[][] classify(String[][] blocks, int sizeX, int sizeZ) {
        if (blocks.length != sizeX) {
            throw new IllegalArgumentException("Surface blocks must match the heightmap size");
        }
        SurfaceCategory[][] out = new SurfaceCategory[sizeX][sizeZ];
        for (int x = 0; x < sizeX; x++) {
            if (blocks[x] == null || blocks[x].length != sizeZ) {
                throw new IllegalArgumentException("Surface blocks must match the heightmap size");
            }
            for (int z = 0; z < sizeZ; z++) {
                out[x][z] = SurfaceCategory.classify(blocks[x][z]);
            }
        }
        return out;
    }

    /**
     * Re-labels built columns standing at most {@link #MAX_PAVING_RISE} above the nearby
     * natural ground as {@link SurfaceCategory#PAVING}; the rest stay
     * {@link SurfaceCategory#BUILT} (buildings). Ground is the median height of natural,
     * non-tree columns within the smallest search radius that has any, falling back to
     * all natural columns, or to the lowest column when the whole area is built.
     */
    static SurfaceCategory[][] splitBuilt(int[][] h, SurfaceCategory[][] categories) {
        int sx = h.length;
        int sz = h[0].length;
        List<Integer> allGround = new ArrayList<>();
        int lowest = Integer.MAX_VALUE;
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                lowest = Math.min(lowest, h[x][z]);
                if (isGround(categories[x][z])) allGround.add(h[x][z]);
            }
        }
        int fallback = allGround.isEmpty() ? lowest : median(allGround);

        SurfaceCategory[][] out = new SurfaceCategory[sx][];
        List<Integer> window = new ArrayList<>();
        for (int x = 0; x < sx; x++) {
            out[x] = categories[x].clone();
            for (int z = 0; z < sz; z++) {
                if (categories[x][z] != SurfaceCategory.BUILT) continue;
                int ground = fallback;
                for (int r : GROUND_SEARCH_RADII) {
                    window.clear();
                    for (int wx = Math.max(0, x - r); wx <= Math.min(sx - 1, x + r); wx++) {
                        for (int wz = Math.max(0, z - r); wz <= Math.min(sz - 1, z + r); wz++) {
                            if (isGround(categories[wx][wz])) window.add(h[wx][wz]);
                        }
                    }
                    if (!window.isEmpty()) {
                        ground = median(window);
                        break;
                    }
                }
                if (h[x][z] - ground <= MAX_PAVING_RISE) out[x][z] = SurfaceCategory.PAVING;
            }
        }
        return out;
    }

    private static boolean isGround(SurfaceCategory c) {
        return c != SurfaceCategory.BUILT && c != SurfaceCategory.PAVING
            && c != SurfaceCategory.TREES && c != SurfaceCategory.NONE;
    }

    private static int median(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static Map<SurfaceCategory, Integer> countCategories(SurfaceCategory[][] categories) {
        Map<SurfaceCategory, Integer> counts = new EnumMap<>(SurfaceCategory.class);
        for (SurfaceCategory[] column : categories) {
            for (SurfaceCategory c : column) {
                counts.merge(c, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, Double> coverage(Map<SurfaceCategory, Integer> counts, int total) {
        Map<String, Double> out = new LinkedHashMap<>();
        sortedByCount(counts).forEach(c ->
            out.put(c.label(), Math.round(counts.get(c) * 1000.0 / total) / 1000.0));
        return out;
    }

    private static List<SurfaceCategory> sortedByCount(Map<SurfaceCategory, Integer> counts) {
        List<SurfaceCategory> present = new ArrayList<>(counts.keySet());
        present.sort((a, b) -> Integer.compare(counts.get(b), counts.get(a)));
        return present;
    }

    // ---- relief layer ----

    private static void drawRelief(Canvas c, int[][] h, SurfaceCategory[][] categories, double[][] shade,
                                   int minY, int maxY, int interval) {
        Layout l = c.layout;
        Graphics2D g = c.g;
        boolean anyWater = false;
        for (int x = 0; x < l.sizeX; x++) {
            for (int z = 0; z < l.sizeZ; z++) {
                boolean water = categories != null && categories[x][z] == SurfaceCategory.WATER;
                anyWater |= water;
                // Half-strength shading: shape cues without hiding the "lighter = higher" ramp.
                double f = 1 + 0.5 * (shade[x][z] - 1);
                int grey = clamp((int) Math.round(rampGrey(h[x][z], minY, maxY) * f), 0, 255);
                int rgb = (grey << 16) | (grey << 8) | grey;
                g.setColor(new Color(water ? blend(rgb, WATER_TINT, 0.55) : rgb));
                g.fillRect(l.mapX(x), l.mapY(z), l.px, l.px);
            }
        }

        List<LabelCandidate> index = new ArrayList<>();
        List<LabelCandidate> minor = new ArrayList<>();
        BasicStroke thin = new BasicStroke(1f);
        BasicStroke bold = new BasicStroke(l.px >= 4 ? 2f : 1f);
        int indexStep = interval * INDEX_EVERY;
        for (int x = 0; x < l.sizeX; x++) {
            for (int z = 0; z < l.sizeZ; z++) {
                if (x + 1 < l.sizeX) {
                    int lx = l.mapX(x + 1);
                    int y0 = l.mapY(z);
                    contourEdge(g, h[x][z], h[x + 1][z], interval, indexStep, thin, bold,
                        lx, y0, lx, y0 + l.px, lx, y0 + l.px / 2, index, minor);
                }
                if (z + 1 < l.sizeZ) {
                    int ly = l.mapY(z + 1);
                    int x0 = l.mapX(x);
                    contourEdge(g, h[x][z], h[x][z + 1], interval, indexStep, thin, bold,
                        x0, ly, x0 + l.px, ly, x0 + l.px / 2, ly, index, minor);
                }
            }
        }

        drawGrid(c);
        List<Rectangle> occupied = drawSpotHeights(c, h);
        placeContourLabels(c, index, minor, occupied);
        drawFrame(c, "Relief: contours + spot heights (standing Y)");
        drawReliefKey(c, minY, maxY, interval, anyWater);
    }

    private static void contourEdge(Graphics2D g, int a, int b, int interval, int indexStep,
                                    BasicStroke thin, BasicStroke bold,
                                    int x1, int y1, int x2, int y2, int cx, int cy,
                                    List<LabelCandidate> index, List<LabelCandidate> minor) {
        Integer level = crossedLevel(a, b, interval);
        if (level == null) return;
        Integer indexLevel = crossedLevel(a, b, indexStep);
        g.setColor(indexLevel != null ? INDEX_CONTOUR : MINOR_CONTOUR);
        g.setStroke(indexLevel != null ? bold : thin);
        g.drawLine(x1, y1, x2, y2);
        if (indexLevel != null) {
            index.add(new LabelCandidate(cx, cy, indexLevel, true));
        } else {
            minor.add(new LabelCandidate(cx, cy, level, false));
        }
    }

    /** One labelled spot height at the centre of each grid cell that falls inside the map. */
    private static List<Rectangle> drawSpotHeights(Canvas c, int[][] h) {
        Layout l = c.layout;
        List<Rectangle> occupied = new ArrayList<>();
        int s = l.gridStep;
        int dot = Math.max(3, Math.min(5, l.px));
        for (int wx = Math.floorDiv(l.originX, s) * s + s / 2; wx < l.originX + l.sizeX; wx += s) {
            if (wx < l.originX) continue;
            for (int wz = Math.floorDiv(l.originZ, s) * s + s / 2; wz < l.originZ + l.sizeZ; wz += s) {
                if (wz < l.originZ) continue;
                int x = wx - l.originX;
                int z = wz - l.originZ;
                int cx = l.mapX(x) + l.px / 2;
                int cy = l.mapY(z) + l.px / 2;
                c.g.setColor(INK);
                c.g.fillOval(cx - dot / 2, cy - dot / 2, dot, dot);
                Rectangle r = c.haloText(Integer.toString(h[x][z]), LABEL_FONT, cx + dot / 2 + 2, cy + 4);
                if (r != null) {
                    occupied.add(r.union(new Rectangle(cx - dot / 2, cy - dot / 2, dot, dot)));
                }
            }
        }
        return occupied;
    }

    private static void placeContourLabels(Canvas c, List<LabelCandidate> index, List<LabelCandidate> minor,
                                           List<Rectangle> occupied) {
        if (!c.textOk) return;
        Layout l = c.layout;
        Rectangle map = new Rectangle(l.mapX(0), l.mapY(0), l.mapW(), l.mapH());
        Random random = new Random(42);
        Collections.shuffle(index, random);
        Collections.shuffle(minor, random);
        List<LabelCandidate> placed = new ArrayList<>();
        for (List<LabelCandidate> pass : List.of(index, minor)) {
            for (LabelCandidate cand : pass) {
                if (placed.size() >= MAX_CONTOUR_LABELS) return;
                Font font = cand.index ? INDEX_LABEL_FONT : LABEL_FONT;
                String text = Integer.toString(cand.level);
                FontMetrics fm = c.g.getFontMetrics(font);
                int w = fm.stringWidth(text) + 4;
                int hgt = fm.getAscent() + 2;
                Rectangle box = new Rectangle(cand.x - w / 2, cand.y - hgt / 2, w, hgt);
                if (!map.contains(box) || collides(box, occupied) || nearSameLevel(cand, placed)) continue;
                c.g.setColor(LABEL_BOX);
                c.g.fillRect(box.x, box.y, box.width, box.height);
                c.g.setColor(INK);
                c.g.setFont(font);
                c.g.drawString(text, box.x + 2, box.y + fm.getAscent());
                occupied.add(box);
                placed.add(cand);
            }
        }
    }

    private static boolean collides(Rectangle box, List<Rectangle> occupied) {
        Rectangle padded = new Rectangle(box.x - 6, box.y - 6, box.width + 12, box.height + 12);
        for (Rectangle r : occupied) {
            if (padded.intersects(r)) return true;
        }
        return false;
    }

    private static boolean nearSameLevel(LabelCandidate cand, List<LabelCandidate> placed) {
        for (LabelCandidate p : placed) {
            if (p.level == cand.level && Math.hypot(p.x - cand.x, p.y - cand.y) < SAME_LEVEL_LABEL_SPACING) {
                return true;
            }
        }
        return false;
    }

    private static void drawReliefKey(Canvas c, int minY, int maxY, int interval, boolean anyWater) {
        Layout l = c.layout;
        Graphics2D g = c.g;
        int x = l.panelX();
        int y = drawNorthArrow(c, x, l.mapY(0));

        int barTop = y + 18;
        int barH = KEY_BAR_HEIGHT;
        c.text("Elevation (Y)", AXIS_FONT, x, barTop - 6);
        for (int i = 0; i < barH; i++) {
            double level = maxY - (maxY - minY) * (i / (double) (barH - 1));
            int grey = clamp((int) Math.round(rampGrey(level, minY, maxY)), 0, 255);
            g.setColor(new Color(grey, grey, grey));
            g.drawLine(x, barTop + i, x + 14, barTop + i);
        }
        g.setColor(INK);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(x, barTop, 14, barH - 1);

        for (int level : keyTicks(minY, maxY, interval)) {
            int ty = barTop + keyTickOffset(level, minY, maxY);
            g.setColor(INK);
            g.drawLine(x + 14, ty, x + 18, ty);
            c.text(Integer.toString(level), AXIS_FONT, x + 21, ty + 4);
        }

        int ty = barTop + barH + 20;
        c.text("contour every " + interval, LABEL_FONT, x, ty);
        c.text("bold every " + interval * INDEX_EVERY, LABEL_FONT, x, ty += 14);
        c.text("dot = spot height", LABEL_FONT, x, ty += 14);
        c.text("lighter = higher", LABEL_FONT, x, ty += 14);
        if (anyWater) {
            ty += 10;
            g.setColor(new Color(blend(0xA0A0A0, WATER_TINT, 0.55)));
            g.fillRect(x, ty, 12, 12);
            c.text("water surface", LABEL_FONT, x + 18, ty + 10);
        }
    }

    /**
     * Elevation key ticks, top to bottom: max and min always, then index contour levels
     * that keep at least {@link #MIN_KEY_TICK_GAP} px from every tick already kept.
     */
    static List<Integer> keyTicks(int minY, int maxY, int interval) {
        List<Integer> kept = new ArrayList<>();
        kept.add(maxY);
        if (minY != maxY) kept.add(minY);
        int indexStep = interval * INDEX_EVERY;
        for (int level = Math.floorDiv(maxY, indexStep) * indexStep; level > minY; level -= indexStep) {
            if (level >= maxY) continue;
            int ty = keyTickOffset(level, minY, maxY);
            if (kept.stream().allMatch(k -> Math.abs(keyTickOffset(k, minY, maxY) - ty) >= MIN_KEY_TICK_GAP)) {
                kept.add(level);
            }
        }
        kept.sort(Collections.reverseOrder());
        return kept;
    }

    private static int keyTickOffset(int level, int minY, int maxY) {
        double t = maxY == minY ? 0 : (maxY - level) / (double) (maxY - minY);
        return (int) Math.round(t * (KEY_BAR_HEIGHT - 1));
    }

    // ---- surface layer ----

    private static void drawSurface(Canvas c, SurfaceCategory[][] categories, double[][] shade,
                                    Map<SurfaceCategory, Integer> counts) {
        Layout l = c.layout;
        Graphics2D g = c.g;
        for (int x = 0; x < l.sizeX; x++) {
            for (int z = 0; z < l.sizeZ; z++) {
                double f = 1 + 0.35 * (shade[x][z] - 1);
                g.setColor(new Color(scale(categories[x][z].rgb(), f)));
                g.fillRect(l.mapX(x), l.mapY(z), l.px, l.px);
            }
        }
        if (l.px >= 3) {
            g.setColor(CATEGORY_EDGE);
            g.setStroke(new BasicStroke(1f));
            for (int x = 0; x < l.sizeX; x++) {
                for (int z = 0; z < l.sizeZ; z++) {
                    if (x + 1 < l.sizeX && categories[x][z] != categories[x + 1][z]) {
                        int lx = l.mapX(x + 1);
                        g.drawLine(lx, l.mapY(z), lx, l.mapY(z) + l.px);
                    }
                    if (z + 1 < l.sizeZ && categories[x][z] != categories[x][z + 1]) {
                        int ly = l.mapY(z + 1);
                        g.drawLine(l.mapX(x), ly, l.mapX(x) + l.px, ly);
                    }
                }
            }
        }

        drawGrid(c);
        drawFrame(c, "Surface material (top block)");

        int x = l.panelX();
        int y = drawNorthArrow(c, x, l.mapY(0)) + 18;
        c.text("Legend (% of area)", AXIS_FONT, x, y - 6);
        int total = l.sizeX * l.sizeZ;
        for (SurfaceCategory cat : sortedByCount(counts)) {
            g.setColor(new Color(cat.rgb()));
            g.fillRect(x, y, 14, 14);
            g.setColor(INK);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(x, y, 14, 14);
            double pct = counts.get(cat) * 100.0 / total;
            String share = pct < 1 ? "<1%" : Math.round(pct) + "%";
            c.text(cat.label() + "  " + share, LABEL_FONT, x + 20, y + 11);
            y += 20;
        }
    }

    // ---- shared furniture ----

    private static void drawGrid(Canvas c) {
        Layout l = c.layout;
        Graphics2D g = c.g;
        int s = l.gridStep;
        g.setStroke(new BasicStroke(1f));
        FontMetrics fm = c.textOk ? g.getFontMetrics(AXIS_FONT) : null;
        int lastRight = Integer.MIN_VALUE;
        for (int wx = Math.floorDiv(l.originX + s - 1, s) * s; wx <= l.originX + l.sizeX; wx += s) {
            int px = l.mapX(wx - l.originX);
            g.setColor(GRID);
            g.drawLine(px, l.mapY(0), px, l.mapY(0) + l.mapH());
            g.setColor(INK);
            g.drawLine(px, l.mapY(0) - 5, px, l.mapY(0) - 1);
            if (fm != null) {
                String text = Integer.toString(wx);
                int left = px - fm.stringWidth(text) / 2;
                if (left > lastRight + 4) {
                    c.text(text, AXIS_FONT, left, l.mapY(0) - 8);
                    lastRight = left + fm.stringWidth(text);
                }
            }
        }
        int lastBottom = Integer.MIN_VALUE;
        for (int wz = Math.floorDiv(l.originZ + s - 1, s) * s; wz <= l.originZ + l.sizeZ; wz += s) {
            int py = l.mapY(wz - l.originZ);
            g.setColor(GRID);
            g.drawLine(l.mapX(0), py, l.mapX(0) + l.mapW(), py);
            g.setColor(INK);
            g.drawLine(l.mapX(0) - 5, py, l.mapX(0) - 1, py);
            if (fm != null && py - 4 > lastBottom + 2) {
                String text = Integer.toString(wz);
                c.text(text, AXIS_FONT, l.mapX(0) - 7 - fm.stringWidth(text), py + 4);
                lastBottom = py + 4;
            }
        }
    }

    private static void drawFrame(Canvas c, String title) {
        Layout l = c.layout;
        Graphics2D g = c.g;
        g.setColor(INK);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(l.mapX(0) - 1, l.mapY(0) - 1, l.mapW() + 1, l.mapH() + 1);
        c.text(title, TITLE_FONT, MARGIN_LEFT, 15);
        c.text("X " + l.originX + ".." + (l.originX + l.sizeX - 1)
                + "   Z " + l.originZ + ".." + (l.originZ + l.sizeZ - 1)
                + "   (" + l.sizeX + "x" + l.sizeZ + ", 1 block = " + l.px + " px)",
            AXIS_FONT, MARGIN_LEFT, 31);
        c.text("X", AXIS_FONT, 6, l.mapY(0) - 8);
        c.text("Z", AXIS_FONT, 6, l.mapY(0) + 14);

        int barY = l.mapY(0) + l.mapH() + 20;
        int barLen = l.gridStep * l.px;
        int bx = l.mapX(0);
        g.drawLine(bx, barY, bx + barLen, barY);
        g.drawLine(bx, barY - 4, bx, barY + 4);
        g.drawLine(bx + barLen, barY - 4, bx + barLen, barY + 4);
        c.text(l.gridStep + " blocks", AXIS_FONT, bx + barLen + 8, barY + 4);
    }

    /** Draws an up arrow labelled N at the top of the panel; returns the y below it. */
    private static int drawNorthArrow(Canvas c, int x, int top) {
        Graphics2D g = c.g;
        g.setColor(INK);
        int cx = x + 7;
        g.fillPolygon(new int[]{cx, cx - 7, cx + 7}, new int[]{top, top + 14, top + 14}, 3);
        g.fillRect(cx - 2, top + 14, 5, 12);
        c.text("N", TITLE_FONT, x + 18, top + 13);
        c.text("+X = east, +Z = south", LABEL_FONT, x, top + 42);
        return top + 54;
    }

    private static double rampGrey(double y, int minY, int maxY) {
        double t = maxY == minY ? 0.5 : (y - minY) / (double) (maxY - minY);
        return 95 + 140 * t;
    }

    private static int blend(int rgb, int tint, double amount) {
        int r = (int) Math.round(((rgb >> 16) & 0xff) * (1 - amount) + ((tint >> 16) & 0xff) * amount);
        int gr = (int) Math.round(((rgb >> 8) & 0xff) * (1 - amount) + ((tint >> 8) & 0xff) * amount);
        int b = (int) Math.round((rgb & 0xff) * (1 - amount) + (tint & 0xff) * amount);
        return (r << 16) | (gr << 8) | b;
    }

    private static int scale(int rgb, double f) {
        int r = clamp((int) Math.round(((rgb >> 16) & 0xff) * f), 0, 255);
        int gr = clamp((int) Math.round(((rgb >> 8) & 0xff) * f), 0, 255);
        int b = clamp((int) Math.round((rgb & 0xff) * f), 0, 255);
        return (r << 16) | (gr << 8) | b;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private record LabelCandidate(int x, int y, int level, boolean index) {}

    private record Layout(int originX, int originZ, int sizeX, int sizeZ, int px, int gridStep) {
        int mapW() { return sizeX * px; }
        int mapH() { return sizeZ * px; }
        int mapX(int x) { return MARGIN_LEFT + x * px; }
        int mapY(int z) { return MARGIN_TOP + z * px; }
        int panelX() { return MARGIN_LEFT + mapW() + MARGIN_RIGHT + 10; }
        int imageW() { return Math.max(panelX() + PANEL_WIDTH, MIN_IMAGE_WIDTH); }
        int imageH() { return Math.max(MARGIN_TOP + mapH() + MARGIN_BOTTOM, MIN_IMAGE_HEIGHT); }
    }

    /** One image being drawn; text is skipped (not fatal) when the JVM has no usable fonts. */
    private static final class Canvas {
        final Layout layout;
        final BufferedImage image;
        final Graphics2D g;
        final boolean textOk;

        Canvas(Layout layout) {
            this.layout = layout;
            this.image = new BufferedImage(layout.imageW(), layout.imageH(), BufferedImage.TYPE_INT_RGB);
            this.g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, layout.imageW(), layout.imageH());
            this.textOk = probeText(g);
        }

        private static boolean probeText(Graphics2D g) {
            try {
                g.setFont(AXIS_FONT);
                g.getFontMetrics().stringWidth("0123456789");
                return true;
            } catch (RuntimeException | LinkageError | InternalError e) {
                return false;
            }
        }

        void text(String s, Font font, int x, int baseline) {
            if (!textOk) return;
            g.setColor(INK);
            g.setFont(font);
            g.drawString(s, x, baseline);
        }

        /** Dark text with a white halo so it stays legible over any terrain; returns its bounds. */
        Rectangle haloText(String s, Font font, int x, int baseline) {
            if (!textOk) return null;
            g.setFont(font);
            g.setColor(Color.WHITE);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) g.drawString(s, x + dx, baseline + dy);
                }
            }
            g.setColor(INK);
            g.drawString(s, x, baseline);
            FontMetrics fm = g.getFontMetrics();
            return new Rectangle(x - 1, baseline - fm.getAscent() - 1, fm.stringWidth(s) + 2, fm.getAscent() + 3);
        }

        byte[] toPng() throws IOException {
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        }
    }
}
