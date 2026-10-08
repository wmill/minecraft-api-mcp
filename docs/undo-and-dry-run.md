# Undo and dry-run for NBT placements

Applies to every NBT placement: `place_nbt_structure`, `place_schematic`, and
`place_starlark_structure` / `build_starlark_structure` with `placement`. All of
them go through `POST /api/world/structure/place`.

## Dry run

Pass `dry_run=true` (MCP) or the `dry_run=true` form field (HTTP). Nothing is written,
no chunks are loaded or generated, and the area lock is not renewed.

```json
{"success": true, "dry_run": true, "bounds": {...},
 "overwrite": {"template_blocks": 2400, "replaced": 310, "unchanged": 40, "air_carved": 120,
               "placed_into_air": 2050,
               "replaced_by_category": {"terrain": 250, "logs_leaves": 48, "vegetation": 12,
                                        "fluids": 0, "block_entities": 0, "other": 0},
               "top_replaced": [{"block": "minecraft:dirt", "count": 180}, ...]},
 "lock_check": {"ok": true}, "reservations": {...}, "builds": {...}, "undo_snapshot": true}
```

- Template cells are compared with the world by block id; block states are ignored.
  Only the first palette is used, and processors are not applied.
- `air_carved` counts existing blocks the template would replace with air. NBT
  placement writes air cells, so this is how a structure wipes out terrain or a
  neighbouring build.
- `lock_check` runs the same lock authorization as a real placement. It reports
  `ok: false` with the error code instead of failing the request.
- `reservations` and `builds` have the same shape as in `survey_site`. Reverted builds
  are excluded.
- Unloaded chunks return 400 `unloaded_chunks` with `missing_chunks`.

## Undo

Each placement saves a snapshot of its cuboid: blocks, air, and block entities. The
capture happens immediately before the write and inside the same lock critical section.
The placement response includes:

- `undo_available: true`, or
- `undo_available: false` with `undo_unavailable_reason`, one of `snapshots_disabled`,
  `snapshot_too_large`, `build_not_recorded` (the build database is unavailable), or
  `snapshot_write_failed`.

`undo_build(build_id)` (HTTP `POST /api/builds/{id}/undo`) restores the snapshot under
the caller's optional area lock:

- **One-shot.** The build becomes `REVERTED` and the snapshot file is deleted.
  A second undo returns 409.
- **Conflicts.** If later, unreverted recorded builds overlap the cuboid, undo
  returns 409 `undo_conflict` and lists up to five of them. Restoring would erase them.
  `force: true` restores anyway.
- **Entities.** Mobs, item frames and other entities spawned by the placement are
  not removed. Replaced blocks do not drop items.
- **Older builds.** Builds placed before this feature, or not recorded at all, cannot
  be undone (400).

Unrecorded edits that happen after the placement, such as players or `set_blocks`,
are overwritten by an undo without warning.

## Storage

Snapshots are gzipped structure NBT files, `{build_id}.nbt`, stored in
`BUILD_SNAPSHOT_DIR`. The default is `<server run dir>/build-snapshots`, which in
Docker is on the `minecraft_data` volume. `BUILD_SNAPSHOT_MAX_VOLUME` (default
2,000,000 voxels) caps the snapshotted cuboid. Larger placements still succeed but
cannot be undone. Deleting a snapshot file only disables undo for that build.
