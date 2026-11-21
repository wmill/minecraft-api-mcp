# Live-world render

`render_region` (MCP) shows what is actually in the world: a placed build with its surroundings, or any cuboid,
drawn by the Starlark preview renderer (iso, top, elevations, cutaways, sections, floor plans, real stair/slab
shapes). It shows the placed build in context: how it sits on the terrain, the seams with neighbouring builds, and
what a revision actually changed.

## Flow

```
MCP render_region ──GET /api/world/region/nbt──▶ mod: capture on the server thread ──▶ gzipped structure NBT
                  ──POST /renders (raw NBT)────▶ starlark-service: stored as rgn_<hash> in the artifact cache
                  ──GET /artifacts/rgn_…/images/{view}?cut_*──▶ existing preview pipeline ──▶ PNG
```

Everything is read-only: the capture route is a GET (outside `OperationHttp.mutation()` and MCP
`tracked_path()`), is never lock-checked, and records nothing.

## Choosing the region

- `build_id`: the build's recorded bounds (the union of its task coordinates, the same lookup `check_access`
  uses) widened by `margin` (default 6) on each horizontal side, `below` (default 3) under its lowest block
  and `above` (default 4) over its highest. The small `below` keeps a deep slab of ground from dominating the
  image. Undone or superseded builds still render; the response says so (`X-Build-Removed: true`), because
  the image shows what is there now.
- A cuboid: `x1,y1,z1,x2,y2,z2`, inclusive, normalized if reversed.

The region is clamped to the world's height range and capped at `REGION_RENDER_MAX_VOLUME` blocks (mod env,
default 1,000,000). The starlark service independently caps uploads at `STARLARK_MAX_NBT_BYTES` and
`STARLARK_MAX_ROOT_VOLUME` blocks.

Only already-loaded chunks are read. If any chunk is missing the mod returns 409 `chunks_not_loaded` instead of
loading or generating it (which `StructureTemplate.saveFromWorld` would otherwise do).

## Coordinates

The capture's minimum corner is its local origin (`X-Region-Origin`). `render_region` takes cuts in **world**
coordinates and converts them (`local = world - origin`); floor levels from `view="floors"` are converted back to
world Y in the result text. The service's own routes take cuts in local coordinates, like Starlark artifacts, but
label images in world coordinates: the origin is stored with the region, so a single `top`, side or `section`
view is framed with a world-coordinate grid, a scale bar and (top view) world-Y spot heights of the topmost
block in each grid cell. Those labels line up with `map_terrain`'s grid for the same area, but its spot heights
are standing Y (one above the top block). Pass `annotate=false` for the bare image.

## Caching

Region ids are `rgn_` + sha256(NBT bytes, origin, world)[:16]. Re-rendering an unchanged region gets the same id
and reuses its rendered previews; any block change produces a new id. Region artifacts live in the same cache as
compiled builds (sharded by id, LRU-evicted under `STARLARK_CACHE_MAX_BYTES`) and have no source or stages.

## Limits and notes

- Entities (mobs, item frames, armor stands) are not captured; block entities are, but the renderer draws blocks
  only.
- Rendering runs under `STARLARK_PREVIEW_TIMEOUT_S` (default 30s). Very large regions can exceed it (503,
  `render_unavailable`); render a build rather than a whole district, or raise the timeout.
- `view="floors"` detects storeys from solid-floor/headroom/roof patterns, which works for buildings but also
  finds hillside ledges and overhangs in terrain-heavy regions. Use it with `build_id` and a small margin, or
  pick explicit `cut_y` levels.
- The background colour is chosen from the silhouette like any artifact; with terrain in view it is usually slate.
