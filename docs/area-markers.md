# Temporary area markers

Use `mark_area` to point out a proposed build site or explain a location in-game.
The server draws all twelve edges of an inclusive cuboid with colored particles,
adds a particle column up to 24 blocks above it, and optionally shows a floating
label facing the viewer. Vanilla Java clients work without a client mod or
resource pack.

Example MCP arguments:

```json
{
  "bounds": {
    "min_x": 100, "min_y": 64, "min_z": 200,
    "max_x": 119, "max_y": 70, "max_z": 219
  },
  "label": "Proposed station",
  "color": "cyan",
  "duration_seconds": 60,
  "world": "minecraft:overworld",
  "player": "Alex"
}
```

Omit `player` to show the marker to all nearby players. A named player must be
online in the requested world when creating the marker. Omit `label` for particles
only. Defaults are an empty label, yellow, 60 seconds, and the overworld.

The response includes `success`, `marker_id`, resolved `world`, `bounds`, `label`,
`color`, `duration_seconds`, and an ISO timestamp `expires_at`. Pass `marker_id`
to `remove_area_marker` to remove it early. Removal is idempotent: `removed` is
false if the marker was already removed or expired. Existing particles fade
naturally after emission stops.

## REST API

Create with `POST /api/world/markers`, using the same JSON body as above:

```bash
curl -X POST http://localhost:7070/api/world/markers \
  -H 'Content-Type: application/json' \
  -d '{"bounds":{"min_x":100,"min_y":64,"min_z":200,"max_x":119,"max_y":70,"max_z":219},"label":"Proposed station","color":"cyan"}'
```

Remove with `DELETE /api/world/markers/{marker_id}`. Invalid bounds, options,
unknown worlds, unavailable target players, and capacity errors return HTTP 400.

## Limits and lifecycle

- Each dimension is at most 128 blocks, with inclusive minima and maxima. Y must
  be within the world's height limits, leaving one block above `max_y` for the
  outline. X/Z must be within -29,999,984 to 29,999,984.
- Colors: `yellow`, `red`, `green`, `blue`, `cyan`, `purple`, `white`.
- Labels are at most 120 characters. Duration is an integer from 1 to 300 seconds.
- At most 16 markers are active per server. Geometry is capped at 217 particle
  points per marker and emitted every 10 server ticks.
- Players must be in the same world and within 128 blocks of the cuboid's center.
  The column is a visual hint, not a full beacon beam or distant waypoint.
  Client rendering settings and occlusion affect visibility. Labels can be seen
  through terrain; particle outlines cannot.
- Markers do not alter blocks, acquire or renew reservations, load chunks, or
  create saved entities. They can annotate reserved areas without a lock token.
  Labels are display entities sent only as packets to eligible viewers.
- Expiry uses wall-clock time, checked on server ticks. Labels are removed when
  viewers leave range or the world, on explicit removal, on expiry, and during
  server shutdown. Markers do not survive restart. Labels refresh every two
  seconds at normal tick speed so respawning clients recover the annotation.

## Validation

Automated coverage tests inclusive geometry, bounded particle counts, invalid
inputs, capacity, exact expiry, idempotent cleanup, and MCP discovery/calls/errors.
For a manual visual check, use a test world, substitute an online player and
nearby coordinates in the example, and verify both particles and the label.
Check targeted visibility with a second player, expiry, early removal, leaving
the world/range, and respawning. No live-client visual check is automated.
