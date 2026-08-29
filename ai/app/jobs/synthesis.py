"""Job A. "Say this clearly."

Nearly free and nearly safe: the risk here is wording, not causality. It runs only on the payments
where Job B ran, because on the deterministic paths the renderings come from per-cause templates
and a payment explained by a rule must cost nothing.
"""

from __future__ import annotations

import json

from .. import gemini
from ..prompts import SYNTHESIS, SYNTHESIS_VERSION
from ..schemas import NarrativeRequest, NarrativeResponse, NarrativeResult, Usage

RESPONSE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "merchant": {"type": "STRING"},
        "support": {"type": "STRING"},
        "engineer": {"type": "STRING"},
        "placeholders": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {"name": {"type": "STRING"}, "field": {"type": "STRING"}},
                "required": ["name", "field"],
            },
        },
    },
    "required": ["merchant", "support", "engineer"],
}


def run(request: NarrativeRequest) -> NarrativeResponse:
    prompt = json.dumps(request.model_dump(), indent=2, default=str)
    raw, usage = gemini.generate(SYNTHESIS, prompt, RESPONSE_SCHEMA)

    pairs = raw.get("placeholders") or []
    if isinstance(pairs, list):
        raw["placeholders"] = {p["name"]: p["field"] for p in pairs}

    return NarrativeResponse(
        result=NarrativeResult.model_validate(raw),
        usage=Usage(prompt_version=SYNTHESIS_VERSION, **usage),
    )
