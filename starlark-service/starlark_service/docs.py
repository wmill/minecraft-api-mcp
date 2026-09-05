"""Focused views of the compiler's canonical component catalog."""

from __future__ import annotations

import ast
import re
from pathlib import Path

SECTION = re.compile(r"(?=^## )", re.MULTILINE)
MODULE = re.compile(r"^### `lib/(\w+)\.star`.*$", re.MULTILINE)
ROW = re.compile(r"^\| `([A-Za-z_]\w*)\([^`]*`", re.MULTILINE)


class UnknownDocs(ValueError):
    pass


def _signatures(lib_dir: Path) -> dict[str, str]:
    # Only inspect syntax; never execute library code to produce documentation.
    signatures = {}
    for path in sorted(lib_dir.glob("*.star")):
        if path.stem == "showcase":
            continue
        for node in ast.parse(path.read_text(encoding="utf-8")).body:
            if isinstance(node, ast.FunctionDef):
                signatures[node.name] = f"{node.name}({ast.unparse(node.args)})"
    return signatures


def catalog_view(tool_dir: Path, topic: str = "full", component: str | None = None) -> str:
    catalog = (tool_dir / "docs/component-catalog.md").read_text(encoding="utf-8")
    signatures = _signatures(tool_dir / "lib")
    catalog = ROW.sub(lambda m: f"| `{signatures[m[1]]}`" if m[1] in signatures else m[0], catalog)
    sections = {part.splitlines()[0][3:]: part for part in SECTION.split(catalog) if part.startswith("## ")}
    library = sections["Component library"]
    matches = list(MODULE.finditer(library))
    modules = {
        match[1]: library[match.start():matches[i + 1].start() if i + 1 < len(matches) else len(library)].strip()
        for i, match in enumerate(matches)
    }
    topics = {
        "full": catalog,
        "dsl": "\n".join(sections[name] for name in
                           ("How a script runs", "Coordinates and geometry", "Build phases and overlap rules", "DSL reference")),
        "composition": sections["Composition patterns"],
        "errors": sections["Errors"],
        **modules,
    }
    components = {m[1]: module for module, body in modules.items() for m in ROW.finditer(body)}
    topics["components"] = "# Components\n\n" + "\n".join(
        f"- {name} ({module})" for name, module in components.items()
    ) + '\n\nUse get_starlark_docs(component="Name") for details.\n'
    if component is not None:
        if component not in components:
            raise UnknownDocs("Unknown component. Valid components: " + ", ".join(components))
        module = components[component]
        # Keep module-level orientation/constraints and the selected table row.
        lines = [line for line in modules[module].splitlines()
                 if not ROW.match(line) or ROW.match(line)[1] == component]
        return (f'# {component}\n\nload("../lib/{module}.star", "{component}")\n\n'
                "Components draw from [0,0,0], face south at rotation zero, and declare min_size.\n\n"
                + "\n".join(lines) + "\n")
    if topic == "quickstart":
        return (tool_dir / "docs/quickstart.md").read_text(encoding="utf-8")
    if topic not in topics:
        raise UnknownDocs("Unknown topic. Valid topics: quickstart, " + ", ".join(topics))
    return topics[topic]
