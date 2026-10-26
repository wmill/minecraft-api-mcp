#!/usr/bin/env python3
import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.request


DEFAULT_BASE_URL = "http://localhost:7070"
LOCK_HEADER = "X-Area-Lock-Id"
MAP_PNGS = ("relief_png", "surface_png")
SMOOTH_PNGS = ("before_png", "after_png", "change_png")


def request_json(method: str, url: str, payload: dict, lock_id: str | None = None) -> dict:
    headers = {"Accept": "application/json", "Content-Type": "application/json"}
    if lock_id:
        headers[LOCK_HEADER] = lock_id
    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), headers=headers, method=method)
    with urllib.request.urlopen(req) as response:
        raw = response.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def print_json(value: object) -> None:
    print(json.dumps(value, indent=2, sort_keys=True))


def save_pngs(result: dict, keys: tuple[str, ...], prefix: str | None) -> dict:
    """Replace base64 PNG fields with the paths they were written to (or drop them without a prefix)."""
    saved = {}
    for key in keys:
        encoded = result.pop(key, None)
        if encoded is None:
            continue
        if prefix:
            path = f"{prefix}{key.removesuffix('_png')}.png"
            with open(path, "wb") as output:
                output.write(base64.b64decode(encoded))
            saved[key.removesuffix("_png")] = path
    if saved:
        result["images"] = saved
    return result


def add_area(parser: argparse.ArgumentParser) -> None:
    for field in ("x1", "z1", "x2", "z2"):
        parser.add_argument(f"--{field}", type=int, required=True)
    parser.add_argument("--world", help="World name, defaults to minecraft:overworld")
    parser.add_argument("--px-per-block", type=int, help="Map scale; auto-fit when omitted")
    parser.add_argument("--contour-interval", type=int, help="Contour spacing in blocks; auto when omitted")
    parser.add_argument("--output-prefix",
                        help="Write the maps as <prefix><name>.png; without it the base64 images are omitted")


def area_payload(args: argparse.Namespace) -> dict:
    payload = {"x1": args.x1, "z1": args.z1, "x2": args.x2, "z2": args.z2}
    for key in ("world", "px_per_block", "contour_interval"):
        if getattr(args, key) is not None:
            payload[key] = getattr(args, key)
    return payload


def main() -> int:
    parser = argparse.ArgumentParser(description="Map and smooth Minecraft terrain over HTTP.")
    parser.add_argument("--base-url", default=os.environ.get("MINECRAFT_API_BASE_URL", DEFAULT_BASE_URL))
    subparsers = parser.add_subparsers(dest="command", required=True)

    terrain_map = subparsers.add_parser("map", help="Top-down relief and surface-material maps (read-only)")
    add_area(terrain_map)
    terrain_map.add_argument("--heightmap-type", default="WORLD_SURFACE")
    terrain_map.add_argument("--layer", action="append", choices=["relief", "surface"], dest="layers",
                             help="Repeat to pick layers; defaults to both")

    smooth = subparsers.add_parser("smooth", help="Smooth ground or grade a blended pad (dry-run first)")
    add_area(smooth)
    smooth.add_argument("--method", required=True, choices=["gaussian", "median", "bilateral", "pad"])
    smooth.add_argument("--sigma", type=float, help="gaussian/bilateral spatial sigma (default 2.0)")
    smooth.add_argument("--sigma-range", type=float, help="bilateral height sigma (default 2.0)")
    smooth.add_argument("--radius", type=int, help="median radius (default 2)")
    smooth.add_argument("--iterations", type=int, help="Filter passes (default 1)")
    smooth.add_argument("--strength", type=float, help="Blend toward the target, 0-1 (default 1.0)")
    smooth.add_argument("--feather", type=int, help="Edge feather in blocks (default 3, 0 for pad)")
    smooth.add_argument("--max-change", type=int, help="Clamp on per-column height change")
    for field in ("y", "x1", "z1", "x2", "z2", "blend"):
        smooth.add_argument(f"--pad-{field}", type=int, dest=f"pad_{field}",
                            help="Pad footprint and target standing Y (method pad)")
    smooth.add_argument("--dry-run", action="store_true", help="Plan only and return before/after/change maps")
    smooth.add_argument("--lock-id", default=os.environ.get("MINECRAFT_AREA_LOCK_ID"),
                        help="Area lock token (X-Area-Lock-Id); defaults to MINECRAFT_AREA_LOCK_ID")

    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    try:
        if args.command == "map":
            payload = area_payload(args) | {"heightmap_type": args.heightmap_type}
            if args.layers:
                payload["layers"] = args.layers
            result = request_json("POST", f"{base_url}/api/world/blocks/heightmap/map", payload)
            result = save_pngs(result, MAP_PNGS, args.output_prefix)
        else:
            payload = area_payload(args) | {"method": args.method, "dry_run": args.dry_run}
            for key in ("sigma", "sigma_range", "radius", "iterations", "strength", "feather", "max_change"):
                if getattr(args, key) is not None:
                    payload[key] = getattr(args, key)
            pad = {field: getattr(args, f"pad_{field}") for field in ("y", "x1", "z1", "x2", "z2", "blend")
                   if getattr(args, f"pad_{field}") is not None}
            if args.method == "pad":
                missing = [f"--pad-{field}" for field in ("y", "x1", "z1", "x2", "z2") if field not in pad]
                if missing:
                    raise ValueError(f"method pad needs {', '.join(missing)}")
                payload["pad"] = pad
            elif pad:
                raise ValueError("--pad-* options are only used with --method pad")
            result = request_json("POST", f"{base_url}/api/world/terrain/smooth", payload, args.lock_id)
            result = save_pngs(result, SMOOTH_PNGS, args.output_prefix)

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
