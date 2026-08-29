"""The AI contract, as types.

The model does not emit prose. It emits claims, which are validated, and only then rendered.
Pydantic is the first gate: a response that does not fit these shapes never reaches pxe-api at all,
so a malformed generation is a 502 from this service rather than a bad explanation in the database.

Two rules are enforced here rather than described:

  * a claim carries at least one citation, because an uncited claim is removed, not softened;
  * `text` may not contain a digit. The model is structurally prevented from typing a number, so it
    cannot mistype one. Numbers arrive later, by placeholder substitution from typed fields.
"""

from __future__ import annotations

import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel

DIGIT = re.compile(r"\d")
PLACEHOLDER = re.compile(r"\{[A-Za-z0-9_]+\}")


def has_literal_number(text: str) -> bool:
    """A digit outside a placeholder.

    ``{amount_1}`` is a slot, not a number. Checking the raw string would reject the exact
    convention the prompt asks for, which is a rule punishing the behaviour it wants.
    """
    return bool(DIGIT.search(PLACEHOLDER.sub("", text)))


class Wire(BaseModel):
    """Anything that crosses the boundary to pxe-api.

    The wire is camelCase because the caller is a JVM; the Python stays snake_case because it is
    Python. Neither side has to hold the other's naming convention in its head.
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class Claim(Wire):
    id: str
    kind: Literal["FACT", "HYPOTHESIS"]
    text: str
    citations: list[str] = Field(min_length=1)
    placeholders: dict[str, str] = Field(default_factory=dict)

    @field_validator("text")
    @classmethod
    def no_literal_numbers(cls, text: str) -> str:
        if has_literal_number(text):
            raise ValueError(
                "a claim may not contain a literal digit; cite a field and use a placeholder"
            )
        return text


class Candidate(Wire):
    """A cause that was considered and could not be distinguished from the others."""

    cause: str
    evidence: str


class HypothesisResult(Wire):
    """Job B. A candidate cause, marked, cited, or an honest refusal to name one."""

    determinable: bool
    root_cause: str | None = None
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    claims: list[Claim] = Field(default_factory=list)
    candidates_considered: list[Candidate] = Field(default_factory=list)

    @field_validator("root_cause")
    @classmethod
    def cause_is_a_symbol(cls, cause: str | None) -> str | None:
        if cause is None:
            return None
        if not re.fullmatch(r"[A-Z][A-Z0-9_]{2,63}", cause):
            raise ValueError("root_cause must be an UPPER_SNAKE_CASE symbol, not a sentence")
        return cause


class NarrativeResult(Wire):
    """Job A. The same fact set, said three ways. Still no digits, still with placeholders."""

    merchant: str
    support: str
    engineer: str
    placeholders: dict[str, str] = Field(default_factory=dict)

    @field_validator("merchant", "support", "engineer")
    @classmethod
    def no_literal_numbers(cls, text: str) -> str:
        if has_literal_number(text):
            raise ValueError(
                "a rendering may not contain a literal digit; use a placeholder instead"
            )
        return text


class Hop(Wire):
    seq: int
    stage: str
    actor: str
    status: str
    code: str | None = None
    latency_ms: int | None = None
    occurred: bool = True
    amount_minor: int | None = None
    batch: str | None = None
    note: str | None = None
    attrs: dict = Field(default_factory=dict)


class Reference(Wire):
    hop_seq: int
    kind: str
    superseded: bool = False


class FactSet(Wire):
    """Everything the model is allowed to reason about. It never sees ground truth."""

    payment_id: str
    fact_set_hash: str
    instrument: str
    rail: str
    tag: str | None = None
    response_code: str | None = None
    hops: list[Hop]
    references: list[Reference] = Field(default_factory=list)
    deviations: list[str] = Field(default_factory=list)
    known_rules: list[str] = Field(default_factory=list)


class NarrativeRequest(Wire):
    facts: FactSet
    root_cause: str | None = None
    claims: list[Claim] = Field(default_factory=list)
    determinable: bool = True


class Usage(Wire):
    prompt_tokens: int = 0
    completion_tokens: int = 0
    latency_ms: int = 0
    model: str = ""
    prompt_version: str = ""


class HypothesisResponse(Wire):
    result: HypothesisResult
    usage: Usage


class NarrativeResponse(Wire):
    result: NarrativeResult
    usage: Usage
