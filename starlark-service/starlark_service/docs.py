"""Focused views of the compiler's canonical component catalog."""

from __future__ import annotations

import ast
import re
from pathlib import Path

SECTION = re.compile(r"(?=^## )", re.MULTILINE)
MODULE = re.compile(r"^### `lib/(\w+)\.star`.*$", re.MULTILINE)
ROW = re.compile(r"^\| `([A-Za-z_]\w*)\([^`]*`", re.MULTILINE)


LIBRARY_GUIDE = """# Script library

Saved scripts are shared by every agent using this server. Before writing a structure from
scratch, search_starlark_library for something to reuse; after a build you are happy with,
save_starlark_script it (by artifact_id) with a clear title, description, and tags.

Two ways to reuse an entry:

1. Fork: get_starlark_script(name) returns the full source. Edit it, build it, and save it
   under a new name with parent="name@version" (or as a new version of the same name).
2. Load: library scripts can be load()ed like lib/ components. Use the pinned path that
   get_starlark_script / search results give you:

```python
load("../library/gothic_window/v2.star", "GothicWindow")

def build():
    return GothicWindow()
```

Rules:
- Paths are always pinned to a version (v1, v2, ...). Saved versions never change, so a
  script that loads one keeps building the same structure forever.
- Every script, including saved library modules, uses the same load paths:
  ../lib/<module>.star and ../library/<name>/v<N>.star.
- Only scripts that build successfully can be saved. Saving identical source again is a
  no-op; saving changed source under an existing name creates the next version.
- Top-level UpperCamel functions are listed as exports (lowercase helpers stay loadable but
  unlisted). Write reusable parts as UpperCamel component functions that draw from [0,0,0],
  face south (+Z), and declare min_size, like lib/ components. Even a one-off structure is
  more reusable as `def Thing(...): ...` plus `def build(): return Thing()`.
- The listed size is the demo build()'s output; an exported component's size follows its
  arguments (results show each export's signature).
- get_starlark_script shows which library versions a script uses and which saved scripts
  use it. Loads are pinned, so saving a new version never changes existing dependents.
"""


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


def catalog_view(tool_dir: Path, topic: str = "quickstart", component: str | None = None) -> str:
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
        "library": LIBRARY_GUIDE,
        **modules,
    }
    components = {m[1]: module for module, body in modules.items() for m in ROW.finditer(body)}
    topics["components"] = "# Components\n\n" + "\n".join(
        f"- {name} ({module})" for name, module in components.items()
    ) + '\n\nUse get_starlark_docs(component="Name") for details.\n'
    topics["index"] = topics["components"]
    math_reference = sections["DSL reference"].split("Math builtins", 1)[1].strip()
    topics["math"] = (
        "# Math\n\nMath builtins " + math_reference + "\n\n"
        "These functions and PI are globals: no import is needed. There is no math namespace or math.star.\n\n"
        "```python\nradius = round(sqrt(25))\nheight = isqrt(50)\nx = round(cos(PI) * radius)\n```\n"
    )
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
