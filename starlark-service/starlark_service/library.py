"""Durable, searchable library of saved Starlark scripts.

Layout: ``<library_dir>/<name>/meta.json`` plus one ``v<N>.star`` per version.
Versions are append-only and never rewritten: scripts load them by pinned path
(``../library/<name>/v<N>.star``) and the artifact cache keys builds on source
text alone, so mutating a saved version would silently stale cached artifacts.
The directory is meant to be committed to git, so files are plain and diffable.
"""

from __future__ import annotations

import ast
import hashlib
import json
import os
import re
import time
import uuid
from pathlib import Path
from typing import Any

NAME_PATTERN = re.compile(r"^[a-z][a-z0-9_]{1,47}$")
REF_PATTERN = re.compile(r"^([a-z][a-z0-9_]{1,47})@(\d+)$")
TAG_PATTERN = re.compile(r"^[a-z0-9][a-z0-9_-]{0,31}$")
MAX_TAGS = 16
PALETTE_TOP = 8

_DEF = re.compile(r"^def\s+([A-Za-z_]\w*)\s*\(", re.MULTILINE)
_LOAD = re.compile(r'^load\(\s*"([^"]+)"((?:\s*,\s*(?:\w+\s*=\s*)?"[^"]*")*)\s*,?\s*\)', re.MULTILINE)
_LOAD_SYMBOL = re.compile(r'"([^"]*)"')


class LibraryError(ValueError):
    """Bad input; maps to HTTP 400."""


class LibraryNotFound(LookupError):
    """Unknown entry or version; maps to HTTP 404."""


def validate_name(name: str) -> str:
    if not NAME_PATTERN.fullmatch(name):
        raise LibraryError("name must be lowercase snake_case: a letter, then 1-47 of [a-z0-9_]")
    return name


def load_path(name: str, version: int) -> str:
    return f"../library/{name}/v{version}.star"


def _entry_dir(library_dir: Path, name: str) -> Path:
    return library_dir / validate_name(name)


def load_meta(library_dir: Path, name: str) -> dict[str, Any]:
    path = _entry_dir(library_dir, name) / "meta.json"
    if not path.exists():
        raise LibraryNotFound(f"library entry {name!r} not found")
    return json.loads(path.read_text(encoding="utf-8"))


def list_meta(library_dir: Path) -> list[dict[str, Any]]:
    if not library_dir.is_dir():
        return []
    entries = []
    for path in sorted(library_dir.glob("*/meta.json")):
        try:
            entries.append(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, ValueError):
            continue
    return entries


def read_source(library_dir: Path, name: str, version: int | None = None) -> tuple[int, str]:
    meta = load_meta(library_dir, name)
    version = meta["latest"] if version is None else version
    path = _entry_dir(library_dir, name) / f"v{version}.star"
    if not path.exists():
        raise LibraryNotFound(f"{name} has no version {version}; latest is {meta['latest']}")
    return version, path.read_text(encoding="utf-8")


def analyze_source(source: str, entry: str) -> dict[str, Any]:
    """Entry params, public top-level defs, and load() symbols, from syntax only.

    Starlark is close enough to Python syntax for ``ast`` to parse nearly every
    script; when it can't, fall back to line regexes and skip params.
    """
    try:
        module = ast.parse(source)
    except SyntaxError:
        defs = _DEF.findall(source)
        loads = [_format_load(path, _LOAD_SYMBOL.findall(symbols)) for path, symbols in _LOAD.findall(source)]
        return {"params": [], "exports": _public(defs, entry), "loads": loads}

    defs, loads, params = [], [], []
    for node in module.body:
        if isinstance(node, ast.FunctionDef):
            defs.append(node.name)
            if node.name == entry:
                params = _params(node.args)
        elif (isinstance(node, ast.Expr) and isinstance(node.value, ast.Call)
              and isinstance(node.value.func, ast.Name) and node.value.func.id == "load"
              and node.value.args and isinstance(node.value.args[0], ast.Constant)):
            call = node.value
            symbols = [arg.value for arg in call.args[1:] if isinstance(arg, ast.Constant)]
            symbols += [kw.value.value for kw in call.keywords if isinstance(kw.value, ast.Constant)]
            loads.append(_format_load(call.args[0].value, symbols))
    return {"params": params, "exports": _public(defs, entry), "loads": loads}


def _params(args: ast.arguments) -> list[dict[str, Any]]:
    positional = args.posonlyargs + args.args
    defaults = [None] * (len(positional) - len(args.defaults)) + list(args.defaults)
    pairs = list(zip(positional, defaults)) + list(zip(args.kwonlyargs, args.kw_defaults))
    params = []
    for arg, default in pairs:
        param: dict[str, Any] = {"name": arg.arg}
        if default is not None:
            try:
                param["default"] = ast.literal_eval(default)
            except ValueError:
                param["default"] = ast.unparse(default)
        params.append(param)
    return params


def _public(defs: list[str], entry: str) -> list[str]:
    # Advertise UpperCamel component functions only, like lib/; lowercase helpers stay loadable but unlisted.
    return [name for name in dict.fromkeys(defs) if name[0].isupper() and name != entry]


def _format_load(path: str, symbols: list[str]) -> str:
    return path.removeprefix("../") + ":" + ",".join(symbols)


def _clean_tags(tags: list[str]) -> list[str]:
    cleaned = list(dict.fromkeys(tag.strip().lower().replace(" ", "-") for tag in tags if tag.strip()))
    bad = [tag for tag in cleaned if not TAG_PATTERN.fullmatch(tag)]
    if bad:
        raise LibraryError(f"invalid tags {bad}: use short lowercase words, digits, '-' or '_'")
    if len(cleaned) > MAX_TAGS:
        raise LibraryError(f"at most {MAX_TAGS} tags")
    return cleaned


