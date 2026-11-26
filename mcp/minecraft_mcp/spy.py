"""
Spy mode: an opt-in record of tool calls for a live demo viewer.

When ``SPY_DIR`` is set, every tool call writes a start event (tool name and
sanitized arguments, including full Starlark sources) and an end event (status,
duration, a short text excerpt and any images) into that directory. The separate
``minecraft-spy`` viewer follows the directory and shows the calls in a browser.

The spy never affects a tool call: every public method swallows its own errors.
Files are written atomically (temp file + rename) so the viewer never reads a
partial event. See docs/spy-mode.md.
"""

from __future__ import annotations

import base64
import binascii
import itertools
import json
import os
import sys
import time
from pathlib import Path
from typing import Any

REDACTED = "•••"
MAX_LIST_ITEMS = 20
MAX_STRING_CHARS = 2000
MAX_RESULT_CHARS = 600
KEEP_CALLS = 200
PRUNE_EVERY = 25

# Results of these tools carry reservation tokens, so their text is not recorded.
REDACT_RESULT_TOOLS = frozenset({"acquire_area_lock", "update_area_lock", "get_operation"})

IMAGE_EXTENSIONS = {
    "image/png": "png",
    "image/jpeg": "jpg",
    "image/webp": "webp",
    "image/gif": "gif",
}


def _block_shape(value: Any) -> list[int]:
    shape = []
    while isinstance(value, list):
        shape.append(len(value))
        value = value[0] if value else None
    return shape


def _shrink(value: Any, key: str | None = None) -> Any:
    if isinstance(value, list):
        if key == "blocks":
            shape = _block_shape(value)
            return f"[{'×'.join(str(n) for n in shape)} blocks]"
        if len(value) > MAX_LIST_ITEMS:
            return f"[list: {len(value)} items]"
        return [_shrink(item) for item in value]
    if isinstance(value, dict):
        return {k: _sanitize_value(k, v) for k, v in value.items()}
    if isinstance(value, str) and len(value) > MAX_STRING_CHARS:
        return value[:MAX_STRING_CHARS] + f"… ({len(value)} chars)"
    return value


def _sanitize_value(key: str, value: Any) -> Any:
    lowered = str(key).lower()
    if lowered == "lock_id" or "token" in lowered:
        return REDACTED
    return _shrink(value, key)


def sanitize_args(tool: str, args: dict[str, Any] | None) -> dict[str, Any]:
    """Make tool arguments safe and small enough to show on screen.

    Reservation tokens are redacted, Starlark ``source`` strings are kept whole and
    marked for highlighting, and bulky block arrays become a shape note.
    """
    out: dict[str, Any] = {}
    for key, value in (args or {}).items():
        if key == "source" and isinstance(value, str):
            out[key] = {"kind": "starlark", "code": value}
        else:
            out[key] = _sanitize_value(key, value)
    return out


def _content_blocks(result: Any) -> list[Any]:
    if isinstance(result, list):
        return result
    return list(getattr(result, "content", None) or [])


def result_text(tool: str, result: Any) -> str:
    if tool in REDACT_RESULT_TOOLS:
        return "[redacted]"
    texts = [getattr(b, "text", "") for b in _content_blocks(result) if getattr(b, "type", None) == "text"]
    text = "\n".join(t for t in texts if t)
    if len(text) > MAX_RESULT_CHARS:
        return text[:MAX_RESULT_CHARS] + "…"
    return text


class Spy:
    """Does nothing. Used when spy mode is off."""

    enabled = False

    def start(self, tool: str, arguments: dict[str, Any] | None) -> str | None:
        return None

    def end(self, call_id: str | None, tool: str, result: Any, duration_s: float) -> None:
        return None


class SpoolSpy(Spy):
    """Writes call events into a spool directory for the viewer."""

    enabled = True

    def __init__(self, directory: str | os.PathLike[str]):
        self.dir = Path(directory)
        self.dir.mkdir(parents=True, exist_ok=True)
        self.pid = os.getpid()
        self._seq = itertools.count()
        self._ends = 0
        self._prune()

    def start(self, tool: str, arguments: dict[str, Any] | None) -> str | None:
        try:
            call_id = f"{int(time.time() * 1000):013d}-{self.pid}-{next(self._seq):05d}"
            self._write_json(f"{call_id}-start.json", {
                "type": "start",
                "call_id": call_id,
                "pid": self.pid,
                "tool": tool,
                "ts": time.time(),
                "args": sanitize_args(tool, arguments),
            })
            return call_id
        except Exception as exc:  # never break a tool call
            print(f"spy: start failed: {exc}", file=sys.stderr)
            return None

    def end(self, call_id: str | None, tool: str, result: Any, duration_s: float) -> None:
        if call_id is None:
            return
        try:
            images = self._write_images(call_id, result)
            self._write_json(f"{call_id}-end.json", {
                "type": "end",
                "call_id": call_id,
                "ts": time.time(),
                "duration_ms": round(duration_s * 1000),
                "is_error": bool(getattr(result, "isError", False)),
                "text": result_text(tool, result),
                "images": images,
            })
            self._ends += 1
            if self._ends % PRUNE_EVERY == 0:
                self._prune()
        except Exception as exc:  # never break a tool call
            print(f"spy: end failed: {exc}", file=sys.stderr)

    def _write_images(self, call_id: str, result: Any) -> list[str]:
        names = []
        for block in _content_blocks(result):
            if getattr(block, "type", None) != "image":
                continue
            ext = IMAGE_EXTENSIONS.get(getattr(block, "mimeType", ""), None)
            if ext is None:
                continue
            try:
                data = base64.b64decode(getattr(block, "data", ""), validate=True)
            except (binascii.Error, ValueError):
                continue
            name = f"{call_id}-{len(names)}.{ext}"
            self._write_bytes(name, data)
            names.append(name)
        return names

    def _write_json(self, name: str, payload: dict[str, Any]) -> None:
        self._write_bytes(name, json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8"))

    def _write_bytes(self, name: str, data: bytes) -> None:
        tmp = self.dir / f".{name}.tmp"
        tmp.write_bytes(data)
        os.replace(tmp, self.dir / name)

    def _prune(self) -> None:
        try:
            starts = sorted(p.name for p in self.dir.glob("*-start.json"))
            for name in starts[:-KEEP_CALLS] if len(starts) > KEEP_CALLS else []:
                call_id = name[: -len("-start.json")]
                for path in self.dir.glob(f"{call_id}-*"):
                    path.unlink(missing_ok=True)
        except Exception as exc:
            print(f"spy: prune failed: {exc}", file=sys.stderr)


def make_spy(spy_dir: str | None = None) -> Spy:
    """Return a SpoolSpy for ``spy_dir`` (default: configured ``SPY_DIR``), else a no-op spy."""
    if spy_dir is None:
        from .config import load_config
        spy_dir = load_config().spy_dir
    if not spy_dir:
        return Spy()
    try:
        spy = SpoolSpy(spy_dir)
        print(f"Spy mode on: writing tool call events to {spy.dir}", file=sys.stderr)
        return spy
    except Exception as exc:
        print(f"spy: disabled, cannot use {spy_dir}: {exc}", file=sys.stderr)
        return Spy()
