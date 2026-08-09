"""CLI entrypoint for the Starlark build service."""

from __future__ import annotations

import uvicorn


def main() -> None:
    uvicorn.run("starlark_service.app:app", host="0.0.0.0", port=7090)


if __name__ == "__main__":
    main()
