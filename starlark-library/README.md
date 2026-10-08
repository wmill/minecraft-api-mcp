# Starlark script library

Saved Starlark build scripts, written by the starlark service (`POST /library`, MCP tool
`save_starlark_script`). Each entry is `<name>/meta.json` plus immutable `v<N>.star` versions.

Scripts load saved components by pinned path, e.g.
`load("../library/gothic_window/v2.star", "GothicWindow")`. Never edit or delete a
published `v<N>.star`: other scripts and cached artifacts depend on its exact contents.
Save a new version instead. This directory is meant to be committed.
