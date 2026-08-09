from __future__ import annotations

from fastapi.testclient import TestClient

from conftest import SIMPLE_SOURCE, make_config
from starlark_service.app import create_app


def client_for(config) -> TestClient:
    return TestClient(create_app(config))


def test_build_success_and_cache_hit(config):
    client = client_for(config)
    body = {"source": SIMPLE_SOURCE, "root_size": [1, 1, 1]}

    first = client.post("/build", json=body).json()
    assert first["ok"] is True
    assert first["artifact_id"].startswith("slk_")
    assert first["cached"] is False
    assert first["size"] == [1, 1, 1]
    assert first["block_count"] == 1
    assert first["y_offset"] == 0
    assert first["palette"] == [{"block": "minecraft:stone", "count": 1}]

    second = client.post("/build", json=body).json()
    assert second["cached"] is True
    assert second["artifact_id"] == first["artifact_id"]


def test_build_can_load_component_library(config):
    source = (
        'load("../lib/random.star", "RANDOM_NUMBERS")\n'
        "\n"
        "def build():\n"
        '    return component(name="Cell", props={"count": len(RANDOM_NUMBERS)},\n'
        '                     body=place_block([0, 0, 0], block("minecraft:stone")))\n'
    )
    result = client_for(config).post("/build", json={"source": source, "root_size": [1, 1, 1]}).json()
    assert result["ok"] is True


def test_starlark_error_returns_diagnostics(config):
    source = "def build():\n    return undefined_thing\n"
    result = client_for(config).post("/build", json={"source": source, "root_size": [1, 1, 1]}).json()
    assert result["ok"] is False
    assert result["error_kind"] == "starlark_error"
    assert "undefined_thing" in result["diagnostics"][0]["message"]
    assert result["hint"]


def test_build_rule_error_returns_diagnostics(config):
    source = (
        "def build():\n"
        '    return component(name="Cell", props={},\n'
        '                     body=place_block([5, 0, 0], block("minecraft:stone")))\n'
    )
    result = client_for(config).post("/build", json={"source": source, "root_size": [1, 1, 1]}).json()
    assert result["ok"] is False
    assert result["error_kind"] == "build_error"
    assert result["diagnostics"][0]["code"] == "root_overflow"
    assert result["diagnostics"][0]["coordinates"] == [5, 0, 0]


def test_confined_loader_rejects_escapes(config):
    source = 'load("/etc/passwd", "X")\n\ndef build():\n    return None\n'
    result = client_for(config).post("/build", json={"source": source, "root_size": [1, 1, 1]}).json()
    assert result["ok"] is False
    assert result["diagnostics"][0]["code"] == "load_error"
    assert "module not found" in result["diagnostics"][0]["message"]


def test_oversized_source_is_rejected(tmp_path):
    config = make_config(tmp_path / "cache", max_source_bytes=10)
    response = client_for(config).post("/build", json={"source": SIMPLE_SOURCE, "root_size": [1, 1, 1]})
    assert response.status_code == 400


def test_artifact_metadata_and_nbt_roundtrip(config):
    client = client_for(config)
    built = client.post("/build", json={"source": SIMPLE_SOURCE, "root_size": [1, 1, 1]}).json()
    identifier = built["artifact_id"]

    metadata = client.get(f"/artifacts/{identifier}").json()
    assert metadata["block_count"] == 1

    nbt = client.get(f"/artifacts/{identifier}/nbt")
    assert nbt.status_code == 200
    assert nbt.content[:2] == b"\x1f\x8b"


def test_missing_artifact_is_404_and_bad_id_is_400(config):
    client = client_for(config)
    assert client.get("/artifacts/slk_0123456789abcdef").status_code == 404
    assert client.get("/artifacts/../../etc/passwd").status_code in (400, 404)
    assert client.get("/artifacts/slk_BAD").status_code == 400


def test_examples_and_catalog_routes(config):
    client = client_for(config)

    examples = client.get("/examples").json()
    names = {item["name"] for item in examples["examples"]}
    assert "cottage" in names

    source = client.get("/examples/cottage")
    assert source.status_code == 200
    assert "def build" in source.text

    assert client.get("/examples/../secrets").status_code in (400, 404)
    assert client.get("/examples/no_such_example").status_code == 404

    catalog = client.get("/docs/catalog")
    assert catalog.status_code == 200
    assert "place_block" in catalog.text


def test_health_reports_lib_and_cache(config):
    health = client_for(config).get("/health").json()
    assert health["ok"] is True
    assert health["lib_dir_exists"] is True
    assert health["lib_fingerprint"]
    assert health["cache"] == {"artifacts": 0, "bytes": 0}
