#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.request


DEFAULT_BASE_URL = "http://localhost:7070"


def request_json(method: str, url: str, payload: dict | None = None) -> object:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(request) as response:
        raw = response.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def main() -> int:
    parser = argparse.ArgumentParser(description="Drive common Minecraft build-system workflows.")
    parser.add_argument("--base-url", default=os.environ.get("MINECRAFT_API_BASE_URL", DEFAULT_BASE_URL))
    subparsers = parser.add_subparsers(dest="command", required=True)

    create = subparsers.add_parser("create")
    create.add_argument("--name", required=True)
    create.add_argument("--description", default="")
    create.add_argument("--world")

    status = subparsers.add_parser("status")
    status.add_argument("--build-id", required=True)

    execute = subparsers.add_parser("execute")
    execute.add_argument("--build-id", required=True)

    add_single = subparsers.add_parser("add-single-block")
    add_single.add_argument("--build-id", required=True)
    add_single.add_argument("--x", type=int, required=True)
    add_single.add_argument("--y", type=int, required=True)
    add_single.add_argument("--z", type=int, required=True)
    add_single.add_argument("--block-name", required=True)
    add_single.add_argument("--world")
    add_single.add_argument("--description", default="")

    add_fill = subparsers.add_parser("add-fill")
    add_fill.add_argument("--build-id", required=True)
    for field in ("x1", "y1", "z1", "x2", "y2", "z2"):
        add_fill.add_argument(f"--{field}", type=int, required=True)
    add_fill.add_argument("--block-type", required=True)
    add_fill.add_argument("--world")
    add_fill.add_argument("--description", default="")

    plan_rail = subparsers.add_parser("plan-rail")
    plan_rail.add_argument("--build-id", required=True)
    for field in ("start-x", "start-y", "start-z", "end-x", "end-y", "end-z"):
        plan_rail.add_argument(f"--{field}", type=int, required=True)
    plan_rail.add_argument("--world")
    plan_rail.add_argument("--weight-overrides", help="JSON object")

    rail_status = subparsers.add_parser("rail-status")
    rail_status.add_argument("--job-id", required=True)

    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    try:
        if args.command == "create":
            payload = {"name": args.name, "description": args.description}
            if args.world:
                payload["world"] = args.world
            result = request_json("POST", f"{base_url}/api/builds", payload)
        elif args.command == "status":
            result = request_json("GET", f"{base_url}/api/builds/{args.build_id}")
        elif args.command == "execute":
            result = request_json("POST", f"{base_url}/api/builds/{args.build_id}/execute")
        elif args.command == "add-single-block":
            payload = {
                "task_type": "BLOCK_SET",
                "task_data": {
                    "start_x": args.x,
                    "start_y": args.y,
                    "start_z": args.z,
                    "blocks": [[[{"block_name": args.block_name}]]],
                },
                "description": args.description,
            }
            if args.world:
                payload["task_data"]["world"] = args.world
            result = request_json("POST", f"{base_url}/api/builds/{args.build_id}/tasks", payload)
        elif args.command == "add-fill":
            task_data = {
                "x1": args.x1,
                "y1": args.y1,
                "z1": args.z1,
                "x2": args.x2,
                "y2": args.y2,
                "z2": args.z2,
                "block_type": args.block_type,
                "notify_neighbors": False,
            }
            if args.world:
                task_data["world"] = args.world
            payload = {"task_type": "BLOCK_FILL", "task_data": task_data, "description": args.description}
            result = request_json("POST", f"{base_url}/api/builds/{args.build_id}/tasks", payload)
        elif args.command == "plan-rail":
            payload = {
                "start_x": args.start_x,
                "start_y": args.start_y,
                "start_z": args.start_z,
                "end_x": args.end_x,
                "end_y": args.end_y,
                "end_z": args.end_z,
            }
            if args.world:
                payload["world"] = args.world
            if args.weight_overrides:
                payload["weight_overrides"] = json.loads(args.weight_overrides)
            result = request_json("POST", f"{base_url}/api/builds/{args.build_id}/plan-rail", payload)
        else:
            result = request_json("GET", f"{base_url}/api/rail-plans/{args.job_id}")

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
