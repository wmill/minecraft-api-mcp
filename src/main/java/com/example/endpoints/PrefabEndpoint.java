package com.example.endpoints;

import io.javalin.Javalin;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;

public class PrefabEndpoint extends APIEndpoint{
    private final PrefabEndpointCore core;
    
    public PrefabEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        this.core = new PrefabEndpointCore(server, logger);
        init();
    }
    private void init() {
        registerDoor();
        registerStairs();
        registerWindowPane();
        registerTorch();
        registerSign();
    }
    private void registerDoor() {
        app.post("/api/world/prefabs/door", ctx -> {
            DoorRequest req = ctx.bodyAsClass(DoorRequest.class);

            // Delegate to core method
            CompletableFuture<DoorResult> future = core.placeDoor(req);

            // Wait for result and respond
            try {
                DoorResult result = future.get(10, TimeUnit.SECONDS);
                if (!result.success()) {
                    ctx.status(500).json(Map.of("error", result.error()));
                } else {
                    ctx.json(Map.of(
                        "success", true,
                        "world", result.world(),
                        "doors_placed", result.doorsPlaced(),
                        "facing", result.facing(),
                        "hinge", result.hinge(),
                        "open", result.open()
                    ));
                }
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for door placement"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });
    }

    private void registerStairs() {
        app.post("/api/world/prefabs/stairs", ctx -> {
            StairRequest req = ctx.bodyAsClass(StairRequest.class);

            // Delegate to core method
            CompletableFuture<StairResult> future = core.placeStairs(req);

            // Wait for result and respond
            try {
                StairResult result = future.get(10, TimeUnit.SECONDS);
                if (!result.success()) {
                    ctx.status(500).json(Map.of("error", result.error()));
                } else {
                    ctx.json(Map.of(
                        "success", true,
                        "world", result.world(),
                        "blocks_placed", result.blocksPlaced(),
                        "staircase_direction", result.staircaseDirection(),
                        "fill_support", result.fillSupport()
                    ));
                }
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for stair placement"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });
    }

    private void registerWindowPane() {
        app.post("/api/world/prefabs/window-pane", ctx -> {
            WindowPaneRequest req = ctx.bodyAsClass(WindowPaneRequest.class);

            // Delegate to core method
            CompletableFuture<WindowPaneResult> future = core.placeWindowPane(req);

            // Wait for result and respond
            try {
                WindowPaneResult result = future.get(10, TimeUnit.SECONDS);
                if (!result.success()) {
                    ctx.status(500).json(Map.of("error", result.error()));
                } else {
                    ctx.json(Map.of(
                        "success", true,
                        "world", result.world(),
                        "panes_placed", result.panesPlaced(),
                        "orientation", result.orientation(),
                        "waterlogged", result.waterlogged()
                    ));
                }
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for window pane placement"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });
    }

    private void registerTorch() {
        app.post("/api/world/prefabs/torch", ctx -> {
            TorchRequest req = ctx.bodyAsClass(TorchRequest.class);

            // Delegate to core method
            CompletableFuture<TorchResult> future = core.placeTorch(req);

            // Wait for result and respond
            try {
                TorchResult result = future.get(10, TimeUnit.SECONDS);
                if (!result.success()) {
                    ctx.status(500).json(Map.of("error", result.error()));
                } else {
                    Map<String, Object> response = Map.of(
                        "success", true,
                        "world", result.world(),
                        "position", result.position(),
                        "block_type", result.blockType(),
                        "wall_mounted", result.wallMounted()
                    );
                    
                    // Add facing if it's a wall torch
                    if (result.wallMounted() && result.facing() != null) {
                        response = new java.util.HashMap<>(response);
                        response.put("facing", result.facing());
                    }
                    
                    ctx.json(response);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for torch placement"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });
    }

    private void registerSign() {
        app.post("/api/world/prefabs/sign", ctx -> {
            SignRequest req = ctx.bodyAsClass(SignRequest.class);

            // Delegate to core method
            CompletableFuture<SignResult> future = core.placeSign(req);

            // Wait for result and respond
            try {
                SignResult result = future.get(10, TimeUnit.SECONDS);
                if (!result.success()) {
                    ctx.status(500).json(Map.of("error", result.error()));
                } else {
                    Map<String, Object> response = new java.util.HashMap<>(Map.of(
                        "success", true,
                        "world", result.world(),
                        "position", result.position(),
                        "block_type", result.blockType(),
                        "sign_type", result.signType(),
                        "glowing", result.glowing()
                    ));
                    
                    // Add facing or rotation based on sign type
                    if ("wall".equals(result.signType()) && result.facing() != null) {
                        response.put("facing", result.facing());
                    } else if ("standing".equals(result.signType()) && result.rotation() != null) {
                        response.put("rotation", result.rotation());
                    }
                    
                    ctx.json(response);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for sign placement"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
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
