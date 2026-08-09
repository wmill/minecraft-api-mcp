# TODO: Starlark Build Service

Integration of [wmill/starlark-to-nbt](https://github.com/wmill/starlark-to-nbt) as an HTTP
sidecar service so MCP users (LLMs) can submit Starlark build scripts, get structured build
errors back, iterate (edit → build error → edit loop), and place successful builds into the
Minecraft world.

**Architecture decisions:**
- starlark-to-nbt vendored as a **git submodule** at `starlark-service/starlark-to-nbt`
- Service is **stateless**: full source per request; successful builds cached on disk by
  content hash (artifact id, deterministic output makes this safe); no Postgres
- New docker compose service on **port 7090**, profile `starlark`, mirroring `schematic-service`
- Builds run in a **killable, resource-limited subprocess** (the tool has no timeout/memory/
  output caps of its own) with a confined `load()` loader (upstream change)
- **No Java/mod changes** — placement reuses the mod's existing
  `POST /api/world/structure/place` (accepts gzipped NBT, returns `build_id`)
- Visual preview deferred (Phase 6)

```
MCP LLM → build_starlark_structure ──POST /build──▶ starlark-service ──subprocess──▶ runner (starlark_to_nbt)
        → place_starlark_structure ──GET /artifacts/{id}/nbt──▶ cache ──bytes──▶ mod /api/world/structure/place
```

## Phase 0 — Upstream changes (tools/starlark-to-nbt repo)

- [x] Add `pipeline.build_source(source, entry="build", props=None, root_size=None, filename="<input>", base_dir=None, loader_root=None) -> BuildResult` wrapping `starlark_runtime.evaluate_source`
- [x] Confined loader: optional `loader_root` on `_Loader`/`evaluate_source` — reject absolute paths, require resolution inside root, allowlist `lib/` first segment, sanitize load errors to uniform "module not found" (no file-existence oracle)
- [x] Wrap bare `ValueError` escapes as `BuildError` diagnostics: `pipeline._root_size` → `missing_root_size`, root `Box.from_size` → `invalid_box`, `serialize` → `serialize_error` (schema.py already wrapped user-data boxes as `invalid_ir`)
- [x] Upstream tests: `tests/test_build_source.py` — happy path, confinement allow/reject/sanitize, ValueError-wrapping cases
- [x] Update `docs/component-catalog.md` error-code list with the new codes
- [x] Full upstream test suite green (719 passed)
- [x] Commit + push upstream (`0ef0d56`)

## Phase 1 — Service skeleton (`starlark-service/`)

- [x] `git submodule add https://github.com/wmill/starlark-to-nbt starlark-service/starlark-to-nbt`
- [x] `pyproject.toml` (py≥3.13, path dep on submodule via `[tool.uv.sources]`)
- [x] `starlark_service/config.py` — frozen dataclass, `{**dotenv_values(.env), **os.environ}`, all `STARLARK_*` env vars with defaults
- [x] `starlark_service/cache.py` — `artifact_id = "slk_" + sha256({source, entry, props, root_size, lib_fingerprint})[:16]` (fingerprint = hash of `lib/*.star` contents, works without git in the container); sharded `{shard}/{id}.nbt` + `{id}.json` layout; LRU eviction over `STARLARK_CACHE_MAX_BYTES`
- [x] `starlark_service/app.py` — `create_app(config)` factory + module-level `app`
- [x] `POST /build` — success `{ok, artifact_id, cached, build_ms, size, block_count, entity_count, ground_level, y_offset, nbt_bytes, palette}`; build failure **200** `{ok: false, error_kind, diagnostics, hint}`; 400 malformed/oversized source; 429 queue full
- [x] `GET /artifacts/{id}` (404 if evicted, rebuild guidance) and `GET /artifacts/{id}/nbt` (`FileResponse`)
- [x] `GET /docs/catalog` — serve submodule's `component-catalog.md` as markdown
- [x] `GET /examples` + `GET /examples/{name}` (name regex `^[a-z0-9_]+$` traversal guard)
- [x] `GET /health` — `{ok, lib_dir_exists, lib_fingerprint, cache: {artifacts, bytes}}`
- [x] `starlark_service/main.py` uvicorn console script (port 7090)
- [x] `test_app.py` — build success (incl. `../lib` loads), diagnostics shape on bad script, cache hit `cached: true`, artifact 404, oversized source 400, examples/catalog routes
- [x] `test_cache.py` — artifact id stability, eviction at cap, lib fingerprint

## Phase 2 — Sandbox & limits

- [x] `starlark_service/runner.py` — subprocess worker: stdin JSON → `resource.setrlimit(RLIMIT_AS)` (no-op warn on macOS) → `build_source` → result JSON on stdout; tmp file + atomic rename into cache
- [x] `starlark_service/sandbox.py` — concurrency counter (`429` when full); wall-clock timeout → SIGKILL → `error_kind: timeout`; non-zero exit/garbled stdout → `crash`
- [x] Enforce `STARLARK_MAX_ROOT_VOLUME` pre-serialize and `STARLARK_MAX_NBT_BYTES` post-serialize → `error_kind: resource_limit`
- [x] `POST /build` runs through the sandbox from the start (no in-process interim step was needed)
- [x] `test_sandbox.py` — runaway loop → timeout kill; root volume / NBT byte caps → resource_limit; queue-full 429; failed builds leave no artifacts
- [x] Defaults documented in config: timeout 15s, memory 512MB, source 256KiB, root volume 2M voxels, NBT 16MiB, concurrency 2, cache 1GiB

## Phase 3 — MCP integration

- [x] `mcp/minecraft_mcp/config.py` — `starlark_service_url` + `STARLARK_SERVICE_URL` export (default `http://localhost:7090`)
- [x] `mcp/minecraft_mcp/client/starlark_service.py` — httpx client (build 60s, nbt 30s, json 10s timeouts)
- [x] `handlers/starlark.py`: `build_starlark_structure` — success summary (artifact id, size, block_count, y_offset, top palette, next-step hint); failure text with numbered diagnostics, codes, file:line where available, iteration hint, component-path note
- [x] `handlers/starlark.py`: `place_starlark_structure(artifact_id, x, y, z, world?, rotation?, apply_y_offset?=true)` — apply `y + y_offset`, call `place_nbt_structure_bytes`, surface `build_id`; 404 → "artifact evicted; rebuild (same source yields same id)"
- [x] `handlers/starlark.py`: `get_starlark_docs`, `list_starlark_examples`, `get_starlark_example` + `_unavailable()` graceful-degradation path
- [x] `tools/schemas.py` — `TOOL_*` constants + `TOOL_SCHEMAS` entries (rotation enum, coordinate blurbs per existing tools)
- [x] `tools/registry.py` — `TOOL_HANDLERS` entries
- [x] `mcp/test_starlark_tools.py` — registration + default-URL tests mirroring `test_schematic_tools.py`
- [x] (Drive-by) mcp dev deps: pytest + pytest-asyncio (`asyncio_mode = "auto"`) so `uv run pytest` works in `mcp/` — full suite 24 passed

## Phase 4 — Docker & compose

- [x] `starlark-service/Dockerfile` — `python:3.13-slim`, copy submodule, `pip install ./starlark-to-nbt && pip install .`, `ENV STARLARK_TOOL_DIR=/app/starlark-to-nbt STARLARK_CACHE_DIR=/data`, uvicorn CMD
- [x] `starlark-service/.dockerignore`
- [x] `docker-compose.yml` — `starlark-service` block (profile `starlark`, `127.0.0.1:7090:7090`, `./starlark-service-data:/data` **rw**, `restart: unless-stopped`, 3G memory limit)
- [x] Add `STARLARK_SERVICE_URL=http://starlark-service:7090` to the `mcp` compose service env
- [x] `.gitignore` — add `starlark-service-data/`
- [x] Container smoke test: image builds; in-container health, build (66ms), cache hit, and gzipped NBT download all verified. Found + fixed: 512MB `RLIMIT_AS` SIGABRTs the Rust starlark runtime — switched to `RLIMIT_DATA`, default `STARLARK_BUILD_MEMORY_MB=2048` (values below ~1024 abort every build; the container limit is the real boundary)
- [ ] E2E smoke with live Minecraft: `docker compose --profile starlark up` → build cottage source → place via MCP `place_starlark_structure` → verify `build_id` + in-world structure at y_offset-adjusted origin (needs the Minecraft server running)

## Phase 5 — Docs, OpenAPI, Bruno

- [x] `docs/openapi-starlark.yaml` — all routes incl. both `/build` response shapes
- [x] `bruno/Minecraft Starlark Service/` — health, build-success, build-error, artifact meta/nbt, catalog, examples
- [x] `CLAUDE.md` — architecture section, env vars, HTTP API list, MCP tools list, deps, test inventory, compose services, key patterns
- [x] `README.md` — service section + submodule clone note

## Phase 6 — Deferred

- [ ] Isometric PNG preview (`GET /artifacts/{id}/preview.png`) + MCP preview tool
- [ ] Combined `build_and_place` convenience tool
- [ ] Named script persistence / user script library
- [ ] Rate limiting / auth if exposed beyond localhost
- [ ] Build metrics; warm worker pool if subprocess cold-start latency matters
- [ ] Revisit docker-bake.hcl inclusion + Python CI
