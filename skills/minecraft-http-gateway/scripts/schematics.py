#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


DEFAULT_SERVICE_URL = "http://localhost:7080"
DEFAULT_MINECRAFT_URL = "http://localhost:7070"
LOCK_HEADER = "X-Area-Lock-Id"
VIEWS = ("sheet", "iso", "top", "north", "south", "east", "west")


def request(method: str, url: str) -> tuple[bytes, str]:
    req = urllib.request.Request(url, headers={"Accept": "*/*"}, method=method)
    with urllib.request.urlopen(req) as response:
        return response.read(), response.headers.get_content_type()


def request_json(method: str, url: str) -> object:
    raw, content_type = request(method, url)
    if not raw:
        return {}
    if content_type != "application/json":
        raise ValueError(f"Expected JSON response, got {content_type}")
    return json.loads(raw.decode("utf-8"))


def request_multipart(url: str, fields: dict[str, str], filename: str, data: bytes, lock_id: str | None) -> object:
    boundary = "----minecraft-http-gateway-boundary"
    parts: list[bytes] = []
    for name, value in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode("utf-8"))
    parts.extend([
        f'--{boundary}\r\nContent-Disposition: form-data; name="nbt_file"; filename="{filename}"\r\n'.encode("utf-8"),
        b"Content-Type: application/octet-stream\r\n\r\n",
        data,
        f"\r\n--{boundary}--\r\n".encode("utf-8"),
    ])
    headers = {"Accept": "application/json", "Content-Type": f"multipart/form-data; boundary={boundary}"}
    if lock_id:
        headers[LOCK_HEADER] = lock_id
    req = urllib.request.Request(url, data=b"".join(parts), headers=headers, method="POST")
    with urllib.request.urlopen(req) as response:
        raw = response.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def print_json(value: object) -> None:
    print(json.dumps(value, indent=2, sort_keys=True))


def ground_level(metadata: dict) -> int:
    placement = (metadata.get("image_metadata") or {}).get("placement") or {}
    return int(placement.get("ground_level") or 0)


def main() -> int:
    parser = argparse.ArgumentParser(description="Search, preview and place catalogued schematics over HTTP.")
    parser.add_argument("--base-url", default=os.environ.get("SCHEMATIC_SERVICE_URL", DEFAULT_SERVICE_URL))
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("health")

    search = subparsers.add_parser("search")
    search.add_argument("--q", default="")
    search.add_argument("--limit", type=int, default=10, help="1-50")
    search.add_argument("--structure-type")
    search.add_argument("--style")
    search.add_argument("--size-category")
    search.add_argument("--has-interior", choices=["true", "false"])
    search.add_argument("--include-unplaceable", action="store_true")

    tags = subparsers.add_parser("tags", help="Most common tags, to guide searches")
    tags.add_argument("--limit", type=int, default=20)

    get = subparsers.add_parser("get", help="Full catalog entry, including size and ground_level")
    get.add_argument("--schematic-id", required=True)

    image = subparsers.add_parser("image", help="Preview PNG: labelled sheet or a single view")
    image.add_argument("--schematic-id", required=True)
    image.add_argument("--view", choices=VIEWS, default="sheet")
    image.add_argument("--max-px", type=int, help="64-1024; a sheet tile size when view is sheet")
    image.add_argument("--output", required=True)

    nbt = subparsers.add_parser("nbt")
    nbt.add_argument("--schematic-id", required=True)
    nbt.add_argument("--output", required=True)

    place = subparsers.add_parser("place", help="Place a schematic via the Minecraft NBT endpoint")
    place.add_argument("--schematic-id", required=True)
    for axis in ("x", "y", "z"):
        place.add_argument(f"--{axis}", type=int, required=True)
    place.add_argument("--rotation", default="NONE", choices=["NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"])
    place.add_argument("--world", default="minecraft:overworld")
    place.add_argument("--include-entities", default="true", choices=["true", "false"])
    place.add_argument("--no-ground-offset", action="store_true", help="Do not sink the schematic by its ground_level")
    place.add_argument("--dry-run", action="store_true", help="Report what would be overwritten without writing")
    place.add_argument("--lock-id", default=os.environ.get("MINECRAFT_AREA_LOCK_ID"))
    place.add_argument("--minecraft-url", default=os.environ.get("MINECRAFT_API_BASE_URL", DEFAULT_MINECRAFT_URL))

    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    try:
        if args.command == "health":
            result = request_json("GET", f"{base_url}/health")
        elif args.command == "search":
            query = {k: v for k, v in {
                "q": args.q, "limit": args.limit, "structure_type": args.structure_type, "style": args.style,
                "size_category": args.size_category, "has_interior": args.has_interior,
            }.items() if v is not None}
            if args.include_unplaceable:
                query["placeable"] = "false"
            result = request_json("GET", f"{base_url}/schematics/search?{urllib.parse.urlencode(query)}")
        elif args.command == "tags":
            result = request_json("GET", f"{base_url}/schematics/tags?limit={args.limit}")
        elif args.command == "get":
            result = request_json("GET", f"{base_url}/schematics/{urllib.parse.quote(args.schematic_id)}")
        elif args.command in ("image", "nbt"):
            sid = urllib.parse.quote(args.schematic_id)
            if args.command == "image":
                query = f"?max_px={args.max_px}" if args.max_px else ""
                raw, content_type = request("GET", f"{base_url}/schematics/{sid}/images/{args.view}{query}")
                if content_type != "image/png":
                    raise ValueError(f"Expected image/png response, got {content_type}")
            else:
                raw, _ = request("GET", f"{base_url}/schematics/{sid}/nbt")
            Path(args.output).write_bytes(raw)
            result = {"success": True, "output": args.output, "bytes": len(raw)}
        else:
            sid = urllib.parse.quote(args.schematic_id)
            metadata = request_json("GET", f"{base_url}/schematics/{sid}")
            nbt, _ = request("GET", f"{base_url}/schematics/{sid}/nbt")
            y = args.y if args.no_ground_offset else args.y - ground_level(metadata)
            fields = {
                "x": str(args.x), "y": str(y), "z": str(args.z),
                "rotation": args.rotation,
                "include_entities": args.include_entities,
                "world": args.world,
            }
            if args.dry_run:
                fields["dry_run"] = "true"
            placed = request_multipart(f"{args.minecraft_url.rstrip('/')}/api/world/structure/place",
                                       fields, f"{args.schematic_id}.nbt", nbt, args.lock_id)
            result = {"schematic_id": args.schematic_id, "placed_y": y, "placement": placed}

        print_json(result)
        return 0
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        print(f"HTTP {exc.code}: {body}", file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"Request failed: {exc}", file=sys.stderr)
        return 1
    except (json.JSONDecodeError, ValueError, OSError) as exc:
        print(f"Invalid input or response: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
