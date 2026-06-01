#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
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


def parse_json_object(value: str, field: str) -> dict:
    parsed = json.loads(value)
    if not isinstance(parsed, dict):
        raise argparse.ArgumentTypeError(f"{field} must be a JSON object")
    return parsed


def parse_json_array(value: str, field: str) -> list:
    parsed = json.loads(value)
    if not isinstance(parsed, list):
        raise ValueError(f"{field} must be a JSON array")
    return parsed


def maybe_world(target: dict, world: str | None) -> None:
    if world:
        target["world"] = world


def add_world(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--world", help="World name, defaults to minecraft:overworld")


def add_description(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--description", default="")
    parser.add_argument("--task-order", type=int, help="Optional insertion position")


def add_xyz(parser: argparse.ArgumentParser, prefix: str = "") -> None:
    for axis in ("x", "y", "z"):
        parser.add_argument(f"--{prefix}{axis}", type=int, required=True)


def add_task_payload(payload: dict, args: argparse.Namespace) -> None:
    if getattr(args, "description", None):
        payload["description"] = args.description
    if getattr(args, "task_order", None) is not None:
        payload["task_order"] = args.task_order


def post_task(base_url: str, build_id: str, task_type: str, task_data: dict, args: argparse.Namespace) -> object:
    payload = {"task_type": task_type, "task_data": task_data}
    add_task_payload(payload, args)
    return request_json("POST", f"{base_url}/api/builds/{build_id}/tasks", payload)


def main() -> int:
    parser = argparse.ArgumentParser(description="Drive Minecraft build-system workflows over HTTP.")
    parser.add_argument("--base-url", default=os.environ.get("MINECRAFT_API_BASE_URL", DEFAULT_BASE_URL))
    subparsers = parser.add_subparsers(dest="command", required=True)

    create = subparsers.add_parser("create")
    create.add_argument("--name", required=True)
    create.add_argument("--description", default="")
    add_world(create)

    for name in ("status", "tasks", "execute", "replay", "audit"):
        p = subparsers.add_parser(name)
        p.add_argument("--build-id", required=True)

    preview = subparsers.add_parser("preview")
    preview.add_argument("--build-id", required=True)
    preview.add_argument("--output", required=True)
    preview.add_argument("--iso-scale", type=int, default=6)
    preview.add_argument("--terrain-margin", type=int, default=0)
    preview.add_argument("--view-direction", choices=["south", "west", "north", "east"], default="south")

    reorder = subparsers.add_parser("reorder")
    reorder.add_argument("--build-id", required=True)
    reorder.add_argument("--task-id", action="append", required=True, help="Task ID in desired order; repeat for each task")

    delete = subparsers.add_parser("delete-task")
    delete.add_argument("--build-id", required=True)
    delete.add_argument("--task-id", required=True)

    update = subparsers.add_parser("update-task")
    update.add_argument("--build-id", required=True)
    update.add_argument("--task-id", required=True)
    update.add_argument("--task-data", type=lambda value: parse_json_object(value, "task-data"))
    update.add_argument("--description")

    add_single = subparsers.add_parser("add-single-block")
    add_single.add_argument("--build-id", required=True)
    add_xyz(add_single)
    add_single.add_argument("--block-name", required=True)
    add_single.add_argument("--block-states", type=lambda value: parse_json_object(value, "block-states"))
    add_world(add_single)
    add_description(add_single)

    add_set = subparsers.add_parser("add-block-set")
    add_set.add_argument("--build-id", required=True)
    add_set.add_argument("--start-x", type=int, required=True)
    add_set.add_argument("--start-y", type=int, required=True)
    add_set.add_argument("--start-z", type=int, required=True)
    add_set.add_argument("--blocks-json", required=True, help="3D blocks array JSON")
    add_world(add_set)
    add_description(add_set)

    add_fill = subparsers.add_parser("add-fill")
    add_fill.add_argument("--build-id", required=True)
    for field in ("x1", "y1", "z1", "x2", "y2", "z2"):
        add_fill.add_argument(f"--{field}", type=int, required=True)
    add_fill.add_argument("--block-type", required=True)
    add_fill.add_argument("--notify-neighbors", action="store_true")
    add_world(add_fill)
    add_description(add_fill)

    door = subparsers.add_parser("add-door")
    door.add_argument("--build-id", required=True)
    door.add_argument("--start-x", type=int, required=True)
    door.add_argument("--start-y", type=int, required=True)
    door.add_argument("--start-z", type=int, required=True)
    door.add_argument("--block-type", required=True)
    door.add_argument("--facing", required=True, choices=["north", "south", "east", "west"])
    door.add_argument("--width", type=int, default=1)
    door.add_argument("--hinge", choices=["left", "right"], default="left")
    door.add_argument("--open", action="store_true")
    door.add_argument("--double-doors", action="store_true")
    add_world(door)
    add_description(door)

    stairs = subparsers.add_parser("add-stairs")
    stairs.add_argument("--build-id", required=True)
    for field in ("start-x", "start-y", "start-z", "end-x", "end-y", "end-z"):
        stairs.add_argument(f"--{field}", type=int, required=True)
    stairs.add_argument("--block-type", required=True)
    stairs.add_argument("--stair-type", required=True)
    stairs.add_argument("--staircase-direction", required=True, choices=["north", "south", "east", "west"])
    stairs.add_argument("--fill-support", action="store_true")
    add_world(stairs)
    add_description(stairs)

    window = subparsers.add_parser("add-window")
    window.add_argument("--build-id", required=True)
    for field in ("start-x", "start-y", "start-z", "end-x", "end-z", "height"):
        window.add_argument(f"--{field}", type=int, required=True)
    window.add_argument("--block-type", required=True)
    window.add_argument("--waterlogged", action="store_true")
    add_world(window)
    add_description(window)

    torch = subparsers.add_parser("add-torch")
    torch.add_argument("--build-id", required=True)
    add_xyz(torch)
    torch.add_argument("--block-type", required=True)
    torch.add_argument("--facing", choices=["north", "south", "east", "west"])
    add_world(torch)
    add_description(torch)

    sign = subparsers.add_parser("add-sign")
    sign.add_argument("--build-id", required=True)
    add_xyz(sign)
    sign.add_argument("--block-type", required=True)
    sign.add_argument("--front-line", action="append", default=[])
    sign.add_argument("--back-line", action="append", default=[])
    sign.add_argument("--facing", choices=["north", "south", "east", "west"])
    sign.add_argument("--rotation", type=int)
    sign.add_argument("--glowing", action="store_true")
    add_world(sign)
    add_description(sign)

    ladder = subparsers.add_parser("add-ladder")
    ladder.add_argument("--build-id", required=True)
    add_xyz(ladder)
    ladder.add_argument("--height", type=int, required=True)
    ladder.add_argument("--block-type", default="minecraft:ladder")
    ladder.add_argument("--facing", choices=["north", "south", "east", "west"])
    add_world(ladder)
    add_description(ladder)

    query = subparsers.add_parser("query-location")
    for field in ("min-x", "min-y", "min-z", "max-x", "max-y", "max-z"):
        query.add_argument(f"--{field}", type=int, required=True)
    add_world(query)
    query.add_argument("--include-in-progress", action="store_true")

    plan_rail = subparsers.add_parser("plan-rail")
    plan_rail.add_argument("--build-id", required=True)
    for field in ("start-x", "start-y", "start-z", "end-x", "end-y", "end-z"):
        plan_rail.add_argument(f"--{field}", type=int, required=True)
    add_world(plan_rail)
    plan_rail.add_argument("--weight-overrides", type=lambda value: parse_json_object(value, "weight-overrides"))

    rail_status = subparsers.add_parser("rail-status")
    rail_status.add_argument("--job-id", required=True)

    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    try:
        if args.command == "create":
            payload = {"name": args.name, "description": args.description}
            maybe_world(payload, args.world)
            result = request_json("POST", f"{base_url}/api/builds", payload)
        elif args.command == "status":
            result = request_json("GET", f"{base_url}/api/builds/{args.build_id}")
        elif args.command == "tasks":
            result = request_json("GET", f"{base_url}/api/builds/{args.build_id}/tasks")
        elif args.command == "execute":
            result = request_json("POST", f"{base_url}/api/builds/{args.build_id}/execute")
        elif args.command == "replay":
            result = request_json("POST", f"{base_url}/api/builds/{args.build_id}/replay")
        elif args.command == "audit":
            result = request_json("POST", f"{base_url}/api/builds/{args.build_id}/audit")
        elif args.command == "preview":
            query = urllib.parse.urlencode({
                "iso_scale": args.iso_scale,
                "terrain_margin": args.terrain_margin,
                "view_direction": args.view_direction,
            })
            raw, content_type = request("GET", f"{base_url}/api/builds/{args.build_id}/preview?{query}")
            if content_type != "image/png":
                raise ValueError(f"Expected image/png response, got {content_type}: {raw.decode('utf-8', errors='replace')}")
            with open(args.output, "wb") as output:
                output.write(raw)
            result = {"success": True, "output": args.output, "bytes": len(raw)}
        elif args.command == "reorder":
            result = request_json("PUT", f"{base_url}/api/builds/{args.build_id}/tasks", {
                "tasks": [{"id": task_id} for task_id in args.task_id]
            })
        elif args.command == "delete-task":
            result = request_json("DELETE", f"{base_url}/api/builds/{args.build_id}/tasks/{args.task_id}")
        elif args.command == "update-task":
            payload = {}
            if args.task_data is not None:
                payload["task_data"] = args.task_data
            if args.description is not None:
                payload["description"] = args.description
            result = request_json("PATCH", f"{base_url}/api/builds/{args.build_id}/tasks/{args.task_id}", payload)
        elif args.command == "add-single-block":
            block = {"block_name": args.block_name}
            if args.block_states:
                block["block_states"] = args.block_states
            task_data = {"start_x": args.x, "start_y": args.y, "start_z": args.z, "blocks": [[[block]]]}
            maybe_world(task_data, args.world)
            result = post_task(base_url, args.build_id, "BLOCK_SET", task_data, args)
        elif args.command == "add-block-set":
            blocks = parse_json_array(args.blocks_json, "blocks-json")
            task_data = {"start_x": args.start_x, "start_y": args.start_y, "start_z": args.start_z, "blocks": blocks}
            maybe_world(task_data, args.world)
            result = post_task(base_url, args.build_id, "BLOCK_SET", task_data, args)
        elif args.command == "add-fill":
            task_data = {
                "x1": args.x1, "y1": args.y1, "z1": args.z1,
                "x2": args.x2, "y2": args.y2, "z2": args.z2,
                "block_type": args.block_type,
                "notify_neighbors": args.notify_neighbors,
            }
            maybe_world(task_data, args.world)
            result = post_task(base_url, args.build_id, "BLOCK_FILL", task_data, args)
        elif args.command == "add-door":
            task_data = vars(args) | {"open": args.open, "double_doors": args.double_doors}
            task_data = {k: v for k, v in task_data.items() if k in {
                "world", "start_x", "start_y", "start_z", "width", "facing", "block_type", "hinge", "open", "double_doors"
            } and v is not None}
            result = post_task(base_url, args.build_id, "PREFAB_DOOR", task_data, args)
        elif args.command == "add-stairs":
            task_data = {k: getattr(args, k) for k in (
                "start_x", "start_y", "start_z", "end_x", "end_y", "end_z",
                "block_type", "stair_type", "staircase_direction"
            )}
            task_data["fill_support"] = args.fill_support
            maybe_world(task_data, args.world)
            result = post_task(base_url, args.build_id, "PREFAB_STAIRS", task_data, args)
        elif args.command == "add-window":
            task_data = {k: getattr(args, k) for k in ("start_x", "start_y", "start_z", "end_x", "end_z", "height", "block_type")}
            task_data["waterlogged"] = args.waterlogged
            maybe_world(task_data, args.world)
            result = post_task(base_url, args.build_id, "PREFAB_WINDOW", task_data, args)
        elif args.command == "add-torch":
            task_data = {"x": args.x, "y": args.y, "z": args.z, "block_type": args.block_type}
            if args.facing:
                task_data["facing"] = args.facing
            maybe_world(task_data, args.world)
            result = post_task(base_url, args.build_id, "PREFAB_TORCH", task_data, args)
        elif args.command == "add-sign":
            task_data = {"x": args.x, "y": args.y, "z": args.z, "block_type": args.block_type}
            if args.front_line:
                task_data["front_lines"] = args.front_line
            if args.back_line:
                task_data["back_lines"] = args.back_line
            if args.facing:
                task_data["facing"] = args.facing
            if args.rotation is not None:
                task_data["rotation"] = args.rotation
            task_data["glowing"] = args.glowing
            maybe_world(task_data, args.world)
            result = post_task(base_url, args.build_id, "PREFAB_SIGN", task_data, args)
        elif args.command == "add-ladder":
            task_data = {"x": args.x, "y": args.y, "z": args.z, "height": args.height, "block_type": args.block_type}
            if args.facing:
                task_data["facing"] = args.facing
            maybe_world(task_data, args.world)
            result = post_task(base_url, args.build_id, "PREFAB_LADDER", task_data, args)
        elif args.command == "query-location":
            payload = {
                "min_x": args.min_x, "min_y": args.min_y, "min_z": args.min_z,
                "max_x": args.max_x, "max_y": args.max_y, "max_z": args.max_z,
                "include_in_progress": args.include_in_progress,
            }
            maybe_world(payload, args.world)
            result = request_json("POST", f"{base_url}/api/builds/query-location", payload)
        elif args.command == "plan-rail":
            payload = {
                "start_x": args.start_x, "start_y": args.start_y, "start_z": args.start_z,
                "end_x": args.end_x, "end_y": args.end_y, "end_z": args.end_z,
            }
            maybe_world(payload, args.world)
            if args.weight_overrides:
                payload["weight_overrides"] = args.weight_overrides
            result = request_json("POST", f"{base_url}/api/builds/{args.build_id}/plan-rail", payload)
        else:
            result = request_json("GET", f"{base_url}/api/rail-plans/{args.job_id}")

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
