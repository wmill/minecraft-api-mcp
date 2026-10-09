#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


DEFAULT_BASE_URL = "http://localhost:7070"
BOUNDS = ("min_x", "min_y", "min_z", "max_x", "max_y", "max_z")


def request_json(method: str, url: str, payload: dict | None = None) -> object:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req) as response:
        raw = response.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def print_json(value: object) -> None:
    print(json.dumps(value, indent=2, sort_keys=True))


def add_bounds(parser: argparse.ArgumentParser, required: bool) -> None:
    for field in BOUNDS:
        parser.add_argument(f"--{field.replace('_', '-')}", dest=field, type=int, required=required)


def bounds(args: argparse.Namespace) -> dict | None:
    values = {field: getattr(args, field) for field in BOUNDS}
    given = [v for v in values.values() if v is not None]
    if not given:
        return None
    if len(given) != len(BOUNDS):
        raise ValueError("all six bounds (--min-x ... --max-z) are required together")
    return values


def main() -> int:
    parser = argparse.ArgumentParser(description="Reserve build areas (area locks) over HTTP.")
    parser.add_argument("--base-url", default=os.environ.get("MINECRAFT_API_BASE_URL", DEFAULT_BASE_URL))
    subparsers = parser.add_subparsers(dest="command", required=True)

    acquire = subparsers.add_parser("acquire", help="Reserve an inclusive cuboid; prints the lock_id token")
    add_bounds(acquire, required=True)
    acquire.add_argument("--label", default="")
    acquire.add_argument("--world", help="World name, defaults to minecraft:overworld")

    listing = subparsers.add_parser("list", help="List active reservations (never shows tokens)")
    add_bounds(listing, required=False)
    listing.add_argument("--world")

    renew = subparsers.add_parser("renew", help="Restart the idle timer, optionally resizing")
    renew.add_argument("--lock-id", required=True)
    add_bounds(renew, required=False)

    release = subparsers.add_parser("release", help="Release a reservation (repeating is harmless)")
    release.add_argument("--lock-id", required=True)

    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    try:
        if args.command == "acquire":
            payload = {"bounds": bounds(args), "label": args.label}
            if args.world:
                payload["world"] = args.world
            result = request_json("POST", f"{base_url}/api/area-locks", payload)
        elif args.command == "list":
            query = dict(bounds(args) or {})
            if args.world:
                query["world"] = args.world
            suffix = f"?{urllib.parse.urlencode(query)}" if query else ""
            result = request_json("GET", f"{base_url}/api/area-locks{suffix}")
        elif args.command == "renew":
            resized = bounds(args)
            result = request_json("PATCH", f"{base_url}/api/area-locks/{args.lock_id}",
                                  {"bounds": resized} if resized else {})
        else:
            result = request_json("DELETE", f"{base_url}/api/area-locks/{args.lock_id}")

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
        print(f"Invalid input or response: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
