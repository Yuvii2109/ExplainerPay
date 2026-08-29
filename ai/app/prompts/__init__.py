"""Versioned, hashed prompts.

Every explanation records the ``prompt_version`` it was produced under, or it cannot be reproduced.
The version is the content hash, so editing a prompt cannot silently keep the old label: the
version changes, the cache key changes with it, and the eval harness sees a different run.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

_DIR = Path(__file__).parent


def _load(name: str) -> tuple[str, str]:
    text = (_DIR / name).read_text(encoding="utf-8")
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]
    stem = name.rsplit(".", 1)[0]
    return text, stem + "@" + digest


HYPOTHESIS, HYPOTHESIS_VERSION = _load("hypothesis.txt")
SYNTHESIS, SYNTHESIS_VERSION = _load("synthesis.txt")

VERSIONS = {"hypothesis": HYPOTHESIS_VERSION, "synthesis": SYNTHESIS_VERSION}
