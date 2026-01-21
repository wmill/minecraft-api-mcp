# Minecraft API and MCP

Adds a rest api to minecraft and implements an MCP server so that you can play around with it in Claude Desktop or other LLMs.

# Basics and setup

So there are MCP clinets like Claude Desktop or Goose which connect to the LLM and run MCP commands with the server.

Next there is the MCP server, which for this project runs locally from the `mcp` dicrectory. 

## MCP Server Config

I'm running evertying with uv. You should be able to do a `uv sync` then `uv run minecraft_mcp.py` to start it up.
I'm using python `3.13.9`. Some of the dependencies don't support 3.14 yet.

Try `npx @modelcontextprotocol/inspector uv run  minecraft_mcp.py` to make sure it's listing tools. 

The mcp server will aim at localhost:7070 by default.

You can change this with `mcp/.env`, it will check the BASE_URL value, eg `BASE_URL="http://localhost:7070"`

## MCP Client config

There's a sample Claude Desktop Config in `mcp/mcp_config.json`. You'll need to change the paths.

To get it working with goose I needed to set up a script in my $PATH,

```bash
cd /Users/waltermiller/code/minecraft/fabric-sdk-mod-attempt/mcp
uv run minecraft_mcp.py
cd -
```

Then tell it to run the script.

Visual Studio Code was able to use the Claude Desktop settings after going through some conifg options.

## Minecraft Server setup

First you'll need the postgres image up and running `docker compose up -d postgres`

After that the gradle runServer command worked for me. Kudos to the fabric devs.

# Final notes

The mod is still called "modid" and "com.example" in the Java code because I haven't changed it yet.