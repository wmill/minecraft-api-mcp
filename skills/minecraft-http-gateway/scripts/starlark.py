#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


DEFAULT_SERVICE_URL = "http://localhost:7090"
DEFAULT_MINECRAFT_URL = "http://localhost:7070"
LOCK_HEADER = "X-Area-Lock-Id"
VIEWS = ("sheet", "iso", "top", "north", "south", "east", "west")


def request(method: str, url: str, payload: dict | None = None) -> tuple[bytes, str, dict]:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "*/*"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req) as response:
        return response.read(), response.headers.get_content_type(), dict(response.headers)


def request_json(method: str, url: str, payload: dict | None = None) -> object:
    raw, content_type, _ = request(method, url, payload)
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


def parse_json_object(value: str, field: str) -> dict:
    parsed = json.loads(value)
    if not isinstance(parsed, dict):
        raise argparse.ArgumentTypeError(f"{field} must be a JSON object")
    return parsed


def read_source(path: str) -> str:
    return sys.stdin.read() if path == "-" else Path(path).read_text(encoding="utf-8")


def write_bytes(path: str, raw: bytes) -> dict:
    Path(path).write_bytes(raw)
    return {"success": True, "output": path, "bytes": len(raw)}


def add_build_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--entry", help="Entry function, defaults to build")
    parser.add_argument("--props", type=lambda value: parse_json_object(value, "props"), help="JSON keyword args for the entry")
    parser.add_argument("--root-size", type=int, nargs=3, metavar=("X", "Y", "Z"))


def build_body(args: argparse.Namespace, body: dict) -> dict:
    for key in ("entry", "props", "root_size"):
        if getattr(args, key) is not None:
            body[key] = getattr(args, key)
    return body


