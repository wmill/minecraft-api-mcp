from __future__ import annotations

import os

from starlark_service import cache


def test_artifact_id_is_stable_and_sensitive_to_inputs():
    base = cache.artifact_id("src", "build", {"a": 1}, None, "fp")
    assert base == cache.artifact_id("src", "build", {"a": 1}, None, "fp")
    assert base.startswith("slk_") and len(base) == 20
    assert base != cache.artifact_id("src2", "build", {"a": 1}, None, "fp")
    assert base != cache.artifact_id("src", "other", {"a": 1}, None, "fp")
    assert base != cache.artifact_id("src", "build", {"a": 2}, None, "fp")
    assert base != cache.artifact_id("src", "build", {"a": 1}, [1, 2, 3], "fp")
    assert base != cache.artifact_id("src", "build", {"a": 1}, None, "fp2")


def test_metadata_roundtrip_and_stats(tmp_path):
    identifier = cache.artifact_id("src", "build", {}, None, "fp")
    nbt = cache.nbt_path(tmp_path, identifier)
    nbt.parent.mkdir(parents=True)
    nbt.write_bytes(b"x" * 10)
    cache.store_metadata(tmp_path, identifier, {"block_count": 1})

    assert cache.load_metadata(tmp_path, identifier) == {"block_count": 1}
    result = cache.stats(tmp_path)
    assert result["artifacts"] == 1
    assert result["bytes"] > 10


def test_missing_artifact_returns_none(tmp_path):
    assert cache.load_metadata(tmp_path, "slk_0123456789abcdef") is None


def test_eviction_removes_oldest_first(tmp_path):
    identifiers = [cache.artifact_id(f"src{i}", "build", {}, None, "fp") for i in range(3)]
    for age, identifier in enumerate(identifiers):
        nbt = cache.nbt_path(tmp_path, identifier)
        nbt.parent.mkdir(parents=True, exist_ok=True)
        nbt.write_bytes(b"x" * 100)
        cache.store_metadata(tmp_path, identifier, {})
        os.utime(nbt, (age, age))  # identifiers[0] is oldest

    sizes = cache.stats(tmp_path)["bytes"]
    cache.evict(tmp_path, sizes - 1)  # forces exactly one eviction

    assert cache.load_metadata(tmp_path, identifiers[0]) is None
    assert cache.load_metadata(tmp_path, identifiers[1]) is not None
    assert cache.load_metadata(tmp_path, identifiers[2]) is not None


def test_lib_fingerprint_tracks_lib_contents(tmp_path):
    (tmp_path / "lib").mkdir()
    (tmp_path / "lib" / "a.star").write_text("A = 1\n", encoding="utf-8")
    first = cache.lib_fingerprint(tmp_path)
    assert first == cache.lib_fingerprint(tmp_path)
    (tmp_path / "lib" / "a.star").write_text("A = 2\n", encoding="utf-8")
    assert first != cache.lib_fingerprint(tmp_path)
