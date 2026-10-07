# Site survey

`survey_site` assesses a chosen rectangular footprint without changing blocks,
loading/generating chunks, or acquiring/renewing reservations.

```json
{"x1": 100, "z1": 200, "x2": 139, "z2": 239, "world": "minecraft:overworld"}
```

The equivalent HTTP request is `POST /api/world/blocks/survey`. Corners are
inclusive and may be reversed; this example surveys 40 by 40 columns. The maximum
is 10,000 columns. All intersecting chunks must already be loaded and ready.
Ceiling dimensions, including the Nether, are not supported by this surface survey.

## Measurements

The server scans at most 64 blocks downward from each column's `WORLD_SURFACE`,
skipping air, logs, leaves, and non-colliding vegetation. The first remaining
collision surface must have a full supporting top face. Its block Y plus one is
the integer walking-plane Y. Unsupported surfaces, void columns, and scans that
reach their depth limit remain unresolved, with reason counts in `ground`.

- `ground`: resolved/unresolved counts; minimum, maximum, lower median, and range
  of resolved walking-plane heights.
- `slope`: mean and maximum absolute height difference between orthogonally
  adjacent resolved columns, in blocks per horizontal block. Each edge is counted
  once; a single column has zero adjacent pairs and zero slope.
- `water`, `lava`, `vegetation`: number and fraction of footprint columns where
  each was observed in the sampled interval. These flags may overlap; waterlogged
  vegetation contributes to both water and vegetation coverage.
- `grading`: lower median of dry ground as `suggested_walking_y`, with sums of
  positive ground-minus-target and target-minus-ground differences as `cut_blocks`
  and `fill_blocks`. Submerged resolved ground contributes to those volumes.
  Any unresolved ground, any lava, or no dry ground suppresses the suggestion and
  volumes, returning null values and an `unavailable_reason`.

The suggested Y is a **target after site preparation**, not a claim that the
current site is safe to walk on or ready for placement. Cut/fill is a height-based
estimate, excluding vegetation removal, drainage, cavities, and material suitability.
Tree roots below the first ground surface and other buried obstructions are not inspected.
Logs/leaves can be player construction; roofs can count as ground.

## Occupancy

Reservations and recorded builds are queried over the footprint and the world's
full vertical range. This can include underground builds and elevated reservations.
Each group returns `status`, `total`, `truncated`, and up to five `entries`.
Labels/names are capped at 80 characters. Reservation entries never include tokens.
Build entries include ID, name, and status, including unfinished/failed records.

If the build database was not initialized or a query fails, `builds.status` is
`unavailable`, `total` is null, and `entries` is empty. This is not zero occupancy.
Records do not prove blocks are still present, and unrecorded player construction
is not covered. The survey never declares a footprint "free".

`surveyed_at` records terrain-sampling completion. Terrain, lock, and database
observations are not one atomic snapshot. Reserve explicitly before placement;
the survey does not stop another agent from claiming or editing the footprint.

## Errors and compact output

Invalid coordinates, oversized footprints, unknown worlds, unsupported ceiling
dimensions, and missing chunks return HTTP 400. `unloaded_chunks` includes
`missing_chunks` as chunk-coordinate `{x, z}` objects. A server-thread wait timeout
returns HTTP 504 with `survey_timeout`. MCP preserves these errors in structured
content and sets `isError`.

Successful responses contain statistics and capped overlap summaries, never a raw
grid. Use `get_heightmap` when individual heights are needed: bounds are inclusive,
`heights[x_offset][z_offset]` starts at minimum X/Z, and values are first-air Y above
the surface selected by the heightmap type. Water and treetops are not necessarily
usable walking ground.

Existing heightmap, placement, and lock APIs keep their behavior. Deploy the updated
mod and MCP together to expose the new tool; no database migration is needed.
