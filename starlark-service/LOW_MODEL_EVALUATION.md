# Custom-building evaluation

Compare the baseline and updated interfaces using the same exact model ID,
reasoning setting, system instructions, and task text. Run each task three
times in fresh conversations. Give each run up to three compilation attempts;
do not provide manual script repairs. Record results even when a build fails.

Use a disposable flat test world, with surface blocks at Y=63 and walking
plane Y=64. Reset the test area between runs, including entities. These are
manual evaluation instructions, not an automated world-modifying test.

## Task prompts

1. Build an original 13x11 furnished timber cottage at (100,64,100), with a
   south-facing entrance, gable roof, bed, table, and windows on two sides.
2. Build an original garden scene within 17x17 blocks at (150,64,100), with
   a pergola, two benches, a flower bed, and lighting. Preserve clear walking paths.
3. Build an original market within 21x21 blocks at (200,64,100), with four
   differently colored stalls facing a central square from all four directions.
4. Build an original underground room within 15x15 blocks at (250,64,100),
   with its floor below the surface, a usable stair entrance, lighting, and a chest.
   Align its surface entrance with the requested walking plane.

## Scorecard

Record repository and compiler revisions; model ID and reasoning setting;
task/run number; first-compilation success; total compilation attempts;
documentation bytes returned; total tool calls; elapsed wall time; final
artifact/build IDs; placement status; and any manual intervention.

After placement, inspect requested features, correct orientation/ground level,
accessible entrances and paths, supported furniture, and unintended terrain
changes. Mark each requirement pass/fail and retain screenshots from consistent
angles. Compilation success alone is not visual or gameplay correctness.

Compare first-compilation success rates and median tool calls/time across the
12 runs per version. Report visual failures alongside speed improvements.
Do not claim a low-model improvement until these model runs have been measured.

## Deployment and verification

Ship the matching compiler submodule, Starlark service, and MCP update together.
Rebuild their images; no database migrations or cache deletion are required.
For a local service checkout, run `uv sync --reinstall-package starlark-to-nbt`
from starlark-service after changing compiler source (the dependency is not editable).
Existing source scripts and artifact IDs remain usable while cached.

HTTP `/docs/catalog` without selectors still returns the full reference;
the MCP docs tool now defaults to the short quickstart. Build tool calls without
placement still only compile. Calls with placement can replace blocks and spawn
entities; never automatically retry an unknown placement outcome.

Run `uv run pytest` from the compiler, service, and MCP projects. Tests use
temporary artifacts and simulated placement; they do not place blocks in a world.
