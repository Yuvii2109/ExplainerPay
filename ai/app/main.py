"""pxe-ai: Job A (synthesis) and Job B (hypothesis).

This process holds no database credentials, by construction. It is a separate container for exactly
one reason: "the model has no database connection" is a real security property, and only a process
boundary proves it. A reviewer can verify it by reading docker-compose.yml.

Four routes. Nothing here reads a payment; everything it reasons about arrives in the request body,
which is also why it can never see ground truth.
"""

from __future__ import annotations

from fastapi import FastAPI, HTTPException

from . import gemini
from .jobs import hypothesis, synthesis
from .prompts import VERSIONS
from .schemas import FactSet, HypothesisResponse, NarrativeRequest, NarrativeResponse

app = FastAPI(title="pxe-ai", version="0.1.0")


@app.get("/health")
def health() -> dict[str, object]:
    return {
        "status": "UP",
        "service": "pxe-ai",
        "model": gemini.model_name(),
        "modelConfigured": gemini.configured(),
    }


@app.get("/prompts")
def prompts() -> dict[str, str]:
    """The frozen versions. An explanation records one of these or it cannot be reproduced."""
    return VERSIONS


@app.post("/jobs/hypothesis", response_model=HypothesisResponse)
def job_b(facts: FactSet) -> HypothesisResponse:
    try:
        return hypothesis.run(facts)
    except gemini.ModelUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except ValueError as exc:
        # Pydantic rejected the generation: a claim with no citation, or a digit in prose.
        # A malformed response is a protocol violation, not a content error, and it stops here.
        raise HTTPException(status_code=502, detail="model response rejected: " + str(exc)) from exc


@app.post("/jobs/synthesis", response_model=NarrativeResponse)
def job_a(request: NarrativeRequest) -> NarrativeResponse:
    try:
        return synthesis.run(request)
    except gemini.ModelUnavailable as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=502, detail="model response rejected: " + str(exc)) from exc
