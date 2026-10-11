package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.preview.BlockGrid;
import ca.waltermiller.mcpapi.preview.IsoRenderer;
import ca.waltermiller.mcpapi.preview.PreviewViewDirection;
import ca.waltermiller.mcpapi.preview.TerrainHeightmapGridAdapter;
import ca.waltermiller.mcpapi.preview.TerrainMapRenderer;
import ca.waltermiller.mcpapi.arealock.AreaLockService;
import io.javalin.Javalin;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.state.property.Property;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class BlocksEndpoint extends APIEndpoint {
    private static final int WRITE_TIMEOUT_SECONDS = 10;
    private static final int BULK_TIMEOUT_SECONDS = 30;

    private final BlocksEndpointCore core;

    public BlocksEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        this(app, server, logger, new AreaLockService());
    }

    public BlocksEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger, AreaLockService locks) {
        this(app, server, logger, new BlocksEndpointCore(server, logger, locks));
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
            respond(ctx, core.setBlocks(req, ctx.header(AreaLockService.HEADER)), WRITE_TIMEOUT_SECONDS, "block operation",
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
            respond(ctx, core.fillBox(req, ctx.header(AreaLockService.HEADER)), BULK_TIMEOUT_SECONDS, "box fill operation",
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

        // Top-down relief (contours, spot heights) and surface-material maps as base64 PNGs
        app.post("/api/world/blocks/heightmap/map", ctx -> {
            TerrainMapRequest req = ctx.bodyAsClass(TerrainMapRequest.class);
            String invalid = req.validate();
            if (invalid != null) {
                ctx.status(400).json(Map.of("error", invalid));
                return;
            }
            boolean relief = req.wantsLayer("relief");
            boolean surface = req.wantsLayer("surface");
            // Surface blocks are sampled even for relief-only maps: they mark water.
            respond(ctx, core.getTerrainSample(req), BULK_TIMEOUT_SECONDS, "terrain map operation",
                TerrainSampleResult::success, TerrainSampleResult::error,
                result -> terrainMapBody(result, new TerrainMapRenderer.Options(
                    req.px_per_block, req.contour_interval, relief, surface)));
        });
    }

    private static Map<String, Object> terrainMapBody(TerrainSampleResult result, TerrainMapRenderer.Options options) {
        HeightmapResult hm = result.heightmap();
        Map<String, Map<String, Integer>> bounds = hm.areaBounds();
        TerrainMapRenderer.Result map;
        try {
            map = TerrainMapRenderer.render(hm.heights(), result.surfaceBlocks(),
                bounds.get("min").get("x"), bounds.get("min").get("z"), options);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        TerrainMapRenderer.Meta meta = map.meta();
        Base64.Encoder base64 = Base64.getEncoder();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("world", hm.world());
        body.put("area_bounds", bounds);
        body.put("size", hm.size());
        body.put("heightmap_type", hm.heightmapType());
        body.put("px_per_block", meta.pxPerBlock());
        body.put("contour_interval", meta.contourInterval());
        body.put("grid_step", meta.gridStep());
        body.put("y", Map.of("min", meta.minY(), "median", meta.medianY(), "max", meta.maxY()));
        body.put("coverage", meta.coverage());
        body.put("labels", meta.labels());
        if (map.reliefPng() != null) body.put("relief_png", base64.encodeToString(map.reliefPng()));
        if (map.surfacePng() != null) body.put("surface_png", base64.encodeToString(map.surfacePng()));
        return body;
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

class TerrainMapRequest extends HeightmapRequest {
    public Integer px_per_block; // optional, auto-fit to ~768 px
    public Integer contour_interval; // optional, auto from the height range
    public List<String> layers; // optional subset of ["relief", "surface"], defaults to both

    boolean wantsLayer(String layer) {
        return layers == null || layers.isEmpty() || layers.contains(layer);
    }

    /** Returns an error message for out-of-range options, or null when valid. */
    String validate() {
        if (px_per_block != null && (px_per_block < TerrainMapRenderer.MIN_PX_PER_BLOCK
                || px_per_block > TerrainMapRenderer.MAX_PX_PER_BLOCK)) {
            return "px_per_block must be between " + TerrainMapRenderer.MIN_PX_PER_BLOCK
                + " and " + TerrainMapRenderer.MAX_PX_PER_BLOCK;
        }
        if (contour_interval != null && (contour_interval < TerrainMapRenderer.MIN_CONTOUR_INTERVAL
                || contour_interval > TerrainMapRenderer.MAX_CONTOUR_INTERVAL)) {
            return "contour_interval must be between " + TerrainMapRenderer.MIN_CONTOUR_INTERVAL
                + " and " + TerrainMapRenderer.MAX_CONTOUR_INTERVAL;
        }
        if (layers != null && layers.stream().anyMatch(l -> !"relief".equals(l) && !"surface".equals(l))) {
            return "layers may only contain \"relief\" and \"surface\"";
        }
        return null;
    }
}
