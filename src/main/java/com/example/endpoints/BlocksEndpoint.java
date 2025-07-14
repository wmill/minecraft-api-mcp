package com.example.endpoints;

import io.javalin.Javalin;
import net.minecraft.server.MinecraftServer;

public class BlocksEndpoint extends APIEndpoint {
    public BlocksEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        init();
    }

    private void init() {
        // Define your endpoints here
        app.get("/api/world/blocks/list", ctx -> {
            ctx.result("List of blocks would be here");
            // TODO - Implement logic to return a list of blocks
        });

        // note, payload will be a JSON three-dimensional array indicating the new block values along with coordinates for where to place the block array
        // that is one set of coordinates for the block, and then a three-dimensional array of block values
        // null will be used to indicate blocks that will not be changed.
        app.post("/api/world/blocks/set", ctx -> {
            // TODO - Implement logic to set blocks in the world
            BlockPlaceRequest req = ctx.bodyAsClass(BlockPlaceRequest.class);
            // Implement logic to place a block at the specified position
            ctx.result("Placing block " + req.blockType + " at " + req.position);
        });

        // get a chunk of blocks, payload will be a JSON object with the chunk coordinates and size to grab
        // response will be a three-dimensional array of block values
        app.get("/api/world/blocks/chunk", ctx -> {
            // TODO - Implement logic to get a chunk of blocks
            ChunkRequest req = ctx.bodyAsClass(ChunkRequest.class);
            ctx.result("Getting chunk at " + req.chunkPosition + " with size " + req.size);
        });
    }
}
