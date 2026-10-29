# Terrain smoothing

`smooth_terrain` (HTTP `POST /api/world/terrain/smooth`) reshapes a region's natural
ground for a build site. Use it instead of flattening with `fill_box`: it keeps the
character of the land while removing the noise.

```json
{"x1": 100, "z1": 200, "x2": 159, "z2": 259, "method": "bilateral", "dry_run": true}
```

Always dry-run first. A dry run writes nothing and returns three base64 PNGs drawn
by `TerrainMapRenderer`:

- `before_png` and `after_png`: relief maps of the ground. Both use the same grey
  ramp and contour interval, so they compare directly.
- `change_png`: a change map. Blue means raised (fill) and red means lowered (cut),
  with signed labels at grid-cell centres. Masked columns are grey and marked `x`.

The dry run also returns cut/fill numbers, a lock check, overlapping reservations and
builds, and whether applying would be undoable. Repeat the call without `dry_run` to
apply it.

## Methods

| method | parameters | use for |
|---|---|---|
| `bilateral` | `sigma` (2), `sigma_range` (2), `iterations` (1) | Smoothing bumps while keeping cliffs and terraces; the usual choice. Steps much taller than `sigma_range` survive. |
| `gaussian` | `sigma` (2), `iterations` (1) | Soft, rolling ground. |
| `median` | `radius` (2), `iterations` (1) | Removing spikes, pillars and pits while keeping ridge and cliff lines. |
| `pad` | `pad: {y, x1, z1, x2, z2, blend (6)}` | A flat footprint at `pad.y` that eases back into the existing ground over `blend` blocks. |

Common parameters:

- `strength` (0–1, default 1): the fraction of the change to apply.
- `feather` (default 3, or 0 for `pad`): the number of blocks over which the effect
  fades in from the region edge, so the result joins the untouched terrain outside.
- `max_change` (default 16): the largest cut or fill in any one column.
- `px_per_block` and `contour_interval`: dry-run map options.

For `pad`, the footprint must lie inside the region. Make the region at least `blend`
blocks bigger than the footprint on every side, or the slope back to the existing
ground gets cut off at the region edge. Footprint columns ignore `strength` and
`feather`. Only masks and `max_change` stop them reaching `pad.y`;
`pad_columns_at_target` reports how many did.

All heights are standing Y: the first air block above the ground, the same as
`survey_site`'s `suggested_walking_y` and the `map_terrain` labels.

## How it works

1. **Sample** (server thread). For each column in the region, plus a context margin
   equal to the filter radius, the server walks down from `WORLD_SURFACE` past air,
   logs, leaves, plants, snow layers and other vegetation to the ground. Each column
   is then classified:
   - `ground`: grass, dirt, sand, gravel, stone or snow block.
   - `fluid`: water, lava or ice.
   - `built`: anything else, including stripped logs, farmland and paths.
   - `unloaded`: a margin column in an unloaded chunk.

   All chunks in the region must already be loaded; none are loaded or generated.
2. **Plan** (HTTP thread, pure `TerrainSmoother`). The filter runs over the ground
   heights. Masked columns keep their height and are left out of every
   neighbourhood. Margin columns are read but never planned. Each column moves
   `strength × edge_fade × (target − old)`, rounded and clamped to `max_change`.
   Masked columns never change, and neither do the 8 neighbours of a built or fluid
   column: walls are not undercut and water is not opened up.
3. **Write** (server thread, inside `AreaLockService.execute`):
   1. Re-sample the region. If any column changed since planning, nothing is
      written and the response is 409 `terrain_changed`.
   2. Snapshot the write bounds.
   3. Clear vegetation in `ground` and `fluid` columns (with neighbour updates, so
      detached leaves can decay).
   4. Reshape each ground column without neighbour updates. A cut sets air down to
      the new surface and re-places the column's top block (such as `grass_block`).
      A fill builds up with the block found below the top (such as dirt), then the
      top block.

   Vegetation on `built` columns, such as a roof garden, is left alone.
4. **Record**: a `TERRAIN_SMOOTH` build is recorded with the same task-data shape
   as an NBT placement, so `undo_build` and `redo_build` work unchanged. These
   builds cannot be executed, replayed or cloned. The write bounds run from one
   block below the lower of the old and new ground up to the highest vegetation.

## Limits and caveats

- At most 10,000 columns. The filter radius is capped at 16 blocks.
- Logs count as vegetation unless stripped, so a log cabin inside the region is
  cleared. Check the change map and the `builds` overlap before applying; undo
  restores it.
- Entities are not captured by the undo snapshot.
- Rounding to whole blocks means very fine noise (a perfect 1-block checkerboard)
  survives. Real terrain smooths as expected.
- Error codes: 400 `invalid_request` / `unloaded_chunks`; 409 `area_locked` /
  `outside_area_lock` / `invalid_area_lock` / `terrain_changed`. Dry runs may return
  504 `timeout`; writes instead return a [pollable 202 operation](operations-and-queues.md)
  when the HTTP wait expires, and finish through build and snapshot persistence.
