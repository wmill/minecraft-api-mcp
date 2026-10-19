package ca.waltermiller.mcpapi.preview;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IsoRendererTest {

    @Test
    void nearerCubeOccludesFartherCubeInEveryView() throws Exception {
        for (PreviewViewDirection direction : PreviewViewDirection.values()) {
            // Arrange each world view to produce the same two diagonal cubes
            // in camera space: red at (0,0,0), blue at (1,0,1).
            BlockPos far;
            BlockPos near;
            switch (direction) {
                case SOUTH -> { far = new BlockPos(0, 0, 0); near = new BlockPos(1, 0, 1); }
                case WEST -> { far = new BlockPos(1, 0, 0); near = new BlockPos(0, 0, 1); }
                case NORTH -> { far = new BlockPos(1, 0, 1); near = new BlockPos(0, 0, 0); }
                case EAST -> { far = new BlockPos(0, 0, 1); near = new BlockPos(1, 0, 0); }
                default -> throw new AssertionError(direction);
            }
            BlockGrid grid = new BlockGrid(Map.of(far, "minecraft:red_wool", near, "minecraft:blue_wool"),
                    0, 0, 0, 1, 0, 1);
            int scale = 12;
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(IsoRenderer.renderPng(grid, scale, direction)));
            int[] blue = Palette.colorFor("minecraft:blue_wool");
            int expected = new java.awt.Color(blue[0], blue[1], blue[2]).getRGB();
            assertThat(img.getRGB(42, 33)).as("near cube top in %s view", direction).isEqualTo(expected);
        }
    }

    @Test
    void rendersNonEmptyPngForSmallGrid() throws Exception {
        Map<BlockPos, String> cells = new HashMap<>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                cells.put(new BlockPos(x, 0, z), "minecraft:stone");
            }
        }
        cells.put(new BlockPos(1, 1, 1), "minecraft:gold_block");

        BlockGrid grid = new BlockGrid(cells, 0, 0, 0, 2, 1, 2);

        byte[] png = IsoRenderer.renderPng(grid, 4);

        assertThat(png).isNotEmpty();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isGreaterThan(0);
        assertThat(decoded.getHeight()).isGreaterThan(0);

        boolean hasOpaquePixel = false;
        outer:
        for (int y = 0; y < decoded.getHeight(); y++) {
            for (int x = 0; x < decoded.getWidth(); x++) {
                int argb = decoded.getRGB(x, y);
                if (((argb >> 24) & 0xff) > 0) {
                    hasOpaquePixel = true;
                    break outer;
                }
            }
        }
        assertThat(hasOpaquePixel).as("rendered PNG should contain opaque pixels").isTrue();
    }

    @Test
    void emptyGridRendersTransparentPng() throws Exception {
        BlockGrid grid = new BlockGrid(Map.of(), 0, 0, 0, -1, -1, -1);
        byte[] png = IsoRenderer.renderPng(grid, 2);
        assertThat(png).isNotEmpty();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(decoded).isNotNull();
    }

    @Test
    void rendersDifferentImageForDifferentViewDirection() throws Exception {
        Map<BlockPos, String> cells = new HashMap<>();
        cells.put(new BlockPos(0, 0, 0), "minecraft:stone");
        cells.put(new BlockPos(2, 0, 0), "minecraft:gold_block");
        cells.put(new BlockPos(0, 1, 1), "minecraft:oak_planks");

        BlockGrid grid = new BlockGrid(cells, 0, 0, 0, 2, 1, 1);

        byte[] south = IsoRenderer.renderPng(grid, 4, PreviewViewDirection.SOUTH);
        byte[] west = IsoRenderer.renderPng(grid, 4, PreviewViewDirection.WEST);
        byte[] north = IsoRenderer.renderPng(grid, 4, PreviewViewDirection.NORTH);

        assertThat(south).isNotEqualTo(west);
        assertThat(west).isNotEqualTo(north);
    }
}
