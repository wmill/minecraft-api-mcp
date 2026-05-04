package ca.waltermiller.mcpapi.preview;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlockGridTest {

    @Test
    void terrainMarginIncludesNearbyTerrainBlocks() {
        Map<BlockPos, String> placed = new LinkedHashMap<>();
        placed.put(new BlockPos(1, 0, 0), "minecraft:gold_block");

        BlockGrid grid = BlockGrid.fromIds(placed, pos -> {
            if (pos.equals(new BlockPos(0, 0, 0))) {
                return "minecraft:stone";
            }
            if (pos.equals(new BlockPos(1, 0, 0))) {
                return "minecraft:grass_block";
            }
            return "minecraft:air";
        }, 1);

        assertThat(grid.blockAt(0, 0, 0)).isEqualTo("minecraft:stone");
        assertThat(grid.blockAt(1, 0, 0)).isEqualTo("minecraft:gold_block");
        assertThat(grid.blockAt(2, 0, 0)).isNull();
    }

    @Test
    void emptyPlacedBlocksStillProduceEmptyGridWhenTerrainMarginRequested() {
        BlockGrid grid = BlockGrid.fromIds(Map.of(), pos -> "minecraft:stone", 3);

        assertThat(grid.isEmpty()).isTrue();
    }
}
