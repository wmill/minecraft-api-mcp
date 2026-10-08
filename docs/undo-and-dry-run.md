# Undo, redo and dry-run for NBT placements

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

## Undo and redo

Each placement saves a snapshot of its cuboid: blocks, air, and block entities. The
capture happens immediately before the write and inside the same lock critical section.
The placement response includes:

- `undo_available: true`, or
- `undo_available: false` with `undo_unavailable_reason`, one of `snapshots_disabled`,
  `snapshot_too_large`, `build_not_recorded` (the build database is unavailable), or
  `snapshot_write_failed`.

`undo_build(build_id)` (HTTP `POST /api/builds/{id}/undo`) restores the snapshot under
the caller's optional area lock:

- **Repeatable.** Undo saves the current region for redo, then restores the undo
  snapshot and sets the build to `REVERTED`. `redo_build(build_id)` (HTTP
  `POST /api/builds/{id}/redo`) restores the region exactly as it was before undo,
  including edits made before undo, and sets the same build to `COMPLETED`.
  Redo saves the current region for another undo. A duplicate undo or redo returns
  409. Responses include `undo_available` and `redo_available` for the next action.
- **Conflicts.** If later, unreverted recorded builds overlap the cuboid, undo/redo
  returns 409 `undo_conflict` / `redo_conflict` and lists up to five of them. Restoring would erase them.
  `force: true` restores anyway.
- **Locks.** Both tools accept `lock_id`; HTTP uses `X-Area-Lock-Id`. Force does
  not bypass reservations. Capture, restore and status persistence run serially
  on the server thread under the area lock.
- **Entities.** Mobs, item frames and other entities are neither removed nor
  respawned. Replaced blocks do not drop items.
- **Older builds.** Builds placed before this feature, or not recorded at all, cannot
  be undone (400).

`replay_build` re-executes queued task builds; it is not redo. Replay and direct
execution reject NBT placements without changing their status. Already-undone
builds whose snapshots were deleted by the old implementation cannot be recovered
automatically. Existing unconsumed undo snapshots remain compatible.
Reverted placement records cannot be edited or translated before redo.

Unrecorded edits that happen after the placement, such as players or `set_blocks`,
are overwritten by undo/redo without warning, but saved in the reverse snapshot.

## Storage

Snapshots are gzipped structure NBT files, `{build_id}.nbt` (undo) and
`{build_id}.redo.nbt` (redo), stored in
`BUILD_SNAPSHOT_DIR`. The default is `<server run dir>/build-snapshots`, which in
Docker is on the `minecraft_data` volume. `BUILD_SNAPSHOT_MAX_VOLUME` (default
2,000,000 voxels) caps the snapshotted cuboid. Larger placements still succeed but
cannot be undone. Keep both files to preserve repeatable undo/redo.

Before restoration, the reverse snapshot is written atomically and a
`{build_id}.pending` marker records the action. The marker is removed only after
world restoration and database status persistence succeed. A timeout is an unknown
outcome: inspect build status and the world before retrying. An unresolved marker
blocks both operations, including after restart, and preserves both snapshots.

Recovery is an administrator operation: stop the server, back up the two snapshots
and marker, inspect the recorded action and actual region state, and reconcile the
world and database status (`REVERTED` after undo, `COMPLETED` after redo). Remove
the marker only when they agree. Do not simply retry or remove it without inspecting
the world: a failed restore may have written part of the region.

## Live regression

`scripts/verify_undo_redo.py` checks the complete HTTP path in a disposable server
on API port 7071 (`-Dapi.port=7071`), with its own world and database. It requires
`motd=Disposable undo redo verification` in that server's properties. From
`starlark-service/`, run:

```sh
uv run python ../scripts/verify_undo_redo.py --snapshot-dir ../build/redo-smoke/build-snapshots
# Fully stop and restart the disposable JVM, then:
uv run python ../scripts/verify_undo_redo.py --snapshot-dir ../build/redo-smoke/build-snapshots --resume
```

The test uses a rotated 2x2x2 structure at (10,80,10), checks exact blocks and chest
inventory, edits before undo, repeated and concurrent calls, locks, forced overlap
restores, replay rejection, and restart persistence. It leaves its result in
`verification.json` alongside the disposable world's snapshot directory. It does
not connect to the main server on port 7070.
