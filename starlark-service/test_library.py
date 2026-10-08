from __future__ import annotations

import json

from fastapi.testclient import TestClient

from conftest import SIMPLE_SOURCE
from starlark_service import library
from starlark_service.app import create_app

COMPONENT_SOURCE = (
    "def Pillar(height=2):\n"
    '    return component(name="Pillar", props={"height": height}, min_size=[1, height, 1],\n'
    '                     body=fill_region([0, 0, 0], [1, height, 1], block("minecraft:stone_bricks")))\n'
    "\n"
    "def _helper():\n"
    "    return None\n"
    "\n"
    "def build(height=3):\n"
    "    return Pillar(height)\n"
)


def save(client, **body):
    values = {"name": "pillar", "source": COMPONENT_SOURCE, "title": "Stone pillar",
              "description": "A stone brick column", "tags": ["Structural", "pillar"], "author": "agent-a"}
    return client.post("/library", json=values | body).json()


def test_save_records_metadata_and_files(config):
    client = TestClient(create_app(config))
    result = save(client)
    assert result["ok"] is True and result["created"] is True
    assert result["version"] == 1
    assert result["load_path"] == "../library/pillar/v1.star"
    assert result["exports"] == ["Pillar"]
    assert result["signatures"] == {"Pillar": "Pillar(height=2)"}
    assert result["params"] == [{"name": "height", "default": 3}]
    assert result["size"] == [1, 3, 1]

    entry_dir = config.library_dir / "pillar"
    assert (entry_dir / "v1.star").read_text(encoding="utf-8") == COMPONENT_SOURCE
    meta = json.loads((entry_dir / "meta.json").read_text(encoding="utf-8"))
    assert meta["tags"] == ["structural", "pillar"]
    assert meta["versions"][0]["palette_top"] == ["minecraft:stone_bricks"]
    assert client.get("/health").json()["library_entries"] == 1


def test_resave_identical_is_noop_and_changes_append_versions(config):
    client = TestClient(create_app(config))
    save(client)
    again = save(client)
    assert again["created"] is False and again["version"] == 1

    changed = save(client, source=COMPONENT_SOURCE.replace("stone_bricks", "deepslate_bricks"),
                   title=None, description=None, notes="darker", author="agent-b")
    assert changed["created"] is True and changed["version"] == 2
    assert "stone_bricks" in (config.library_dir / "pillar" / "v1.star").read_text(encoding="utf-8")

    meta = client.get("/library/pillar").json()
    assert meta["latest"] == 2
    assert meta["title"] == "Stone pillar"
    assert [v["author"] for v in meta["versions"]] == ["agent-a", "agent-b"]

    source = client.get("/library/pillar/source", params={"version": 1})
    assert source.headers["x-library-version"] == "1"
    assert "stone_bricks" in source.text
    assert "deepslate" in client.get("/library/pillar/source").text


def test_failed_build_is_not_saved(config):
    client = TestClient(create_app(config))
    result = save(client, source="def build():\n    return undefined_thing\n")
    assert result["ok"] is False
    assert result["error_kind"] == "starlark_error"
    assert not (config.library_dir / "pillar").exists()


def test_validation_errors(config):
    client = TestClient(create_app(config))
    assert client.post("/library", json={"name": "Bad Name", "source": SIMPLE_SOURCE}).status_code == 400
    assert client.post("/library", json={"name": "cell", "source": SIMPLE_SOURCE,
                                         "root_size": [1, 1, 1]}).status_code == 400  # no title
    assert client.post("/library", json={"name": "cell"}).status_code == 400
    response = client.post("/library", json={"name": "cell", "source": SIMPLE_SOURCE, "root_size": [1, 1, 1],
                                             "title": "t", "description": "d", "parent": "nope@1"})
    assert response.status_code == 404
    assert client.get("/library/missing").status_code == 404
    assert client.get("/library/..%2Fetc").status_code in (400, 404)


def test_save_by_artifact_id(config):
    client = TestClient(create_app(config))
    built = client.post("/build", json={"source": COMPONENT_SOURCE, "props": {"height": 4}}).json()
    result = client.post("/library", json={"name": "tall_pillar", "artifact_id": built["artifact_id"],
                                           "title": "Tall pillar", "description": "four high"}).json()
    assert result["ok"] is True
    assert result["artifact_id"] == built["artifact_id"]
    assert result["size"] == [1, 4, 1]
    meta = client.get("/library/tall_pillar").json()
    assert meta["versions"][0]["props"] == {"height": 4}


def test_scripts_can_load_saved_versions(config):
    client = TestClient(create_app(config))
    save(client)
    source = 'load("../library/pillar/v1.star", "Pillar")\n\ndef build():\n    return Pillar(5)\n'
    built = client.post("/build", json={"source": source}).json()
    assert built["ok"] is True
    assert built["size"] == [1, 5, 1]

    child = client.post("/library", json={"name": "pillar_pair", "source": source, "title": "Pair",
                                          "description": "uses pillar", "parent": "pillar@1"}).json()
    assert child["ok"] is True
    meta = client.get("/library/pillar_pair").json()
    assert meta["versions"][0]["loads"] == ["library/pillar/v1.star:Pillar"]
    assert meta["versions"][0]["parent"] == "pillar@1"
    assert client.get("/library/pillar").json()["used_by"] == [
        {"name": "pillar_pair", "version": 1, "loads_version": 1}]
    assert meta["used_by"] == []

    missing = 'load("../library/pillar/v9.star", "Pillar")\n\ndef build():\n    return Pillar()\n'
    result = client.post("/build", json={"source": missing}).json()
    assert result["ok"] is False
    assert result["diagnostics"][0]["message"] == "module not found: ../library/pillar/v9.star"


def test_search_ranks_and_filters(config):
    client = TestClient(create_app(config))
    save(client)
    client.post("/library", json={"name": "cell", "source": SIMPLE_SOURCE, "root_size": [1, 1, 1],
                                  "title": "Stone cell", "description": "single block", "tags": ["tiny"],
                                  "author": "agent-b"})

    names = lambda **params: [r["name"] for r in client.get("/library/search", params=params).json()["results"]]
    assert names(q="pillar") == ["pillar"]
    assert names(q="stone") == ["cell", "pillar"]  # equal title hits; newest first
    assert names(q="stone_bricks") == ["pillar"]
    assert names(tag="tiny") == ["cell"]
    assert names(author="agent-a") == ["pillar"]
    assert names(max_y=2) == ["cell"]
    assert set(names()) == {"pillar", "cell"}
    assert names(q="castle") == []


def test_analyze_source_fallback_without_python_syntax():
    source = 'load("../lib/roofs.star", "HipRoof")\ndef Hut(w):\n    return w if w > 1 else fail("x")\nlambda_x = lambda: 1 if True else 2 else 3\n'
    info = library.analyze_source(source, "build")
    assert info["exports"] == ["Hut"]
    assert info["signatures"] == {"Hut": "Hut(w)"}
    assert info["loads"] == ["lib/roofs.star:HipRoof"]
