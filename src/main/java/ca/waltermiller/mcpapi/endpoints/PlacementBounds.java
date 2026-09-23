package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import com.fasterxml.jackson.databind.JsonNode;

/** Conservative bounds of direct writes, including clearance and support blocks. */
final class PlacementBounds {
    private PlacementBounds() {}

    static int add(int value, int offset) {
        try {
            return Math.addExact(value, offset);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Invalid coordinate: placement bounds overflow", e);
        }
    }

    static AreaBounds box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new AreaBounds(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
            Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    static AreaBounds of(BlockSetRequest req) {
        if (req.blocks == null) throw new IllegalArgumentException("Invalid blocks array");
        AreaBounds bounds = null;
        for (int x = 0; x < req.blocks.length; x++) {
            if (req.blocks[x] == null) throw new IllegalArgumentException("Invalid blocks array");
            for (int y = 0; y < req.blocks[x].length; y++) {
                if (req.blocks[x][y] == null) throw new IllegalArgumentException("Invalid blocks array");
                for (int z = 0; z < req.blocks[x][y].length; z++) {
                    if (req.blocks[x][y][z] == null) continue;
                    int px = add(req.start_x, x), py = add(req.start_y, y), pz = add(req.start_z, z);
                    AreaBounds point = new AreaBounds(px, py, pz, px, py, pz);
                    bounds = bounds == null ? point : bounds.union(point);
                }
            }
        }
        return bounds;
    }

    static AreaBounds of(FillBoxRequest req) {
        return box(req.x1, req.y1, req.z1, req.x2, req.y2, req.z2);
    }

    static AreaBounds of(DoorRequest req) {
        var facing = PrefabEndpointCore.parseHorizontalDirection(req.facing);
        if (facing == null || req.width <= 0) throw new IllegalArgumentException("Invalid door facing or width");
        var lateral = facing.rotateYClockwise();
        return box(req.start_x, req.start_y, req.start_z,
            add(req.start_x, lateral.getOffsetX() * (req.width - 1)), add(req.start_y, 1),
            add(req.start_z, lateral.getOffsetZ() * (req.width - 1)));
    }

    static AreaBounds of(StairRequest req) {
        return box(Math.min(req.start_x, req.end_x), add(Math.min(req.start_y, req.end_y), req.fill_support ? -1 : 0),
            Math.min(req.start_z, req.end_z), Math.max(req.start_x, req.end_x),
            add(Math.max(req.start_y, req.end_y), 4), Math.max(req.start_z, req.end_z));
    }

    static AreaBounds of(WindowPaneRequest req) {
        if (req.height <= 0) throw new IllegalArgumentException("Invalid window height");
        return box(req.start_x, req.start_y, req.start_z, req.end_x, add(req.start_y, req.height - 1), req.end_z);
    }

    static AreaBounds of(TorchRequest req) { return box(req.x, req.y, req.z, req.x, req.y, req.z); }
    static AreaBounds of(SignRequest req) { return box(req.x, req.y, req.z, req.x, req.y, req.z); }

    static AreaBounds of(LadderRequest req) {
        if (req.height <= 0) throw new IllegalArgumentException("Invalid ladder height");
        return box(req.x, req.y, req.z, req.x, add(req.y, req.height - 1), req.z);
    }

    static AreaBounds rail(JsonNode path, String mode) {
        AreaBounds bounds = null;
        int depth = "bridge".equals(mode) ? 24 : 8;
        for (JsonNode point : path) {
            int x = point.get("x").asInt(), y = point.get("y").asInt(), z = point.get("z").asInt();
            // Include tunnel lining, headroom, and neighboring rail reconciliation.
            AreaBounds piece = box(add(x, -1), add(y, -depth), add(z, -1), add(x, 1), add(y, 3), add(z, 1));
            bounds = bounds == null ? piece : bounds.union(piece);
        }
        return bounds;
    }

    static AreaBounds structure(int x, int y, int z, int sx, int sy, int sz, String rotation) {
        if (sx <= 0 || sy <= 0 || sz <= 0) throw new IllegalArgumentException("Invalid structure size");
        int dx = sx - 1, dz = sz - 1;
        return switch (rotation) {
            case "NONE" -> box(x, y, z, add(x, dx), add(y, sy - 1), add(z, dz));
            case "CLOCKWISE_90" -> box(add(x, -dz), y, z, x, add(y, sy - 1), add(z, dx));
            case "CLOCKWISE_180" -> box(add(x, -dx), y, add(z, -dz), x, add(y, sy - 1), z);
            case "COUNTERCLOCKWISE_90" -> box(x, y, add(z, -dx), add(x, dz), add(y, sy - 1), z);
            default -> throw new IllegalArgumentException("Invalid structure rotation");
        };
    }
}
