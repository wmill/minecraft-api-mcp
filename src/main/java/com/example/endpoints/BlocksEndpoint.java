package com.example.endpoints;

import io.javalin.Javalin;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;

public class BlocksEndpoint extends APIEndpoint {
    public BlocksEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        init();
    }

    private void init() {
        // Define your endpoints here
        app.get("/api/world/blocks/list", ctx -> {
            // Map over Registries.BLOCK and return a list of BlockInfo
            var blockInfos = Registries.BLOCK.stream()
                    .map(block -> new BlockInfo(
                            Registries.BLOCK.getId(block).toString(),
                            block.getTranslationKey()))
                    .toList();
            ctx.json(blockInfos);
        });

        // note, payload will be a JSON three-dimensional array indicating the new block values along with coordinates for where to place the block array
        // that is one set of coordinates for the block, and then a three-dimensional array of block values
        // null will be used to indicate blocks that will not be changed.
        app.post("/api/world/blocks/set", ctx -> {
            // TODO - Implement logic to set blocks in the world

        });

        // get a chunk of blocks, payload will be a JSON object with the chunk coordinates and size to grab
        // response will be a three-dimensional array of block values
        app.get("/api/world/blocks/chunk", ctx -> {
            // TODO - Implement logic to get a chunk of blocks

        });
    }
}

record BlockInfo(String id, String display_name) {
}
