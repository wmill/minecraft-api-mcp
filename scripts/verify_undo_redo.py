"""Live regression on the disposable server, using the starlark-service uv environment.

Run with --snapshot-dir ../build/redo-smoke/build-snapshots. Restart that server
and rerun with --resume to verify that both directions survive a process restart.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
import json
from pathlib import Path

import httpx
import nbtlib
from nbtlib import Compound, File, Int, List, String


def structure(material: str, item: str) -> bytes:
    root = Compound({
        "DataVersion": Int(4438), "size": List[Int]([2, 2, 2]),
        "palette": List[Compound]([
            Compound({"Name": String(material)}), Compound({"Name": String("minecraft:chest")}),
            Compound({"Name": String("minecraft:air")}),
        ]),
        "blocks": List[Compound]([
            Compound({"pos": List[Int]([x, y, z]), "state": Int(1 if (x, y, z) == (0, 1, 0) else 0 if y == 0 else 2),
                      **({"nbt": Compound({"id": String("minecraft:chest"), "Items": List[Compound]([
                          Compound({"Slot": nbtlib.Byte(0), "id": String(item), "count": Int(3)})])})}
                         if (x, y, z) == (0, 1, 0) else {})})
            for x in range(2) for y in range(2) for z in range(2)
        ]), "entities": List[Compound]([]),
    })
    stream = BytesIO()
    File(root).write(stream)
    return stream.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot-dir", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    # This harness deliberately targets only the separately configured disposable world.
    properties = (args.snapshot_dir.parent / "server.properties").read_text()
    assert "motd=Disposable undo redo verification" in properties
    client = httpx.Client(base_url="http://127.0.0.1:7071", timeout=40)
    report_path = args.snapshot_dir.parent / "verification.json"
    chunk = dict(start_x=9, start_y=80, start_z=10, size_x=2, size_y=2, size_z=2)

    def post(path, *, expected=200, **kwargs):
        response = client.post(path, **kwargs)
        assert response.status_code == expected, (path, response.status_code, response.text)
        return response.json()

    def blocks():
        return post("/api/world/blocks/chunk", json=chunk)["blocks"]

    def place(material, item):
        return post("/api/world/structure/place", files={"nbt_file": ("regression.nbt", structure(material, item))},
                    data={"x": "10", "y": "80", "z": "10", "rotation": "CLOCKWISE_90", "include_entities": "false"})

    def restore(build_id, redo=False, **kwargs):
        return post(f"/api/builds/{build_id}/{'redo' if redo else 'undo'}", **kwargs)

    if args.resume:
        report = json.loads(report_path.read_text())
        restore(report["build_id"], True, json={"force": True})
        assert blocks() == report["redo_blocks"]
        restore(report["build_id"], json={"force": True})
        report["restart_verified"] = True
        report_path.write_text(json.dumps(report, indent=2))
        print("Restart: redo and subsequent undo passed")
        return

    place("minecraft:dirt", "minecraft:apple")
    baseline = blocks()
    placement = place("minecraft:stone", "minecraft:diamond")
    assert placement["undo_available"]
    build_id = placement["build_id"]
    post("/api/world/blocks/set", json={"start_x": 9, "start_y": 80, "start_z": 11,
                                      "blocks": [[[{"block_name": "minecraft:gold_block"}]]]})
    edited = blocks()
    assert restore(build_id)["redo_available"]
    assert blocks() == baseline
    redo_file = args.snapshot_dir / f"{build_id}.redo.nbt"
    saved = nbtlib.load(redo_file)
    assert any(str(entry.get("nbt", {}).get("Items", "")).find("minecraft:diamond") >= 0 for entry in saved["blocks"])
    restore(build_id, expected=409)
    for action in ("replay", "execute"):
        post(f"/api/builds/{build_id}/{action}", expected=409)
    assert restore(build_id, True)["undo_available"]
    assert blocks() == edited
    restore(build_id, True, expected=409)
    restore(build_id)
    assert blocks() == baseline
    # The second undo captured the actual redone world, including chest contents.
    assert nbtlib.load(redo_file) == saved

    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda _: client.post(f"/api/builds/{build_id}/redo"), range(2)))
    assert sorted(response.status_code for response in responses) == [200, 409]
    restore(build_id)

    bounds = dict(min_x=9, min_y=80, min_z=10, max_x=10, max_y=81, max_z=11)
    lock = post("/api/area-locks", json={"bounds": bounds, "label": "undo-redo-regression"})["lock_id"]
    try:
        restore(build_id, True, expected=409, json={"force": True})
        restore(build_id, True, headers={"X-Area-Lock-Id": lock})
        restore(build_id, headers={"X-Area-Lock-Id": lock})
    finally:
        client.delete(f"/api/area-locks/{lock}").raise_for_status()

    place("minecraft:bricks", "minecraft:emerald")
    overlapping = blocks()
    assert restore(build_id, True, expected=409)["code"] == "redo_conflict"
    restore(build_id, True, json={"force": True})
    assert blocks() == edited
    assert restore(build_id, expected=409)["code"] == "undo_conflict"
    restore(build_id, json={"force": True})
    assert blocks() == overlapping

    pending = args.snapshot_dir / f"{build_id}.pending"
    pending.write_text("test-only marker; no restore started\n")
    try:
        restore(build_id, expected=409)
        restore(build_id, True, expected=409)
        assert blocks() == overlapping
    finally:
        pending.unlink()
    report_path.write_text(json.dumps({"build_id": build_id, "redo_blocks": edited,
                                     "seed": 104729, "bounds": bounds, "restart_verified": False}, indent=2))
    print("Passed: rotated placement, edits, air, chest inventory, repeated undo/redo, duplicate/concurrent requests,")
    print("replay/execute rejection, area locks, overlap conflicts/force, and pending-recovery blocking")
    print(f"Build: {build_id}; restart the disposable server and run --resume")


if __name__ == "__main__":
    main()
