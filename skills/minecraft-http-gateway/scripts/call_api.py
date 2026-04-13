#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


DEFAULT_BASE_URL = "http://localhost:7070"


def build_url(base_url: str, path: str, query_pairs: list[str]) -> str:
    query = urllib.parse.urlencode([pair.split("=", 1) for pair in query_pairs]) if query_pairs else ""
    if not path.startswith("/"):
        path = "/" + path
    return f"{base_url.rstrip('/')}{path}" + (f"?{query}" if query else "")


def request_json(method: str, url: str, data: str | None) -> object:
    body = data.encode("utf-8") if data is not None else None
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=body, headers=headers, method=method.upper())
    with urllib.request.urlopen(request) as response:
        raw = response.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def main() -> int:
    parser = argparse.ArgumentParser(description="Call the Minecraft HTTP API directly.")
    parser.add_argument("method", choices=["get", "post", "patch", "delete"])
    parser.add_argument("path", help="HTTP path like /api/world/players")
    parser.add_argument("--data", help="JSON request body")
    parser.add_argument("--query", action="append", default=[], help="Query parameter in key=value form")
    parser.add_argument("--base-url", default=os.environ.get("MINECRAFT_API_BASE_URL", DEFAULT_BASE_URL))
    args = parser.parse_args()

    try:
        if args.data is not None:
            json.loads(args.data)
        url = build_url(args.base_url, args.path, args.query)
        result = request_json(args.method, url, args.data)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        print(f"HTTP {exc.code}: {body}", file=sys.stderr)
        return 1
    except urllib.error.URLError as exc:
        print(f"Request failed: {exc}", file=sys.stderr)
        return 1
    except json.JSONDecodeError as exc:
        print(f"Invalid JSON: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
