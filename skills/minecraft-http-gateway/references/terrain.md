# Terrain Smoothing

Use `POST /api/world/terrain/smooth` for natural ground smoothing or a flat footprint blended into surrounding terrain. The current helpers have no dedicated smoothing subcommand; use `call_api.py`.

Survey and inspect the site first. Corners are inclusive, the region is limited to 10,000 columns, and its chunks must already be loaded. Heights are standing Y (first air above ground), matching the survey's suggested walking level.

```bash
uv run --project mcp python skills/minecraft-http-gateway/scripts/call_api.py \
  post /api/world/terrain/smooth \
  --data '{"x1":100,"z1":200,"x2":159,"z2":259,"method":"bilateral","dry_run":true}'
```

Always dry-run first. Inspect cut/fill estimates, `lock_check`, overlapping builds/reservations, and the base64 PNG fields `before_png`, `after_png`, and `change_png`. Decode these fields to files to view them; the raw helper prints JSON rather than saving images. Blue on the change map means fill, red means cut, and grey `x` columns are masked.

Choose `bilateral` to preserve cliffs and terraces, `gaussian` for rolling ground, or `median` for spikes and pits. For a build pad, use `method: "pad"` and `pad: {"y": 70, "x1": 110, "z1": 210, "x2": 139, "z2": 239, "blend": 6}`. Keep the region at least `blend` blocks wider than the footprint on every side. Masks and `max_change` can prevent reaching the target; inspect `pad_columns_at_target`.

Reserve the full write cuboid, using the reported bounds including vegetation height. Apply the inspected payload with `dry_run: false` and `--lock-id <token>`. Check the response and world afterward; retain the build id for `build_flow.py undo` / `redo`. A `TERRAIN_SMOOTH` build cannot be executed, replayed or cloned.

The operation clears vegetation even on unchanged ground. Logs count as vegetation unless stripped, so log cabins can be cleared; inspect the site and overlap reports carefully. Built/fluid columns and their eight neighbours are masked against reshaping. Dry runs do not write, load chunks or renew reservations. On 409 `terrain_changed`, survey and plan again; on a timeout, inspect build status and the world before retrying.

See `docs/terrain-smoothing.md` for parameter defaults and snapshot caveats, and `docs/openapi-minecraft.yaml` for the request/response contract.
