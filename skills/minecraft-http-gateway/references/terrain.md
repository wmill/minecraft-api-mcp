# Terrain Smoothing

Use `terrain.py smooth` (`POST /api/world/terrain/smooth`) for natural ground smoothing or a flat footprint blended into surrounding terrain.

Survey and inspect the site first. Corners are inclusive, the region is limited to 10,000 columns, and its chunks must already be loaded. Heights are standing Y (first air above ground), matching the survey's suggested walking level.

```bash
uv run --project mcp python skills/minecraft-http-gateway/scripts/terrain.py smooth \
  --x1 100 --z1 200 --x2 159 --z2 259 --method bilateral --dry-run --output-prefix /tmp/smooth_
```

Always dry-run first. Inspect cut/fill estimates, `lock_check`, overlapping builds/reservations, and the maps written to `/tmp/smooth_before.png`, `/tmp/smooth_after.png` and `/tmp/smooth_change.png` (listed under `images`; without `--output-prefix` the images are dropped). Filter options: `--sigma`, `--sigma-range` (bilateral), `--radius` (median), `--iterations`, `--strength`, `--feather`, `--max-change`; `--px-per-block` and `--contour-interval` only affect the maps. Blue on the change map means fill, red means cut, and grey `x` columns are masked.

Choose `bilateral` to preserve cliffs and terraces, `gaussian` for rolling ground, or `median` for spikes and pits. For a build pad, use `--method pad --pad-y 70 --pad-x1 110 --pad-z1 210 --pad-x2 139 --pad-z2 239 --pad-blend 6`. Keep the region at least `blend` blocks wider than the footprint on every side. Masks and `max_change` can prevent reaching the target; inspect `pad_columns_at_target`.

Reserve the full write cuboid, using the reported bounds including vegetation height. Apply by rerunning the same command without `--dry-run` and with `--lock-id <token>`. Check the response and world afterward; retain the build id for `build_flow.py undo` / `redo`. A `TERRAIN_SMOOTH` build cannot be executed, replayed or cloned.

The operation clears vegetation even on unchanged ground. Logs count as vegetation unless stripped, so log cabins can be cleared; inspect the site and overlap reports carefully. Built/fluid columns and their eight neighbours are masked against reshaping. Dry runs do not write, load chunks or renew reservations. On 409 `terrain_changed`, survey and plan again; on a timeout, inspect build status and the world before retrying.

See `docs/terrain-smoothing.md` for parameter defaults and snapshot caveats, and `docs/openapi-minecraft.yaml` for the request/response contract.
