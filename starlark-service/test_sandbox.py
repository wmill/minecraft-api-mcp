from __future__ import annotations

from fastapi.testclient import TestClient

from conftest import SIMPLE_SOURCE, make_config
from starlark_service.app import create_app


def build(config, body):
    return TestClient(create_app(config)).post("/build", json=body)


def test_runaway_script_is_killed_by_timeout(tmp_path):
    config = make_config(tmp_path / "cache", build_timeout_s=1.0)
    source = (
        "def build():\n"
        "    total = 0\n"
        "    for i in range(1000000000):\n"
        "        total += i\n"
        "    return None\n"
    )
    result = build(config, {"source": source, "root_size": [1, 1, 1]}).json()
    assert result["ok"] is False
    assert result["error_kind"] == "timeout"
    assert "time limit" in result["diagnostics"][0]["message"]
    assert result["hint"]


def test_root_volume_cap_is_enforced(tmp_path):
    config = make_config(tmp_path / "cache", max_root_volume=10)
    result = build(config, {"source": SIMPLE_SOURCE, "root_size": [3, 3, 3]}).json()
    assert result["ok"] is False
    assert result["error_kind"] == "resource_limit"
    assert "root volume" in result["diagnostics"][0]["message"]


def test_nbt_byte_cap_is_enforced(tmp_path):
    config = make_config(tmp_path / "cache", max_nbt_bytes=10)
    result = build(config, {"source": SIMPLE_SOURCE, "root_size": [1, 1, 1]}).json()
    assert result["ok"] is False
    assert result["error_kind"] == "resource_limit"
    assert "exceeding the maximum" in result["diagnostics"][0]["message"]


def test_full_queue_returns_429(tmp_path):
    config = make_config(tmp_path / "cache", max_concurrent_builds=0)
    response = build(config, {"source": SIMPLE_SOURCE, "root_size": [1, 1, 1]})
    assert response.status_code == 429


def test_failed_builds_leave_no_artifacts(tmp_path):
    cache_dir = tmp_path / "cache"
    config = make_config(cache_dir, max_nbt_bytes=10)
    build(config, {"source": SIMPLE_SOURCE, "root_size": [1, 1, 1]})
    assert not list(cache_dir.glob("*/slk_*"))
    assert not list(cache_dir.glob("*/*.tmp"))