def _check_parent(library_dir: Path, parent: str | None) -> None:
    if parent is None:
        return
    match = REF_PATTERN.fullmatch(parent)
    if not match:
        raise LibraryError("parent must look like name@version, e.g. arcanum_spire@2")
    meta = load_meta(library_dir, match[1])
    if int(match[2]) > meta["latest"]:
        raise LibraryNotFound(f"parent {parent} not found; {match[1]} latest is {meta['latest']}")


def _write_json(path: Path, value: dict[str, Any]) -> None:
    tmp = path.with_name(f".{uuid.uuid4().hex}.tmp")
    tmp.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def save_version(library_dir: Path, name: str, source: str, build: dict[str, Any], *,
                 title: str | None, description: str | None, tags: list[str] | None,
                 author: str | None, notes: str | None, parent: str | None,
                 root_size: list[int] | None) -> tuple[dict[str, Any], dict[str, Any], bool]:
    """Append a version (callers serialize calls). Returns (meta, version record, created).

    Re-saving source identical to the latest version (same entry/props) is a no-op
    that returns the existing record, so retries don't mint duplicate versions.
    """
    entry_dir = _entry_dir(library_dir, name)
    try:
        meta = load_meta(library_dir, name)
    except LibraryNotFound:
        meta = None
    if meta is None and not (title and title.strip() and description and description.strip()):
        raise LibraryError("title and description are required when saving a new library entry")
    _check_parent(library_dir, parent)
    clean_tags = _clean_tags(tags) if tags is not None else None

    sha = hashlib.sha256(source.encode("utf-8")).hexdigest()
    if meta is not None:
        latest = meta["versions"][-1]
        if (latest["sha256"] == sha and latest["entry"] == build["entry"]
                and latest["props"] == build["props"]):
            return meta, latest, False

    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    version = 1 if meta is None else meta["latest"] + 1
    record = {
        "version": version,
        "created_at": now,
        "author": author,
        "notes": notes,
        "parent": parent,
        "sha256": sha,
        "entry": build["entry"],
        "props": build["props"],
        "root_size": root_size,
        **analyze_source(source, build["entry"]),
        "artifact_id": build["artifact_id"],
        "size": build["size"],
        "block_count": build["block_count"],
        "ground_level": build["ground_level"],
        "y_offset": build["y_offset"],
        "palette_top": [item["block"] for item in build["palette"][:PALETTE_TOP]],
    }

    entry_dir.mkdir(parents=True, exist_ok=True)
    # "x" mode: a version file is written exactly once and never replaced.
    with (entry_dir / f"v{version}.star").open("x", encoding="utf-8") as handle:
        handle.write(source)

    if meta is None:
        meta = {"name": name, "title": title.strip(), "description": description.strip(),
                "tags": clean_tags or [], "author": author, "created_at": now, "versions": []}
    else:
        if title and title.strip():
            meta["title"] = title.strip()
        if description and description.strip():
            meta["description"] = description.strip()
        if clean_tags is not None:
            meta["tags"] = clean_tags
    meta["versions"].append(record)
    meta["latest"] = version
    meta["updated_at"] = now
    _write_json(entry_dir / "meta.json", meta)
    return meta, record, True


def summary(meta: dict[str, Any]) -> dict[str, Any]:
    latest = meta["versions"][-1]
    return {
        "name": meta["name"],
        "latest": meta["latest"],
        "title": meta["title"],
        "description": meta["description"],
        "tags": meta["tags"],
        "author": meta.get("author"),
        "updated_at": meta.get("updated_at"),
        "size": latest["size"],
        "block_count": latest["block_count"],
        "params": latest["params"],
        "exports": latest["exports"],
        "loads": latest["loads"],
        "parent": latest["parent"],
        "load_path": load_path(meta["name"], meta["latest"]),
    }


def search(entries: list[dict[str, Any]], query: str = "", *, tag: str | None = None,
           author: str | None = None, max_size: tuple[int | None, int | None, int | None] = (None, None, None),
           limit: int = 10) -> list[dict[str, Any]]:
    """Term-count ranking (name/title/tag hits weigh more), newest first on ties."""
    terms = [term.lower() for term in query.split() if term.strip()]
    tag = tag.strip().lower() if tag else None
    author = author.strip().lower() if author else None

    def matches(meta: dict[str, Any]) -> bool:
        latest = meta["versions"][-1]
        if tag and tag not in meta["tags"]:
            return False
        if author and author not in {(v.get("author") or "").lower() for v in meta["versions"]}:
            return False
        return all(cap is None or extent <= cap for extent, cap in zip(latest["size"], max_size))

    def score(meta: dict[str, Any]) -> int:
        latest = meta["versions"][-1]
        strong = " ".join([meta["name"].replace("_", " "), meta["title"], " ".join(meta["tags"])]).lower()
        weak = " ".join([
            meta["description"], latest.get("notes") or "", " ".join(latest["exports"]),
            " ".join(latest["loads"]), " ".join(latest["palette_top"]),
        ]).lower()
        return sum(3 if term in strong else 1 if term in weak else 0 for term in terms)

    ranked = [(score(meta), meta) for meta in entries if matches(meta)]
    if terms:
        ranked = [pair for pair in ranked if pair[0] > 0]
    ranked.sort(key=lambda pair: (pair[0], pair[1].get("updated_at") or ""), reverse=True)
    return [summary(meta) | {"score": points} for points, meta in ranked[: max(1, min(limit, 50))]]
