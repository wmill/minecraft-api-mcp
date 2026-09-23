# Area reservations

Area locks let multiple MCP conversations build on one Minecraft server. They are optional: ordinary writes still work in unreserved space. A write overlapping a reserved cuboid needs its token, and a token-bearing write must fit entirely inside that cuboid and world.

## MCP workflow

1. Read the player's location once. Choose a cuboid covering the intended build, its clearance, and its foundations.
2. Call `acquire_area_lock`:

   ```json
   {
     "world": "minecraft:overworld",
     "label": "house beside the river",
     "bounds": {
       "min_x": 100, "min_y": 50, "min_z": 200,
       "max_x": 131, "max_y": 95, "max_z": 231
     }
   }
   ```

   The response contains `lock_id`, the canonical world, bounds, label, `expires_at`, and `idle_timeout_seconds`. Bounds are inclusive, so these X/Z coordinates reserve 32 by 32 blocks.

3. Pass that `lock_id` as a top-level argument on placement tools and `execute_build`/`replay_build`. For example:

   ```json
   {
     "lock_id": "<returned token>",
     "x1": 100, "y1": 60, "z1": 200,
     "x2": 110, "y2": 60, "z2": 210,
     "block_type": "minecraft:stone"
   }
   ```

   For `build_starlark_structure`, put `lock_id` at the top level alongside `placement`, not inside `placement`.

4. During long planning, call `update_area_lock` with just `lock_id` to renew. Supply `bounds` as well to explicitly resize. A failed resize keeps the previous reservation and expiry; the world cannot change.
5. Call `release_area_lock` when finished. Repeating release is harmless. If cleanup never happens, the reservation expires automatically.

Keep the original build location if the player moves. A lock conflict must not cause automatic relocation, reacquisition, or a retry without the token. `list_area_locks` shows occupied areas and expiry times; it never returns other conversations' usable tokens. Optional `world` and `bounds` arguments filter the list.

## Expiry and queued work

The default idle timeout is 900 seconds. Set `AREA_LOCK_IDLE_SECONDS` on the Minecraft server to change it; Docker Compose forwards this variable with a default of 900.

Successful placements and explicit renewals/resizes restart the timer. Reads, previews, compilation, queue edits, status polls, rejected requests, and idle MCP connections do not. There is no automatic heartbeat. An operation already writing keeps its authorization until it finishes.

Queued builds carry the token from execute/replay into each task. Checks happen when a task actually writes, so a reservation acquired or expired after submission is respected. On a lock failure the build stops and becomes `FAILED`: completed tasks remain placed and later tasks remain queued. The failed task's status response includes `lock_error` metadata. After explicitly resolving the reservation, `execute_build` retries failed/pending tasks while skipping completed tasks; `replay_build` resets replayable tasks.

Reservations live in Minecraft server memory and do not require PostgreSQL. MCP disconnects/restarts do not remove them; Minecraft server restarts do. Old tokens fail after expiry or restart, even when the area is now unreserved.

## HTTP interface

| Method and path | Request | Result |
|---|---|---|
| `POST /api/area-locks` | JSON `bounds`, optional `world` and `label` | Reservation including token |
| `GET /api/area-locks` | Optional `world` and all six bounds query parameters | `{"locks": [...]}` without tokens |
| `PATCH /api/area-locks/{lock_id}` | `{}` to renew, or `{"bounds": {...}}` to resize | Updated reservation including token |
| `DELETE /api/area-locks/{lock_id}` | No body | `{"success": true}` |

Placement and execute/replay requests take the token in `X-Area-Lock-Id`, including multipart NBT uploads. HTTP 409 responses carry one of:

- `area_locked`: another active reservation overlaps the requested bounds; includes reservation metadata and expiry.
- `outside_area_lock`: the operation extends outside its reservation or uses another world; includes both requested and reserved coordinates.
- `invalid_area_lock`: the token is unknown or expired.

Malformed bounds and other ordinary input validation errors use HTTP 400. The [OpenAPI contract](openapi-minecraft.yaml) documents the routes and types.

## Placement coverage

Checks cover block arrays/fills, doors, stairs, windows, torches, signs, ladders, NBT structures (including schematic and Starlark placement), direct fire placement, and queued tasks including rails. Bounds conservatively enclose the whole operation: sparse arrays can include empty space between writes, stairs include four blocks of clearance, rail supports can reach 24 blocks down for bridges, and rotation can place NBT blocks at negative offsets from the supplied origin.

Reservation conflicts reject a placement before its first write. This is not rollback for unrelated runtime failures. Player actions, entity behavior, later physics/fire/fluid effects, and build-record editing are outside these API reservations.

## Verification

Run `./gradlew test` from the repository root and `uv run pytest` from `mcp/`. Tests cover acquisition races, delayed writes, expiry, resize, rotated bounds, queued-task stopping, HTTP contracts, multipart token forwarding, and concurrent MCP calls.
