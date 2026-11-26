# Spy mode

MCP clients mostly show "thinking…" while tools run. Spy mode is an opt-in side window for demos and
screencasts: a browser page that shows each tool call as it happens. That includes Starlark scripts
(syntax-highlighted, shown as soon as the call starts), the images tools return (terrain maps, Starlark
and schematic previews, region renders), and a one-line entry for every other call.

It only observes. The Minecraft mod and the schematic and Starlark services are unchanged, and a spy
failure never changes a tool result.

## How it works

```
MCP client ──stdio/SSE──> minecraft_mcp (call_tool) ──> handlers ──> mod / services
                                  │ start + end events
                                  ▼
                              SPY_DIR/   <── minecraft-spy viewer (polls, SSE) ──> browser :7095
```

- `minecraft_mcp/spy.py` is called from `MinecraftMCPServer.call_tool`.
  - **Start event:** `{call_id}-start.json`, written before the handler runs: tool name and sanitized arguments.
  - **End event:** `{call_id}-end.json`, written afterwards: status, duration, the first 600 characters of the text result, and image file names.
  - **Images:** saved as `{call_id}-{n}.png` beside the events.
  - Files are written atomically.
- `call_id` is `{epoch_ms}-{pid}-{seq}`, so several MCP processes (one per Claude Desktop conversation) can share one directory.
- The writer keeps the newest 200 calls and deletes older files.
- `minecraft_mcp/spy_viewer/` is a separate Starlette process. It polls the directory and pushes events to the page over SSE. It can be started and stopped independently of the MCP server.

## What is recorded

- **Arguments:**
  - `lock_id` and any `*token*` key are replaced with `•••`.
  - `source` (`build_starlark_structure`, `revise_build`, `save_starlark_script`) is kept whole for highlighting.
  - `blocks` arrays become a shape note (`[32×8×20 blocks]`), so `set_blocks`, `get_blocks` and the block-set build tasks show only that the call was made.
  - Other lists longer than 20 items become `[list: N items]`, and strings longer than 2,000 characters are truncated.
- **Results:**
  - `acquire_area_lock`, `update_area_lock` and `get_operation` text is recorded as `[redacted]` (it carries reservation tokens).
  - `structuredContent` is not recorded.

## Running it

```bash
# mcp/.env (relative paths resolve against mcp/)
SPY_DIR=../spy-data
```

Restart the MCP client so the server picks up the setting. Then:

```bash
cd mcp
uv run minecraft-spy            # http://127.0.0.1:7095 (options: --dir, --host, --port)
```

Using the page:
- The large stage follows the newest call that has a script or images.
- The list below shows every call, with a live timer while a call runs.
- Click a row to pin it. Press Esc or click **Live** to follow again.
- Syntax highlighting loads highlight.js from cdnjs. Offline, scripts show as plain text.

Leave `SPY_DIR` unset (or empty) to turn spy mode off. The spool directory is safe to delete at any time.
