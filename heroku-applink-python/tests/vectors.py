import json
from datetime import datetime
from pathlib import Path
from typing import Any

VECTORS_DIR = Path(__file__).parent.parent.parent


def load_vectors(name: str) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = json.loads((VECTORS_DIR / name).read_text())["cases"]
    return cases


def parse_instant(value: str | None) -> datetime | None:
    return None if value is None else datetime.fromisoformat(value)
