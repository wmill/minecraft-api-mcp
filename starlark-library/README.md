# Starlark script library

Saved Starlark build scripts, written by the starlark service (`POST /library`, MCP tool
`save_starlark_script`). Each entry is `<name>/meta.json` plus immutable `v<N>.star` versions.

Scripts load saved components by pinned path, e.g.
`load("../library/gothic_window/v2.star", "GothicWindow")`. Never edit or delete a
published `v<N>.star`: other scripts and cached artifacts depend on its exact contents.
Save a new version instead. This directory is meant to be committed.

## Reusable magical district components

These versions have been compiled as nested components in all four rotations.
Use the saved `build()` entry for standalone placement with its ground offset;
imported components leave ground metadata to the enclosing root.

| Pinned module | Exports |
| --- | --- |
| `moonwell_garden/v2.star` | `Moonwell`, `RunePylon`, `MoonwellGarden` |
| `astral_armillary_observatory/v1.star` | `AstralObservatory`, `AstralArmillary` |
| `starbloom_arcade/v1.star` | `StarKiosk`, `RuneArch`, `CrystalObelisk`, `StarbloomArcade` |
| `astral_lantern_walk/v2.star` | `LanternWalk`, `ObservatoryApproach` |
| `wayfarer_stable/v2.star` | `WayfarerStable` |
| `ash_and_anvil_smithy/v2.star` | `AshAndAnvilSmithy` |

For example, this composes two compact pieces without importing a whole site:

```python
load("../library/starbloom_arcade/v1.star", "StarKiosk", "RuneArch")

def build():
    return component(
        name="ArcaneMarketEntrance",
        props={},
        min_size=[17, 11, 7],
        body=group([
            at([0, 0, 0], StarKiosk()),
            at([10, 0, 2], RuneArch()),
        ]),
    )
```

The compact arcade pieces use fixture placement and require empty or carved space.
The complete garden, observatory, arcade, and walk include site clearance/supports;
inspect their dry-run footprint before placing them. Stable and smithy interiors
are sparse and require terrain clearance. Their v2 signs sit beside their entrances,
fixtures have supports, and their standalone walking plane is local Y=2.

`RunePylon` accepts integer heights of at least 5. `LanternWalk` takes local walking
levels of at least 3, with adjacent levels differing by at most one block; v2 also
supports the minimum one-column path. Use `get_starlark_script` for exact signatures,
sizes, ground offsets, and source before composing a new scene.
