"""Bounded, actionable summaries of the service's detailed diagnostics."""

import json
from typing import Any

HINTS = {
    "block_conflict": "Move the conflicting parts apart or use identical structural blocks. Fixtures need empty/carved space; all carves run after structure.",
    "missing_component_size": "Pass size=[width, height, length] to at(), or wrap the child in a component with min_size.",
    "missing_root_size": "Set min_size on the root component or pass root_size=[width, height, length].",
    "unsupported_block": "Add a solid support on the fixture's attachment side within the structure.",
    "load_error": 'Check the component import; use get_starlark_docs(component="Name") for its ../lib/ path.',
    "load_cycle": "Remove the circular imports between library modules.",
}
BOUNDS_HINT = "Compare the reported bounds and coordinates with the part's size. Move the part inside or increase its assigned size; 90/270 rotations swap width and length."


def compact_diagnostics(result: dict[str, Any]) -> dict[str, Any]:
    groups: dict[str, dict[str, Any]] = {}
    diagnostics = result.get("diagnostics") or []
    for item in diagnostics:
        identity = {key: item.get(key) for key in
                    ("code", "message", "component_path", "file", "line", "region", "details")}
        key = json.dumps(identity, sort_keys=True)
        if key not in groups:
            code = item.get("code") or "build_error"
            hint = HINTS.get(code)
            if "overflow" in code or code in ("component_too_small", "invalid_box"):
                hint = BOUNDS_HINT
            groups[key] = {**identity, "code": code, "message": item.get("message") or "Build failed",
                           "details": item.get("details") or {}, "count": 0,
                           "coordinate_samples": [], "hint": hint}
        group = groups[key]
        group["count"] += 1
        coordinates = item.get("coordinates")
        if coordinates is not None and len(group["coordinate_samples"]) < 3:
            group["coordinate_samples"].append(coordinates)
    return {
        "ok": False, "compilation_ok": False,
        "error_kind": result.get("error_kind", "build_error"),
        "diagnostics": list(groups.values())[:5], "diagnostic_count": len(diagnostics),
        "omitted_group_count": max(0, len(groups) - 5),
        "hint": result.get("hint") or "Fix the first distinct diagnostic and compile again.",
    }


def diagnostic_text(result: dict[str, Any]) -> str:
    lines = [f"Build failed ({result['error_kind']}): {result['diagnostic_count']} diagnostic(s)."]
    for group in result["diagnostics"]:
        lines.append(f"[{group['code']}] {group['message']} ({group['count']} occurrence(s))")
        for key in ("component_path", "file", "line", "region", "details", "coordinate_samples", "hint"):
            if group.get(key) is not None and group[key] != {} and group[key] != []:
                lines.append(f"  {key}: {group[key]}")
    if result["omitted_group_count"]:
        lines.append(f"{result['omitted_group_count']} additional error group(s) omitted.")
    lines.append(result["hint"])
    return "\n".join(lines)
