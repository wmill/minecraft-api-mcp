package ca.waltermiller.mcpapi.endpoints;

import io.javalin.Javalin;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;

public class PrefabEndpoint extends APIEndpoint {
    private static final int TIMEOUT_SECONDS = 10;

    private final PrefabEndpointCore core;

    public PrefabEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        this.core = new PrefabEndpointCore(server, logger);
        init();
    }

    private void init() {
        app.post("/api/world/prefabs/door", ctx -> {
            DoorRequest req = ctx.bodyAsClass(DoorRequest.class);
            respond(ctx, core.placeDoor(req), TIMEOUT_SECONDS, "door placement",
                DoorResult::success, DoorResult::error,
                result -> Map.of(
                    "success", true,
                    "world", result.world(),
                    "doors_placed", result.doors_placed(),
                    "facing", result.facing(),
                    "hinge", result.hinge(),
                    "open", result.open()
                ));
        });

        app.post("/api/world/prefabs/stairs", ctx -> {
            StairRequest req = ctx.bodyAsClass(StairRequest.class);
            respond(ctx, core.placeStairs(req), TIMEOUT_SECONDS, "stair placement",
                StairResult::success, StairResult::error,
                result -> Map.of(
                    "success", true,
                    "world", result.world(),
                    "blocks_placed", result.blocks_placed(),
                    "staircase_direction", result.staircase_direction(),
                    "fill_support", result.fill_support()
                ));
        });

        app.post("/api/world/prefabs/window-pane", ctx -> {
            WindowPaneRequest req = ctx.bodyAsClass(WindowPaneRequest.class);
            respond(ctx, core.placeWindowPane(req), TIMEOUT_SECONDS, "window pane placement",
                WindowPaneResult::success, WindowPaneResult::error,
                result -> Map.of(
                    "success", true,
                    "world", result.world(),
                    "panes_placed", result.panes_placed(),
                    "orientation", result.orientation(),
                    "waterlogged", result.waterlogged()
                ));
        });

        app.post("/api/world/prefabs/torch", ctx -> {
            TorchRequest req = ctx.bodyAsClass(TorchRequest.class);
            respond(ctx, core.placeTorch(req), TIMEOUT_SECONDS, "torch placement",
                TorchResult::success, TorchResult::error,
                result -> {
                    Map<String, Object> response = new HashMap<>(Map.of(
                        "success", true,
                        "world", result.world(),
                        "position", result.position(),
                        "block_type", result.block_type(),
                        "wall_mounted", result.wall_mounted()
                    ));
                    if (result.wall_mounted() && result.facing() != null) {
                        response.put("facing", result.facing());
                    }
                    return response;
                });
        });

        app.post("/api/world/prefabs/sign", ctx -> {
            SignRequest req = ctx.bodyAsClass(SignRequest.class);
            respond(ctx, core.placeSign(req), TIMEOUT_SECONDS, "sign placement",
                SignResult::success, SignResult::error,
                result -> {
                    Map<String, Object> response = new HashMap<>(Map.of(
                        "success", true,
                        "world", result.world(),
                        "position", result.position(),
                        "block_type", result.block_type(),
                        "sign_type", result.sign_type(),
                        "glowing", result.glowing()
                    ));
                    if ("wall".equals(result.sign_type()) && result.facing() != null) {
                        response.put("facing", result.facing());
                    } else if ("standing".equals(result.sign_type()) && result.rotation() != null) {
                        response.put("rotation", result.rotation());
                    }
                    return response;
                });
        });

        app.post("/api/world/prefabs/ladder", ctx -> {
            LadderRequest req = ctx.bodyAsClass(LadderRequest.class);
            respond(ctx, core.placeLadder(req), TIMEOUT_SECONDS, "ladder placement",
                LadderResult::success, LadderResult::error,
                result -> Map.of(
                    "success", true,
                    "world", result.world(),
                    "blocks_placed", result.blocks_placed(),
                    "facing", result.facing(),
                    "start_position", result.start_position(),
                    "end_position", result.end_position()
                ));
        });
    }

    /**
     * Get access to the core prefab operations for programmatic use
     */
    public PrefabEndpointCore getCore() {
        return core;
    }
}

class DoorRequest {
    public String world; // optional, defaults to overworld
    public int start_x;
    public int start_y;
    public int start_z;
    public int width = 1; // number of doors to place in a row
    public String facing; // direction door faces (e.g. "north")
    public String block_type; // block identifier (e.g., "minecraft:oak_door")
    public String hinge = "left"; // "left" or "right"
    public Boolean open = false; // whether the door starts open
    public Boolean double_doors = false; // pair up the doors by reversing hinges
}

class StairRequest {
    public String world; // optional, defaults to overworld
    public int start_x;
    public int start_y;
    public int start_z;
    public int end_x;
    public int end_y;
    public int end_z;
    public String block_type; // block identifier (e.g., "minecraft:oak_block")
    public String stair_type; // block identifier (e.g., "minecraft:oak_stairs")
    public String staircase_direction; // orientation of the staircase structure (e.g. "north")
    public boolean fill_support = false; // fill underneath the staircase
}

class WindowPaneRequest {
    public String world; // optional, defaults to overworld
    public int start_x;
    public int start_y;
    public int start_z;
    public int end_x;   // defines the wall endpoint
    public int end_z;   // defines the wall endpoint
    public int height; // Y dimension (how tall the wall is)
    public String block_type; // e.g., "minecraft:glass_pane", "minecraft:iron_bars"
    public boolean waterlogged = false;
}

class TorchRequest {
    public String world; // optional, defaults to overworld
    public int x;
    public int y;
    public int z;
    public String block_type; // e.g., "minecraft:torch", "minecraft:wall_torch", "minecraft:soul_wall_torch"
    public String facing; // optional for wall torches - "north", "south", "east", "west" - auto-detects if not provided
}

class SignRequest {
    public String world; // optional, defaults to overworld
    public int x;
    public int y;
    public int z;
    public String block_type; // e.g., "minecraft:oak_wall_sign", "minecraft:oak_sign", "minecraft:birch_wall_sign"
    public String[] front_lines; // 0-4 lines of text for front of sign
    public String[] back_lines; // 0-4 lines of text for back of sign (optional)
    public String facing; // optional for wall signs - "north", "south", "east", "west" - auto-detects if not provided
    public Integer rotation; // for standing signs - 0-15 (optional, defaults to 0)
    public Boolean glowing; // whether text glows (optional, defaults to false)
}

class LadderRequest {
    public String world; // optional, defaults to overworld
    public int x; // ladder base X coordinate
    public int y; // ladder base Y coordinate
    public int z; // ladder base Z coordinate
    public int height; // number of ladder blocks to place vertically
    public String block_type; // ladder block identifier (e.g., "minecraft:ladder")
    public String facing; // optional - direction ladder faces ("north", "south", "east", "west")
}
