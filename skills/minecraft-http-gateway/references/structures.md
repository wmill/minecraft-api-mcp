# Starlark Builds And Schematics

Both produce vanilla structure NBT that is placed through the Minecraft API's `POST /api/world/structure/place`. The scripts fetch the NBT from the service and upload it for you, so placements get dry runs, area-lock checks, and undo like any other NBT placement.

## Starlark Build Service (`starlark.py`, port 7090)

Write a build as a Starlark script, compile it to NBT, look at it, then place it.

### Learn the API

```bash
S=skills/minecraft-http-gateway/scripts
uv run python $S/starlark.py docs                       # quickstart
uv run python $S/starlark.py docs --topic components    # component index
uv run python $S/starlark.py docs --component ConeRoof  # one component
uv run python $S/starlark.py examples
uv run python $S/starlark.py example --name arcane-tower
```

Other topics: `dsl`, `composition`, `errors`, `library`, `math`, `full`, or a component module (`structural`, `roofs`, `openings`, `fixtures`, `outdoor`, ...).

### Edit, build, preview loop

```bash
uv run python $S/starlark.py build --file tower.star --props '{"height": 14}'
uv run python $S/starlark.py image --artifact-id slk_... --view sheet --output /tmp/tower.png
```

- `build` takes `--entry` (default `build`), `--props` (JSON keyword args), `--root-size X Y Z`, and `--file -` for stdin.
- On success it prints `artifact_id`, `size`, `block_count`, `palette`, `ground_level` and `y_offset`. On failure it prints `ok: false`, an `error_kind` and numbered `diagnostics` with component paths and coordinates, and exits 2. Fix and rebuild.
- Identical source gives the same `artifact_id`; artifacts are cached and regenerate from the same source if evicted.
- `image` views: `sheet` (labelled 3x2 grid), `iso` (from the south-east), `top` (north up, east right), and `north`/`south`/`east`/`west` elevations seen from that side. `--max-px` is 64-1024. Renders sit on a slate background so white blocks stay visible. Look at the sheet before placing; it catches most layout mistakes.
- Script basics: execution phases run structure, then carve, then fixtures. Carve overwrites structure, so exclude cells you want to keep; fixtures only fill empty cells. There is no `sum()`; use a loop. A root built by composition needs an explicit `min_size`.

### Shared script library

Saved scripts can be forked or `load()`ed by later builds:

```bash
uv run python $S/starlark.py library-search --q windmill --max-x 40
uv run python $S/starlark.py library-get --name danish_windmill          # versions, exports, signatures, used_by
uv run python $S/starlark.py library-source --name danish_windmill --output wm.star
uv run python $S/starlark.py library-save --name my_tower --file tower.star \
  --title "Mage tower" --description "Exports MageTower(height). build() is a 9x20x9 tower." \
  --tag tower --tag fantasy --author <you>
```

- Load a saved component with `load("../library/<name>/v<N>.star", "Component")`. Vendored components load from `../lib/<module>.star`.
- Versions are immutable. Saving again under the same name adds `v<N+1>`. Identical re-saves are no-ops.
- A save is rebuilt first and rejected (exit 2, diagnostics) if it fails. `--artifact-id` saves the source, entry and props of a previous build. `--parent name@N` records lineage when forking.
- Export UpperCamelCase component functions so others can reuse them, and describe their parameters and size in `--description`.

### Place

```bash
uv run python $S/starlark.py place --artifact-id slk_... --x 100 --y 64 --z 200 --rotation CLOCKWISE_90 --dry-run
uv run python $S/starlark.py place --artifact-id slk_... --x 100 --y 64 --z 200 --rotation CLOCKWISE_90 --lock-id <token>
```

`--y` is the walking level: the artifact's `y_offset` (from `ground_level`) is added automatically unless you pass `--no-y-offset`. The output includes the Minecraft placement response, with the build id for `build_flow.py undo`.

## Schematic Catalog (`schematics.py`, port 7080)

A searchable catalog of pre-made, converted schematics.

```bash
uv run python $S/schematics.py tags --limit 30
uv run python $S/schematics.py search --q "medieval tavern" --size-category medium --has-interior true
uv run python $S/schematics.py get --schematic-id 3144
uv run python $S/schematics.py image --schematic-id 3144 --view sheet --output /tmp/3144.png
uv run python $S/schematics.py place --schematic-id 3144 --x 100 --y 64 --z 200 --dry-run
```

- Search returns only placeable schematics unless you pass `--include-unplaceable`. It falls back to a local search when Elasticsearch is down; `source` in the output says which was used.
- `get` includes the size and `image_metadata.placement.ground_level`. `place` sinks the schematic by `ground_level`, so `--y` is the walking level. `--no-ground-offset` disables this.
- Image views and orientation match the Starlark previews (on a white background).
