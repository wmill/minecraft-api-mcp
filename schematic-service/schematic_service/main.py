"""Command-line entrypoint for the schematic service."""

from __future__ import annotations

import uvicorn


def main() -> None:
    uvicorn.run("schematic_service.app:app", host="0.0.0.0", port=7080)


if __name__ == "__main__":
    main()
