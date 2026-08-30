"""Job B. "Why did this happen?"

The expensive, dangerous, interesting one. It runs only when no response code and no rule could
account for a payment, and its output is a claim about causality rather than about wording, which
is why it is the job that is allowed to abstain, and the one whose output is always marked.
"""

from __future__ import annotations

import json
import re

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
                "properties": {
                    "cause": {"type": "STRING", "enum": CHOICES},
                    "evidence": {"type": "STRING"},
                    # The hop that rules this candidate out, or empty when nothing does.
                    "ruled_out_by": {"type": "STRING"},
                },
                "required": ["cause", "evidence", "ruled_out_by"],
            },
        },
    },
    "required": ["determinable", "root_cause", "claims", "candidates_considered"],
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


CITATION = re.compile(r"^(hop|ref|rule|code):.+")


def _must_separate(raw: dict) -> dict:
    """A cause is only named when the record rules the alternatives out.

    Asking a model to be careful does not make it careful. What does is refusing to accept an
    answer whose own working does not support it: every rival candidate has to be ruled out by
    something in the record, cited. If even one is left standing, two causes fit the evidence and
    the honest output is that it cannot be determined.

    This is the same move made everywhere else in the design. The model is not trusted to weigh
    ambiguity well; it is required to show the evidence that resolved it, and the answer is
    discarded when it cannot.
    """
    if not raw.get("determinable"):
        return raw

    named = raw.get("root_cause")
    candidates = raw.get("candidates_considered") or []
    rivals = [c for c in candidates if c.get("cause") != named]

    # Weighing nothing is not the same as ruling everything out. Without this a response that
    # simply declines to list an alternative sails through the check that exists to catch it.
    if not rivals:
        raw["determinable"] = False
        raw["root_cause"] = None
        raw["confidence"] = None
        return raw

    unresolved = [
        candidate.get("cause")
        for candidate in rivals
        if not CITATION.match((candidate.get("ruled_out_by") or "").strip())
    ]
    if unresolved:
        raw["determinable"] = False
        raw["root_cause"] = None
        raw["confidence"] = None
    return raw


def run(facts: FactSet) -> HypothesisResponse:
    prompt = json.dumps(facts.model_dump(), indent=2, default=str)
    raw, usage = gemini.generate(HYPOTHESIS, prompt, RESPONSE_SCHEMA)
    result = HypothesisResult.model_validate(
        _must_separate(_apply_g9(_flatten_placeholders(raw)))
    )
    return HypothesisResponse(
        result=result,
        usage=Usage(prompt_version=HYPOTHESIS_VERSION, **usage),
    )