def main() -> int:
    parser = argparse.ArgumentParser(description="Compile, preview, save and place Starlark builds over HTTP.")
    parser.add_argument("--base-url", default=os.environ.get("STARLARK_SERVICE_URL", DEFAULT_SERVICE_URL))
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("health")

    build = subparsers.add_parser("build", help="Compile a script; exits 2 with diagnostics when the build fails")
    build.add_argument("--file", required=True, help="Script path, or - for stdin")
    add_build_options(build)

    for name in ("artifact", "nbt"):
        p = subparsers.add_parser(name)
        p.add_argument("--artifact-id", required=True)
        if name == "nbt":
            p.add_argument("--output", required=True)

    image = subparsers.add_parser("image", help="Render a preview PNG of a built artifact")
    image.add_argument("--artifact-id", required=True)
    image.add_argument("--view", choices=VIEWS, default="sheet")
    image.add_argument("--max-px", type=int, help="64-1024; a sheet tile size when view is sheet")
    image.add_argument("--output", required=True)

    docs = subparsers.add_parser("docs", help="Script API reference (markdown)")
    docs.add_argument("--topic", default="quickstart", help="quickstart, components, dsl, composition, errors, library, math, full, or a module such as roofs")
    docs.add_argument("--component", help="Show one component's reference instead of a topic")

    subparsers.add_parser("examples")
    example = subparsers.add_parser("example")
    example.add_argument("--name", required=True)

    search = subparsers.add_parser("library-search")
    search.add_argument("--q", default="")
    search.add_argument("--tag")
    search.add_argument("--author")
    for axis in ("x", "y", "z"):
        search.add_argument(f"--max-{axis}", type=int)
    search.add_argument("--limit", type=int, default=10)

    get = subparsers.add_parser("library-get", help="Entry metadata, versions, exports and used_by")
    get.add_argument("--name", required=True)

    source = subparsers.add_parser("library-source")
    source.add_argument("--name", required=True)
    source.add_argument("--version", type=int, help="Defaults to the latest version")
    source.add_argument("--output", help="Write to a file instead of stdout")

    save = subparsers.add_parser("library-save", help="Save a working script as a new immutable library version")
    save.add_argument("--name", required=True)
    origin = save.add_mutually_exclusive_group(required=True)
    origin.add_argument("--file", help="Script path, or - for stdin")
    origin.add_argument("--artifact-id", help="Reuse the source, entry and props of a built artifact")
    save.add_argument("--title", help="Required for a new name")
    save.add_argument("--description", help="Required for a new name")
    save.add_argument("--tag", action="append", dest="tags")
    save.add_argument("--author")
    save.add_argument("--notes")
    save.add_argument("--parent", help="Lineage as name@N")
    add_build_options(save)

    place = subparsers.add_parser("place", help="Place a built artifact via the Minecraft NBT endpoint")
    place.add_argument("--artifact-id", required=True)
    for axis in ("x", "y", "z"):
        place.add_argument(f"--{axis}", type=int, required=True)
    place.add_argument("--rotation", default="NONE", choices=["NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"])
    place.add_argument("--world", default="minecraft:overworld")
    place.add_argument("--include-entities", default="true", choices=["true", "false"])
    place.add_argument("--no-y-offset", action="store_true", help="Do not shift by the artifact's y_offset (ground_level)")
    place.add_argument("--dry-run", action="store_true", help="Report what would be overwritten without writing")
    place.add_argument("--lock-id", default=os.environ.get("MINECRAFT_AREA_LOCK_ID"))
    place.add_argument("--minecraft-url", default=os.environ.get("MINECRAFT_API_BASE_URL", DEFAULT_MINECRAFT_URL))

    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    try:
        if args.command == "health":
            result = request_json("GET", f"{base_url}/health")
        elif args.command == "build":
            result = request_json("POST", f"{base_url}/build", build_body(args, {"source": read_source(args.file)}))
            print_json(result)
            return 0 if result.get("ok") else 2
        elif args.command == "artifact":
            result = request_json("GET", f"{base_url}/artifacts/{args.artifact_id}")
        elif args.command == "nbt":
            raw, _, _ = request("GET", f"{base_url}/artifacts/{args.artifact_id}/nbt")
            result = write_bytes(args.output, raw)
        elif args.command == "image":
            query = f"?max_px={args.max_px}" if args.max_px else ""
            raw, content_type, _ = request("GET", f"{base_url}/artifacts/{args.artifact_id}/images/{args.view}{query}")
            if content_type != "image/png":
                raise ValueError(f"Expected image/png response, got {content_type}")
            result = write_bytes(args.output, raw)
        elif args.command == "docs":
            query = {"component": args.component} if args.component else {"topic": args.topic}
            raw, _, _ = request("GET", f"{base_url}/docs/catalog?{urllib.parse.urlencode(query)}")
            print(raw.decode("utf-8"))
            return 0
        elif args.command == "examples":
            result = request_json("GET", f"{base_url}/examples")
        elif args.command == "example":
            raw, _, _ = request("GET", f"{base_url}/examples/{urllib.parse.quote(args.name)}")
            print(raw.decode("utf-8"))
            return 0
        elif args.command == "library-search":
            query = {k: v for k, v in {
                "q": args.q, "tag": args.tag, "author": args.author, "limit": args.limit,
                "max_x": args.max_x, "max_y": args.max_y, "max_z": args.max_z,
            }.items() if v is not None}
            result = request_json("GET", f"{base_url}/library/search?{urllib.parse.urlencode(query)}")
        elif args.command == "library-get":
            result = request_json("GET", f"{base_url}/library/{urllib.parse.quote(args.name)}")
        elif args.command == "library-source":
            query = f"?version={args.version}" if args.version else ""
            raw, _, headers = request("GET", f"{base_url}/library/{urllib.parse.quote(args.name)}/source{query}")
            version = headers.get("X-Library-Version") or headers.get("x-library-version")
            if args.output:
                result = write_bytes(args.output, raw) | {"version": version}
            else:
                print(f"# {args.name} v{version}", file=sys.stderr)
                print(raw.decode("utf-8"))
                return 0
        elif args.command == "library-save":
            body = {"name": args.name}
            if args.file:
                body["source"] = read_source(args.file)
            else:
                body["artifact_id"] = args.artifact_id
            for key in ("title", "description", "tags", "author", "notes", "parent"):
                if getattr(args, key) is not None:
                    body[key] = getattr(args, key)
            result = request_json("POST", f"{base_url}/library", build_body(args, body))
            print_json(result)
            return 0 if result.get("ok") else 2
        else:
            metadata = request_json("GET", f"{base_url}/artifacts/{args.artifact_id}")
            nbt, _, _ = request("GET", f"{base_url}/artifacts/{args.artifact_id}/nbt")
            y = args.y if args.no_y_offset else args.y + int(metadata.get("y_offset") or 0)
            fields = {
                "x": str(args.x), "y": str(y), "z": str(args.z),
                "rotation": args.rotation,
                "include_entities": args.include_entities,
                "world": args.world,
            }
            if args.dry_run:
                fields["dry_run"] = "true"
            placed = request_multipart(f"{args.minecraft_url.rstrip('/')}/api/world/structure/place",
                                       fields, f"{args.artifact_id}.nbt", nbt, args.lock_id)
            result = {"artifact_id": args.artifact_id, "placed_y": y, "placement": placed}

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
