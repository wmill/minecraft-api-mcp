# Minecraft API and MCP

Adds a REST API to Minecraft and implements an MCP server so that you can play around with it in Claude Desktop or other LLMs.

# Basics and setup

There are MCP clients like Claude Desktop or Goose which connect to the LLM and run MCP commands with the server.

Next there is the MCP server, which for this project runs locally from the `mcp` directory.

## MCP Server Config

I'm running everything with uv. You should be able to do a `uv sync` then `uv run minecraft_mcp.py` to start it up.
You can also start it in sse mode with `uv run minecraft_mcp.py --transport sse --port 3001`.

I'm using python `3.13.9`. Some of the dependencies don't support 3.14 yet.

Try `npx @modelcontextprotocol/inspector uv run  minecraft_mcp.py` to make sure it's listing tools. 

The MCP server will aim at localhost:7070 by default.

You can change this with `mcp/.env`, it will check the BASE_URL value, eg `BASE_URL="http://localhost:7070"`

The optional schematic service defaults to `http://localhost:7080`. Override it in `mcp/.env` with:

```bash
SCHEMATIC_SERVICE_URL="http://localhost:7080"
```

## MCP Client config

There's a sample Claude Desktop Config in `mcp/mcp_config.json`. You'll need to change the paths.

To get it working with goose I needed to set up a script in my $PATH,

```bash
cd /path/to/minecraft-api-mcp/mcp
uv run minecraft_mcp.py
cd -
```

Then tell it to run the script.

Visual Studio Code was able to use the Claude Desktop settings after going through some config options.

## Minecraft Server setup

First you'll need the postgres image up and running `docker compose up -d postgres`

After that the gradle runServer command worked for me. Kudos to the fabric devs.

## Schematic service

There is an optional schematic catalog service in `schematic-service/`. It searches metadata from local schematic analysis and serves converted vanilla NBT files so MCP can place them through the existing `/api/world/structure/place` endpoint.

The local data lives under `schematic-service-data/` and is intentionally ignored by git. Current expected inputs are:

- `schematic-service-data/schematic_catalog_gemma3.json` - primary AI-generated catalog
- `schematic-service-data/Schematics-nbt/{schematic_id}.nbt` - converted vanilla NBT files
- `schematic-service-data/schematic-images/{schematic_id}/meta.json` - image/conversion metadata used for enrichment

Start the optional stack with:

```bash
docker compose --profile schematics up -d elasticsearch schematic-service
curl -X POST http://localhost:7080/index/rebuild
```

The schematic service is not required for normal Minecraft development. You can still run only Postgres and the Minecraft server; schematic MCP tools will report that the service is unavailable.

For local service development:

```bash
cd schematic-service
uv run uvicorn schematic_service.app:app --host 0.0.0.0 --port 7080
```

Useful endpoints:

- `GET http://localhost:7080/health`
- `POST http://localhost:7080/index/rebuild`
- `GET http://localhost:7080/schematics/search?q=tower&limit=5`
- `GET http://localhost:7080/schematics/2`
- `GET http://localhost:7080/schematics/2/nbt`

MCP tools added for this flow:

- `search_schematics`
- `get_schematic`
- `place_schematic`

## Rail planning debug loop

For a manual end-to-end rail planning regression check, run:

```bash
uv run python skills/minecraft-http-gateway/scripts/rail_debug_e2e.py \
  --start-x 0 --start-y 64 --start-z 0 \
  --end-x 200 --end-y 64 --end-z 200
```

The harness will:
- start `docker compose up -d postgres` unless `--skip-postgres` is passed
- start `./gradlew runServer` unless `--reuse-server` is passed
- wait for `http://localhost:7070/api/test`
- create a build, run `plan-rail`, poll until the planning job completes, then audit the build
- exit non-zero if planning fails or audit reports any errors

It writes diagnostics to `logs/rail-debug/<timestamp>/summary.json` and `server.log`.
