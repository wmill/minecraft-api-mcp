#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path


DEFAULT_BASE_URL = "http://localhost:7070"


def request_json(method: str, url: str, payload: dict | None = None) -> object:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req) as response:
        raw = response.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def request_multipart(url: str, fields: dict[str, str], file_field: str, file_path: Path) -> object:
    boundary = "----minecraft-http-gateway-boundary"
    parts: list[bytes] = []
    for name, value in fields.items():
        parts.extend([
            f"--{boundary}\r\n".encode("utf-8"),
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode("utf-8"),
            f"{value}\r\n".encode("utf-8"),
        ])
    parts.extend([
        f"--{boundary}\r\n".encode("utf-8"),
        f'Content-Disposition: form-data; name="{file_field}"; filename="{file_path.name}"\r\n'.encode("utf-8"),
        b"Content-Type: application/octet-stream\r\n\r\n",
        file_path.read_bytes(),
        b"\r\n",
        f"--{boundary}--\r\n".encode("utf-8"),
    ])
    body = b"".join(parts)
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Accept": "application/json", "Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
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


def parse_json_array(value: str, field: str) -> list:
    parsed = json.loads(value)
    if not isinstance(parsed, list):
        raise ValueError(f"{field} must be a JSON array")
    return parsed


def add_world(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--world", help="World name, defaults to minecraft:overworld")


def add_xyz(parser: argparse.ArgumentParser) -> None:
    for axis in ("x", "y", "z"):
        parser.add_argument(f"--{axis}", type=int, required=True)


def maybe_world(payload: dict, world: str | None) -> None:
    if world:
        payload["world"] = world


def main() -> int:
    parser = argparse.ArgumentParser(description="Perform direct Minecraft world operations over HTTP.")
    parser.add_argument("--base-url", default=os.environ.get("MINECRAFT_API_BASE_URL", DEFAULT_BASE_URL))
    subparsers = parser.add_subparsers(dest="command", required=True)

    fill = subparsers.add_parser("fill")
    for field in ("x1", "y1", "z1", "x2", "y2", "z2"):
        fill.add_argument(f"--{field}", type=int, required=True)
    fill.add_argument("--block-type", required=True)
    fill.add_argument("--notify-neighbors", action="store_true")
    add_world(fill)

    set_block = subparsers.add_parser("set-block")
    add_xyz(set_block)
    set_block.add_argument("--block-name", required=True)
    set_block.add_argument("--block-states", type=lambda value: parse_json_object(value, "block-states"))
    add_world(set_block)

    set_blocks = subparsers.add_parser("set-blocks")
    set_blocks.add_argument("--start-x", type=int, required=True)
    set_blocks.add_argument("--start-y", type=int, required=True)
    set_blocks.add_argument("--start-z", type=int, required=True)
    set_blocks.add_argument("--blocks-json", required=True, help="3D blocks array JSON")
    add_world(set_blocks)

    spawn = subparsers.add_parser("spawn-entity")
    spawn.add_argument("--type", required=True)
    spawn.add_argument("--x", type=float, required=True)
    spawn.add_argument("--y", type=float, required=True)
    spawn.add_argument("--z", type=float, required=True)
    add_world(spawn)

    broadcast = subparsers.add_parser("broadcast")
    broadcast.add_argument("--message", required=True)
    broadcast.add_argument("--action-bar", action="store_true")

    player_message = subparsers.add_parser("message-player")
    player_message.add_argument("--message", required=True)
    player_message.add_argument("--name")
    player_message.add_argument("--uuid")
    player_message.add_argument("--action-bar", action="store_true")

    teleport = subparsers.add_parser("teleport")
    teleport.add_argument("--player-name", required=True)
    teleport.add_argument("--x", type=float, required=True)
    teleport.add_argument("--y", type=float, required=True)
    teleport.add_argument("--z", type=float, required=True)
    teleport.add_argument("--dimension", default="minecraft:overworld")
    teleport.add_argument("--yaw", type=float, default=0.0)
    teleport.add_argument("--pitch", type=float, default=0.0)

    rain_fire = subparsers.add_parser("rain-fire")
    rain_fire.add_argument("--x", type=int, required=True)
    rain_fire.add_argument("--z", type=int, required=True)
    rain_fire.add_argument("--radius", type=int, required=True)
    rain_fire.add_argument("--density", type=float, required=True)
    rain_fire.add_argument("--seed", type=int)
    add_world(rain_fire)

    door = subparsers.add_parser("door")
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

    stairs = subparsers.add_parser("stairs")
    for field in ("start-x", "start-y", "start-z", "end-x", "end-y", "end-z"):
        stairs.add_argument(f"--{field}", type=int, required=True)
    stairs.add_argument("--block-type", required=True)
    stairs.add_argument("--stair-type", required=True)
    stairs.add_argument("--staircase-direction", required=True, choices=["north", "south", "east", "west"])
    stairs.add_argument("--fill-support", action="store_true")
    add_world(stairs)

    window = subparsers.add_parser("window")
    for field in ("start-x", "start-y", "start-z", "end-x", "end-z", "height"):
        window.add_argument(f"--{field}", type=int, required=True)
    window.add_argument("--block-type", required=True)
    window.add_argument("--waterlogged", action="store_true")
    add_world(window)

    torch = subparsers.add_parser("torch")
    add_xyz(torch)
    torch.add_argument("--block-type", required=True)
    torch.add_argument("--facing", choices=["north", "south", "east", "west"])
    add_world(torch)

    sign = subparsers.add_parser("sign")
    add_xyz(sign)
    sign.add_argument("--block-type", required=True)
    sign.add_argument("--front-line", action="append", default=[])
    sign.add_argument("--back-line", action="append", default=[])
    sign.add_argument("--facing", choices=["north", "south", "east", "west"])
    sign.add_argument("--rotation", type=int)
    sign.add_argument("--glowing", action="store_true")
    add_world(sign)

    ladder = subparsers.add_parser("ladder")
    add_xyz(ladder)
    ladder.add_argument("--height", type=int, required=True)
    ladder.add_argument("--block-type", default="minecraft:ladder")
    ladder.add_argument("--facing", choices=["north", "south", "east", "west"])
    add_world(ladder)

    nbt = subparsers.add_parser("place-nbt")
    nbt.add_argument("--file", required=True)
    add_xyz(nbt)
    nbt.add_argument("--rotation", default="NONE", choices=["NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"])
    nbt.add_argument("--include-entities", default="true", choices=["true", "false"])
    nbt.add_argument("--replace-blocks", default="true", choices=["true", "false"])
    add_world(nbt)

    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")

    try:
        if args.command == "fill":
            payload = {
                "x1": args.x1, "y1": args.y1, "z1": args.z1,
                "x2": args.x2, "y2": args.y2, "z2": args.z2,
                "block_type": args.block_type,
                "notify_neighbors": args.notify_neighbors,
            }
            maybe_world(payload, args.world)
            result = request_json("POST", f"{base_url}/api/world/blocks/fill", payload)
        elif args.command == "set-block":
            block = {"block_name": args.block_name}
            if args.block_states:
                block["block_states"] = args.block_states
            payload = {"start_x": args.x, "start_y": args.y, "start_z": args.z, "blocks": [[[block]]]}
            maybe_world(payload, args.world)
            result = request_json("POST", f"{base_url}/api/world/blocks/set", payload)
        elif args.command == "set-blocks":
            payload = {
                "start_x": args.start_x,
                "start_y": args.start_y,
                "start_z": args.start_z,
                "blocks": parse_json_array(args.blocks_json, "blocks-json"),
            }
            maybe_world(payload, args.world)
            result = request_json("POST", f"{base_url}/api/world/blocks/set", payload)
        elif args.command == "spawn-entity":
            payload = {"type": args.type, "x": args.x, "y": args.y, "z": args.z}
            maybe_world(payload, args.world)
            result = request_json("POST", f"{base_url}/api/world/entities/spawn", payload)
        elif args.command == "broadcast":
            result = request_json("POST", f"{base_url}/api/message/broadcast", {
                "message": args.message,
                "action_bar": args.action_bar,
            })
        elif args.command == "message-player":
            payload = {"message": args.message, "action_bar": args.action_bar}
            if args.name:
                payload["name"] = args.name
            if args.uuid:
                payload["uuid"] = args.uuid
            result = request_json("POST", f"{base_url}/api/message/player", payload)
        elif args.command == "teleport":
            result = request_json("POST", f"{base_url}/api/players/teleport", {
                "player_name": args.player_name,
                "x": args.x,
                "y": args.y,
                "z": args.z,
                "dimension": args.dimension,
                "yaw": args.yaw,
                "pitch": args.pitch,
            })
        elif args.command == "rain-fire":
            payload = {"x": args.x, "z": args.z, "radius": args.radius, "density": args.density}
            if args.seed is not None:
                payload["seed"] = args.seed
            maybe_world(payload, args.world)
            result = request_json("POST", f"{base_url}/api/world/effects/rain-fire", payload)
        elif args.command in {"door", "stairs", "window", "torch", "sign", "ladder"}:
            payload = vars(args).copy()
            for key in ("command", "base_url"):
                payload.pop(key, None)
            if args.command == "sign":
                payload["front_lines"] = payload.pop("front_line")
                payload["back_lines"] = payload.pop("back_line")
            payload = {key: value for key, value in payload.items() if value is not None}
            endpoint = "window-pane" if args.command == "window" else args.command
            result = request_json("POST", f"{base_url}/api/world/prefabs/{endpoint}", payload)
        else:
            file_path = Path(args.file)
            fields = {
                "x": str(args.x),
                "y": str(args.y),
                "z": str(args.z),
                "rotation": args.rotation,
                "include_entities": args.include_entities,
                "replace_blocks": args.replace_blocks,
                "world": args.world or "minecraft:overworld",
            }
            result = request_multipart(f"{base_url}/api/world/structure/place", fields, "nbt_file", file_path)

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
