package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.preview.WorldBlockSink;
import io.javalin.Javalin;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class RainFireEndpoint extends APIEndpoint {
    private static final int MAX_RADIUS = 56;

    public RainFireEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        super(app, server, logger);
        init();
    }

    private void init() {
        app.post("/api/world/effects/rain-fire", ctx -> {
            RainFireRequest req = ctx.bodyAsClass(RainFireRequest.class);

            if (req.radius <= 0 || req.radius > MAX_RADIUS) {
                ctx.status(400).json(Map.of("error",
                    "radius must be between 1 and " + MAX_RADIUS));
                return;
            }
            if (!(req.density >= 0.0f) || !(req.density <= 1.0f)) {
                ctx.status(400).json(Map.of("error", "density must be between 0.0 and 1.0"));
                return;
            }

            RegistryKey<World> worldKey = req.world != null
                ? RegistryKey.of(RegistryKeys.WORLD, Identifier.tryParse(req.world))
                : World.OVERWORLD;
            ServerWorld world = server.getWorld(worldKey);
            if (world == null) {
                ctx.status(400).json(Map.of("error", "Unknown world: " + worldKey));
                return;
            }

            CompletableFuture<RainFireResult> future = new CompletableFuture<>();
            server.execute(() -> future.complete(rainFire(world, worldKey, req)));

            try {
                RainFireResult result = future.get(30, TimeUnit.SECONDS);
                if (!result.success()) {
                    ctx.status(500).json(Map.of("error", result.error()));
                } else {
                    ctx.json(Map.of(
                        "success", true,
                        "world", result.world(),
                        "fires_placed", result.fires_placed(),
                        "columns_considered", result.columns_considered(),
                        "center", result.center(),
                        "radius", result.radius(),
                        "density", result.density()
                    ));
                }
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(500).json(Map.of("error", "Timeout waiting for rain_fire"));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
            }
        });
    }

    private RainFireResult rainFire(ServerWorld world, RegistryKey<World> worldKey, RainFireRequest req) {
        try {
            WorldBlockSink sink = new WorldBlockSink(world);
            BlockState fireState = Blocks.FIRE.getDefaultState();
            Random random = req.seed != null
                ? new Random(req.seed)
                : ThreadLocalRandom.current();

            int r = req.radius;
            long r2 = (long) r * r;
            int columnsConsidered = 0;
            int firesPlaced = 0;

            BlockPos.Mutable placePos = new BlockPos.Mutable();
            BlockPos.Mutable belowPos = new BlockPos.Mutable();

            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if ((long) dx * dx + (long) dz * dz > r2) continue;
                    columnsConsidered++;
                    if (random.nextFloat() >= req.density) continue;

                    int x = req.x + dx;
                    int z = req.z + dz;
                    int y = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);

                    belowPos.set(x, y - 1, z);
                    BlockState below = world.getBlockState(belowPos);
                    if (below.isAir()
                        || below.isOf(Blocks.WATER)
                        || below.isOf(Blocks.LAVA)
                        || below.isOf(Blocks.FIRE)) {
                        continue;
                    }

                    placePos.set(x, y, z);
                    if (sink.setBlockState(placePos, fireState, Block.NOTIFY_ALL)) {
                        firesPlaced++;
                    }
                }
            }

            LOGGER.info("rain_fire placed {} fires across {} columns at ({}, {}) r={} density={} in world {}",
                firesPlaced, columnsConsidered, req.x, req.z, r, req.density, worldKey.getValue());

            return new RainFireResult(
                true, null, worldKey.getValue().toString(),
                firesPlaced, columnsConsidered,
                Map.of("x", req.x, "z", req.z), r, req.density);
        } catch (Exception e) {
            LOGGER.error("Error in rain_fire", e);
            return new RainFireResult(
                false, "Exception during rain_fire: " + e.getMessage(),
                null, 0, 0, null, 0, 0f);
        }
    }
}

class RainFireRequest {
    public String world; // optional, defaults to overworld
    public int x;        // center X
    public int z;        // center Z
    public int radius;   // radius in blocks (1..56)
    public float density; // per-column probability in [0.0, 1.0]
    public Long seed;    // optional random seed for reproducibility
}

record RainFireResult(
    boolean success,
    String error,
    String world,
    int fires_placed,
    int columns_considered,
    Map<String, Integer> center,
    int radius,
    float density
) {}
