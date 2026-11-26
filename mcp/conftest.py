import pytest


@pytest.fixture(autouse=True)
def _spy_mode_off(monkeypatch):
    """Keep tests from writing into a developer's SPY_DIR from mcp/.env."""
    monkeypatch.setenv("SPY_DIR", "")
