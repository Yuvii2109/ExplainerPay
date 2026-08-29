"""Job B. "Why did this happen?"

The expensive, dangerous, interesting one. It runs only when no response code and no rule could
account for a payment, and its output is a claim about causality rather than about wording, which
is why it is the job that is allowed to abstain, and the one whose output is always marked.
"""

from __future__ import annotations

import json

from .. import gemini
from ..causes import CHOICES, UNDETERMINED
from ..prompts import HYPOTHESIS, HYPOTHESIS_VERSION
from ..schemas import FactSet, HypothesisResponse, HypothesisResult, Usage

CLAIM_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "id": {"type": "STRING"},
        "kind": {"type": "STRING", "enum": ["FACT", "HYPOTHESIS"]},
        "text": {"type": "STRING"},
        "citations": {"type": "ARRAY", "items": {"type": "STRING"}},
        "placeholders": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {"name": {"type": "STRING"}, "field": {"type": "STRING"}},
                "required": ["name", "field"],
            },
        },
    },
    "required": ["id", "kind", "text", "citations"],
}

RESPONSE_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "determinable": {"type": "BOOLEAN"},
        "root_cause": {"type": "STRING", "enum": CHOICES},
        "confidence": {"type": "NUMBER", "nullable": True},
        "claims": {"type": "ARRAY", "items": CLAIM_SCHEMA},
        "candidates_considered": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {"cause": {"type": "STRING"}, "evidence": {"type": "STRING"}},
                "required": ["cause", "evidence"],
            },
        },
    },
    "required": ["determinable", "root_cause", "claims"],
}


def _flatten_placeholders(raw: dict) -> dict:
    """The wire shape is a list of pairs, because the schema language has no free-form maps."""
    for claim in raw.get("claims", []):
        pairs = claim.get("placeholders") or []
        if isinstance(pairs, list):
            claim["placeholders"] = {p["name"]: p["field"] for p in pairs}
    return raw


def _apply_g9(raw: dict) -> dict:
    """UNDETERMINED is not a cause. Choosing it is choosing to abstain."""
    if raw.get("root_cause") == UNDETERMINED:
        raw["root_cause"] = None
        raw["determinable"] = False
        raw["confidence"] = None
    elif not raw.get("determinable"):
        raw["root_cause"] = None
        raw["confidence"] = None
    return raw


def run(facts: FactSet) -> HypothesisResponse:
    prompt = json.dumps(facts.model_dump(), indent=2, default=str)
    raw, usage = gemini.generate(HYPOTHESIS, prompt, RESPONSE_SCHEMA)
    result = HypothesisResult.model_validate(_apply_g9(_flatten_placeholders(raw)))
    return HypothesisResponse(
        result=result,
        usage=Usage(prompt_version=HYPOTHESIS_VERSION, **usage),
    )
