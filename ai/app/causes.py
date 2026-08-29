"""G9. The closed cause vocabulary.

The model chooses a cause; it does not invent one. That is what makes section 22 measurable: a name
is only checkable against a fixed list, and a free-form symbol turns a correct reading of the record
into a false attribution whenever the model picks a synonym.

UNDETERMINED is always available and is not a cause. It routes to the abstention path.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

UNDETERMINED = "UNDETERMINED"

_DEFAULT = "/app/data/causes.json"


def _load() -> list[str]:
    path = Path(os.environ.get("PXE_CAUSES_PATH", _DEFAULT))
    if not path.exists():
        # Running outside the container, from the repository root or from ai/.
        for candidate in (Path("data/causes.json"), Path("../data/causes.json")):
            if candidate.exists():
                path = candidate
                break
    return sorted(json.loads(path.read_text(encoding="utf-8")))


CAUSES: list[str] = _load()
CHOICES: list[str] = CAUSES + [UNDETERMINED]
