# Minecraft API and MCP

Adds a rest api to minecraft and implements an MCP server so that you can play around with it in Claude Desktop or other LLMs.

# Basics and setup

So there are MCP clinets like Claude Desktop or Goose which connect to the LLM and run MCP commands with the server.

Next there is the MCP server, which for this project runs locally from the `mcp` dicrectory. 

## MCP Server Config

I'm running evertying with uv. You should be able to do a `uv sync` then `uv run minecraft_mcp.py` to start it up.
You can also start it in sse mode with `uv run minecraft_mcp.py --transport sse --port 3001`.

I'm using python `3.13.9`. Some of the dependencies don't support 3.14 yet.

Try `npx @modelcontextprotocol/inspector uv run  minecraft_mcp.py` to make sure it's listing tools. 

The mcp server will aim at localhost:7070 by default.

You can change this with `mcp/.env`, it will check the BASE_URL value, eg `BASE_URL="http://localhost:7070"`

## MCP Client config

There's a sample Claude Desktop Config in `mcp/mcp_config.json`. You'll need to change the paths.

To get it working with goose I needed to set up a script in my $PATH,

```bash
cd /path/to/minecraft-api-mcp/mcp
uv run minecraft_mcp.py
cd -
```

Then tell it to run the script.

Visual Studio Code was able to use the Claude Desktop settings after going through some conifg options.

## Minecraft Server setup

First you'll need the postgres image up and running `docker compose up -d postgres`

After that the gradle runServer command worked for me. Kudos to the fabric devs.

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
