#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.request


DEFAULT_BASE_URL = "http://localhost:7070"


def fetch_json(method: str, url: str, payload: dict | None = None) -> object:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="Query common Minecraft API world endpoints.")
    parser.add_argument("--base-url", default=os.environ.get("MINECRAFT_API_BASE_URL", DEFAULT_BASE_URL))
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("players")
    subparsers.add_parser("entities")
    subparsers.add_parser("blocks")

    heightmap = subparsers.add_parser("heightmap")
    for field in ("x1", "z1", "x2", "z2"):
        heightmap.add_argument(f"--{field}", type=int, required=True)
    heightmap.add_argument("--heightmap-type", default="WORLD_SURFACE")
    heightmap.add_argument("--world")

    chunk = subparsers.add_parser("chunk")
    for field in ("start-x", "start-y", "start-z", "size-x", "size-y", "size-z"):
        chunk.add_argument(f"--{field}", type=int, required=True)
    chunk.add_argument("--world")

    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    try:
        if args.command == "players":
            result = fetch_json("GET", f"{base_url}/api/world/players")
        elif args.command == "entities":
            result = fetch_json("GET", f"{base_url}/api/world/entities")
        elif args.command == "blocks":
            result = fetch_json("GET", f"{base_url}/api/world/blocks/list")
        elif args.command == "heightmap":
            payload = {
                "x1": args.x1,
                "z1": args.z1,
                "x2": args.x2,
                "z2": args.z2,
                "heightmap_type": args.heightmap_type,
            }
            if args.world:
                payload["world"] = args.world
            result = fetch_json("POST", f"{base_url}/api/world/blocks/heightmap", payload)
        else:
            payload = {
                "start_x": args.start_x,
                "start_y": args.start_y,
                "start_z": args.start_z,
                "size_x": args.size_x,
                "size_y": args.size_y,
                "size_z": args.size_z,
            }
            if args.world:
                payload["world"] = args.world
            result = fetch_json("POST", f"{base_url}/api/world/blocks/chunk", payload)

        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        print(f"HTTP {exc.code}: {body}", file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"Request failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
