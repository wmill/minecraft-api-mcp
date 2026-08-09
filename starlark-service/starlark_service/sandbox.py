"""Runs builds in a killable, resource-limited subprocess.

The starlark-to-nbt pipeline has no evaluation budget of its own, so
isolation happens out-of-process: a concurrency cap, a wall-clock timeout
backed by SIGKILL, a memory rlimit applied inside the worker, and volume/byte
caps checked by the worker (see runner.py).
"""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path
from typing import Any

from .config import ServiceConfig

HINTS = {
    "starlark_error": "fix the first diagnostic, then submit the edited source again",
    "build_error": "fix the first diagnostic, then submit the edited source again",
    "resource_limit": "reduce the structure's size or block variety, then submit again",
    "timeout": "reduce the structure's volume or loop counts, then submit again",
    "crash": "the build process died unexpectedly (possibly the memory cap); simplify the script and submit again",
}


class BuildQueueFull(Exception):
    pass


def _failure(error_kind: str, message: str) -> dict[str, Any]:
    return {
        "ok": False,
        "error_kind": error_kind,
        "diagnostics": [{
            "code": error_kind, "message": message, "component_path": "<root>",
            "file": None, "line": None, "region": None, "coordinates": None, "details": {},
        }],
    }


class Sandbox:
    def __init__(self, config: ServiceConfig):
        self._config = config
        self._active = 0

    async def build(self, source: str, entry: str, props: dict[str, Any],
                    root_size: list[int] | None, output_path: Path) -> dict[str, Any]:
        cfg = self._config
        if self._active >= cfg.max_concurrent_builds:
            raise BuildQueueFull(
                f"already running {self._active} builds; retry shortly"
            )
        self._active += 1
        try:
            result = await self._run_worker(source, entry, props, root_size, output_path)
        finally:
            self._active -= 1
        if not result.get("ok"):
            result["hint"] = HINTS.get(result.get("error_kind"), HINTS["build_error"])
        return result

    async def _run_worker(self, source: str, entry: str, props: dict[str, Any],
                          root_size: list[int] | None, output_path: Path) -> dict[str, Any]:
        cfg = self._config
        request = {
            "source": source,
            "entry": entry,
            "props": props,
            "root_size": root_size,
            "tool_dir": str(cfg.tool_dir),
            "output_path": str(output_path),
            "limits": {
                "memory_mb": cfg.build_memory_mb,
                "max_root_volume": cfg.max_root_volume,
                "max_nbt_bytes": cfg.max_nbt_bytes,
            },
        }
        process = await asyncio.create_subprocess_exec(
            sys.executable, "-m", "starlark_service.runner",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            stdout, stderr = await asyncio.wait_for(
                process.communicate(json.dumps(request).encode("utf-8")),
                timeout=cfg.build_timeout_s,
            )
        except TimeoutError:
            process.kill()
            await process.wait()
            output_path.unlink(missing_ok=True)
            return _failure("timeout", f"build exceeded the {cfg.build_timeout_s:g}s time limit")

        if process.returncode != 0:
            output_path.unlink(missing_ok=True)
            detail = stderr.decode("utf-8", errors="replace").strip().splitlines()
            return _failure("crash", detail[-1] if detail else "build worker exited abnormally")
        try:
            return json.loads(stdout.decode("utf-8"))
        except ValueError:
            output_path.unlink(missing_ok=True)
            return _failure("crash", "build worker produced unreadable output")
