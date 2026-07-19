package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.preview.BlockGrid;
import ca.waltermiller.mcpapi.preview.IsoRenderer;
import ca.waltermiller.mcpapi.preview.PreviewViewDirection;
import ca.waltermiller.mcpapi.preview.TerrainHeightmapGridAdapter;
import io.javalin.Javalin;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.state.property.Property;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class BlocksEndpoint extends APIEndpoint {
    private static final int WRITE_TIMEOUT_SECONDS = 10;
    private static final int BULK_TIMEOUT_SECONDS = 30;

    private final BlocksEndpointCore core;

    public BlocksEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        this(app, server, logger, new BlocksEndpointCore(server, logger));
    }

    BlocksEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger, BlocksEndpointCore core) {
        super(app, server, logger);
        this.core = core;
        init();
    }

    private void init() {
        app.get("/api/world/blocks/list", ctx -> {
            // Delegate to core method
            var blockInfos = core.getBlockList();
            ctx.json(blockInfos);
        });

        // note, payload will be a JSON three-dimensional array indicating the new block values along with coordinates for where to place the block array
        // that is one set of coordinates for the block, and then a three-dimensional array of block values
        // null will be used to indicate blocks that will not be changed.
        app.post("/api/world/blocks/set", ctx -> {
            BlockSetRequest req = ctx.bodyAsClass(BlockSetRequest.class);
            respond(ctx, core.setBlocks(req), WRITE_TIMEOUT_SECONDS, "block operation",
                BlockSetResult::success, BlockSetResult::error,
                result -> Map.of(
                    "success", true,
                    "blocks_set", result.blocksSet(),
                    "blocks_skipped", result.blocksSkipped(),
                    "world", result.world()
                ));
        });

        // get a chunk of blocks, payload will be a JSON object with the chunk coordinates and size to grab
        // response will be a three-dimensional array of block values
        app.post("/api/world/blocks/chunk", ctx -> {
            ChunkRequest req = ctx.bodyAsClass(ChunkRequest.class);
            respond(ctx, core.getChunk(req), WRITE_TIMEOUT_SECONDS, "chunk operation",
                ChunkResult::success, ChunkResult::error,
                result -> Map.of(
                    "success", true,
                    "world", result.world(),
                    "start_position", result.startPosition(),
                    "size", result.size(),
                    "blocks", result.blocks()
                ));
        });

        // Fill a box/cuboid with a specific block type between two coordinates
        app.post("/api/world/blocks/fill", ctx -> {
            FillBoxRequest req = ctx.bodyAsClass(FillBoxRequest.class);
            respond(ctx, core.fillBox(req), BULK_TIMEOUT_SECONDS, "box fill operation",
                FillResult::success, FillResult::error,
                result -> Map.of(
                    "success", true,
                    "blocks_set", result.blocksSet(),
                    "blocks_failed", result.blocksFailed(),
                    "total_blocks", result.totalBlocks(),
                    "world", result.world(),
                    "box_bounds", result.boxBounds()
                ));
        });

        // Get heightmap/topography for a rectangular area
        app.post("/api/world/blocks/heightmap", ctx -> {
            HeightmapRequest req = ctx.bodyAsClass(HeightmapRequest.class);
            respond(ctx, core.getHeightmap(req), BULK_TIMEOUT_SECONDS, "heightmap operation",
                HeightmapResult::success, HeightmapResult::error,
                result -> Map.of(
                    "success", true,
                    "world", result.world(),
                    "area_bounds", result.areaBounds(),
                    "size", result.size(),
                    "heightmap_type", result.heightmapType(),
                    "height_range", result.heightRange(),
                    "heights", result.heights()
                ));
        });

        app.post("/api/world/blocks/heightmap/preview", ctx -> {
            HeightmapPreviewRequest req = ctx.bodyAsClass(HeightmapPreviewRequest.class);

            int scale = IsoRenderer.DEFAULT_SCALE;
            if (req.iso_scale != null) {
                if (req.iso_scale < IsoRenderer.MIN_SCALE || req.iso_scale > IsoRenderer.MAX_SCALE) {
                    ctx.status(400).json(Map.of("error",
                        "iso_scale must be between " + IsoRenderer.MIN_SCALE + " and " + IsoRenderer.MAX_SCALE));
                    return;
                }
                scale = req.iso_scale;
            }

            PreviewViewDirection viewDirection;
            try {
                viewDirection = PreviewViewDirection.fromQueryParam(req.view_direction);
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", "view_direction must be one of: south, west, north, east"));
                return;
            }

            HeightmapRequest heightmapRequest = new HeightmapRequest();
            heightmapRequest.world = req.world;
            heightmapRequest.x1 = req.x1;
            heightmapRequest.z1 = req.z1;
            heightmapRequest.x2 = req.x2;
            heightmapRequest.z2 = req.z2;
            heightmapRequest.heightmap_type = req.heightmap_type;

            CompletableFuture<HeightmapResult> future = core.getHeightmap(heightmapRequest);

            try {
                HeightmapResult result = future.get(BULK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!result.success()) {
                    ctx.status(isClientError(result.error()) ? 400 : 500).json(Map.of("error", result.error()));
                    return;
                }

                BlockGrid grid = TerrainHeightmapGridAdapter.fromHeights(result.heights());
                if (grid.isEmpty()) {
                    ctx.status(400).json(Map.of("error", "Heightmap preview area produced no renderable terrain"));
                    return;
                }

                byte[] png = IsoRenderer.renderPng(grid, scale, viewDirection);
                ctx.contentType("image/png");
                ctx.result(png);
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for heightmap preview operation"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });
    }

    /**
     * Get access to the core block operations for programmatic use
     */
    public BlocksEndpointCore getCore() {
        return core;
    }
}

record BlockInfo(String id, String display_name) {
}

class BlockData {
    public String block_name;
    public Map<String, String> block_states; // optional, uses default states if not provided
    
    // Helper method to create BlockState from this data
    public BlockState toBlockState() {
        Identifier blockId = Identifier.tryParse(block_name);
        if (blockId == null) return null;

        Block block = Registries.BLOCK.get(blockId);
        if (block == null) return null;

        BlockState state = block.getDefaultState();

        // Apply block states if provided
        if (block_states != null) {
            for (Map.Entry<String, String> entry : block_states.entrySet()) {
                String propertyName = entry.getKey();
                String value = entry.getValue();
                
                // Find the property in the block's state definition
                for (Property<?> property : state.getProperties()) {
                    if (property.getName().equals(propertyName)) {
                        try {
                            var parsedValue = property.parse(value);
                            if (parsedValue.isPresent()) {
                                state = setBlockStateProperty(state, property, parsedValue.get());
                            }
                        } catch (Exception e) {
                            // Invalid property value, skip this property
                        }
                        break;
                    }
                }
            }
        }
        
        return state;
    }
    
    // Helper method to create BlockData from BlockState
    public static BlockData fromBlockState(BlockState blockState) {
        BlockData data = new BlockData();
        data.block_name = Registries.BLOCK.getId(blockState.getBlock()).toString();
        data.block_states = new HashMap<>();

        // Extract all block state properties
        for (Property<?> property : blockState.getProperties()) {
            data.block_states.put(property.getName(), blockState.get(property).toString());
        }

        return data;
    }
    
    // Helper method to handle generic type casting for block state properties
    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState setBlockStateProperty(BlockState state, Property<T> property, Comparable<?> value) {
        return state.with(property, (T) value);
    }
}

class BlockSetRequest {
    public String world; // optional, defaults to overworld
    public int start_x;
    public int start_y;
    public int start_z;
    public BlockData[][][] blocks; // 3D array of block data objects, null means no change
}

class ChunkRequest {
    public String world; // optional, defaults to overworld
    public int start_x;
    public int start_y;
    public int start_z;
    public int size_x;
    public int size_y;
    public int size_z;
}

class FillBoxRequest {
    public String world; // optional, defaults to overworld
    public int x1, y1, z1; // first corner coordinate
    public int x2, y2, z2; // second corner coordinate
    public String block_type; // block identifier (e.g., "minecraft:stone")
    public boolean notify_neighbors; // optional, defaults to false
}

class HeightmapRequest {
    public String world; // optional, defaults to overworld
    public int x1, z1; // first corner coordinate (only X and Z needed for heightmap)
    public int x2, z2; // second corner coordinate (only X and Z needed for heightmap)
    public String heightmap_type; // optional, defaults to WORLD_SURFACE
    // Valid types: WORLD_SURFACE, MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES, OCEAN_FLOOR
}

class HeightmapPreviewRequest extends HeightmapRequest {
    public Integer iso_scale; // optional, defaults to 6
    public String view_direction; // optional, defaults to south
}
