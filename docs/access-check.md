# Access check

`POST /api/world/access/check` (MCP `check_access`) is a read-only check of whether a placed structure can be
used: can a player get from the road to inside the front door, does the staircase actually reach the second
floor, is there a room nobody can get into. It is meant for the loop *place → check access → fix stairs, path or
terrain → check again*.

## Request

| Field | Default | Notes |
|---|---|---|
| `start` | required | Feet position outside, e.g. on a road. |
| `targets[]` | — | `{name?, x, y, z, via?}`, at most 16. `via` is a doorway (lower door block) the route must pass through. |
| `map` | — | `{build_id}` (the recorded build's task bounds, plus 2 above for standing on top) or `{x1,y1,z1,x2,y2,z2}`. At most 256 per side and 250,000 cells. Player profile only. |
| `profile` | `player` | `player` (own movement model) or `villager` (vanilla mob pathfinding). |
| `doors` | `wooden` | `wooden`: wooden/copper doors, trapdoors and all fence gates can be opened. `all`: iron ones too (assumes a button or plate). `none`: only already-open ones. |
| `max_nodes` | 50,000 | 1..200,000 expanded positions. |
| `margin` | 16 | 0..64 blocks of padding around start/targets/map that the search may use. |
| `include_route` | false | Adds each reachable target's route as turn points (at most 200). |

At least one of `targets` or `map` is needed. Positions are **feet positions**: the air block a player stands in.
If a position isn't standable, the column is tried at y+1, y−1 and y+2 (so a floor-block Y works) and the result
says `adjusted`. Otherwise the target is `invalid_target` with the nearest standable `suggestion`.

The search box is the union of start, targets, vias and map, padded by `margin`, clamped to world height and at
most 512×512 columns. Chunks holding the start, targets, vias or map must be loaded (else 400
`chunks_not_loaded`); unloaded chunks inside the margin are treated as an edge of the search.

## Player movement model (`access/PlayerAccessSearch`)

A breadth-first search over positions a 1.8-tall, one-block-wide player can occupy, in one of three modes:

- **Ground**: standing on a block whose collision top is above 0.6 in the block below (feet at its top: farmland,
  soul sand and fences included), or on a ≤0.6 partial block in the feet cell (slabs, carpet, closed bottom
  trapdoors). Body cells up to the head must be clear.
- **Climb**: the feet cell is in `#minecraft:climbable` (ladders, vines, scaffolding, ...).
- **Swim**: the feet cell holds water and has no collision (waterlogged blocks count as solid).

Moves: the 4 horizontal neighbours with a step of at most 0.6, a jump of at most 1.25 (the source column must be
clear up to the landed head height), walking up stairs (a rise of up to 1.0 onto a stair without jump headroom),
and drops of at most 3 blocks (any height into water) through a clear column. On a ladder you can climb up and
down, and step off the top (counted from one block above the top ladder block). Swimmers move up and down in
water and can climb out onto a bank one block up. Openable floors (trapdoors over a ladder hole) can be dropped
through. Lava, fire, campfires, magma, cactus, berry bushes, powder snow, wither roses and portals are never
entered.

Not modelled: diagonal moves (route lengths are Manhattan-like), sprint jumps and parkour, sneaking through
1.5-high gaps, boats, elytra, pearls, pistons and redstone, mobs or players in the way.

## Villager profile (`access/VanillaAccessProbe`)

An unspawned villager is placed at the start and handed straight to `PathNodeNavigator.findPathToAny` with a
`LandPathNodeMaker` (open doors can be entered, wooden doors opened unless `doors=none`, swimming allowed) over a
`ChunkCache` of the search box. This skips `EntityNavigation`, which refuses mobs not on the ground in the world
and moves targets vertically. A target is reachable only when `Path.reachesTarget()` and the path ends exactly on
it. Vanilla land pathing has no ladder support, so a floor reached only by ladder is unreachable for villagers.
`map` isn't supported.

## Results

Each target has a `status`:

- `reachable`: `route_length` moves (with `via_crossed` when a via was given).
- `unreachable`: `no_route_exists` (the reachable area was fully explored without reaching the box edge, so
  the start is enclosed), `no_route_within_search_box` (try a bigger `margin`), `via_unreachable`,
  `target_unreachable_from_via`, `via_door_cannot_be_opened` (the via is a door the policy can't open), or
  `no_route_within_limits` (villager).
- `unknown`: `node_limit` (raise `max_nodes`), `unloaded_chunks`, or `invalid_start`.
- `invalid_target`: `invalid_target` / `invalid_via`, with a `suggestion` when one is near.

Failures include `nearest_reached` (the closest position the search got to), which usually points at the gap.

`map` floods everything reachable from the start and reports, for the region's standing (ground-mode) positions:
`standable`/`reachable` totals, `floors[]` per feet Y (`standable`, `reachable`, `outdoor` = open to the sky
by the `MOTION_BLOCKING` heightmap, `fraction`), and up to 10 `unreached_regions` (connected groups by horizontal
neighbours ±1 Y), indoor groups first. An indoor group at an upper level means that floor has no working
stairs or ladder. Outdoor groups are usually roofs and wall tops. `complete: false` means the flood hit
`max_nodes`.

The work runs on the server thread with a 30 s wait (504 `access_timeout`). It never modifies blocks and is not
an operation (no `Idempotency-Key`, no area lock).
