#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.request


DEFAULT_BASE_URL = "http://localhost:7070"


def request(method: str, url: str, payload: dict | None = None) -> tuple[bytes, str]:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req) as response:
        return response.read(), response.headers.get_content_type()


def request_json(method: str, url: str, payload: dict | None = None) -> object:
    raw, content_type = request(method, url, payload)
    if not raw:
        return {}
    if content_type != "application/json":
        raise ValueError(f"Expected JSON response, got {content_type}")
    return json.loads(raw.decode("utf-8"))


def print_json(value: object) -> None:
    print(json.dumps(value, indent=2, sort_keys=True))


def add_world(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--world", help="World name, defaults to minecraft:overworld")


def add_area(parser: argparse.ArgumentParser) -> None:
    for field in ("x1", "z1", "x2", "z2"):
        parser.add_argument(f"--{field}", type=int, required=True)
    parser.add_argument("--heightmap-type", default="WORLD_SURFACE")
    add_world(parser)


def main() -> int:
    parser = argparse.ArgumentParser(description="Read Minecraft API world state over HTTP.")
    parser.add_argument("--base-url", default=os.environ.get("MINECRAFT_API_BASE_URL", DEFAULT_BASE_URL))
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("test", help="Check that the HTTP API is running")
    subparsers.add_parser("players", help="List online players")
    subparsers.add_parser("entities", help="List spawnable entity types")
    subparsers.add_parser("blocks", help="List available block types")

    heightmap = subparsers.add_parser("heightmap", help="Read raw heightmap JSON")
    add_area(heightmap)

    preview = subparsers.add_parser("heightmap-preview", help="Render a heightmap PNG preview")
    add_area(preview)
    preview.add_argument("--output", required=True, help="PNG output path")
    preview.add_argument("--iso-scale", type=int, default=6)
    preview.add_argument("--view-direction", choices=["south", "west", "north", "east"], default="south")

    chunk = subparsers.add_parser("chunk", help="Read a block chunk")
    for field in ("start-x", "start-y", "start-z", "size-x", "size-y", "size-z"):
        chunk.add_argument(f"--{field}", type=int, required=True)
    add_world(chunk)

    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    try:
        if args.command == "test":
            raw, _ = request("GET", f"{base_url}/api/test")
            print(raw.decode("utf-8"))
            return 0
        if args.command == "players":
            result = request_json("GET", f"{base_url}/api/world/players")
        elif args.command == "entities":
            result = request_json("GET", f"{base_url}/api/world/entities")
        elif args.command == "blocks":
            result = request_json("GET", f"{base_url}/api/world/blocks/list")
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
            result = request_json("POST", f"{base_url}/api/world/blocks/heightmap", payload)
        elif args.command == "heightmap-preview":
            payload = {
                "x1": args.x1,
                "z1": args.z1,
                "x2": args.x2,
                "z2": args.z2,
                "heightmap_type": args.heightmap_type,
                "iso_scale": args.iso_scale,
                "view_direction": args.view_direction,
            }
            if args.world:
                payload["world"] = args.world
            raw, content_type = request("POST", f"{base_url}/api/world/blocks/heightmap/preview", payload)
            if content_type != "image/png":
                raise ValueError(f"Expected image/png response, got {content_type}: {raw.decode('utf-8', errors='replace')}")
            with open(args.output, "wb") as output:
                output.write(raw)
            print_json({"success": True, "output": args.output, "bytes": len(raw)})
            return 0
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
            result = request_json("POST", f"{base_url}/api/world/blocks/chunk", payload)

        print_json(result)
        return 0
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        print(f"HTTP {exc.code}: {body}", file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"Request failed: {exc}", file=sys.stderr)
        return 1
    except (json.JSONDecodeError, ValueError) as exc:
        print(f"Invalid response: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
