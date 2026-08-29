"""The model call. One place, so the constraints live in one place too.

Temperature is zero and the response is schema-constrained, because section 16 requires that the
same payment in the same state produces the same explanation. Without that the system cannot be
tested, and an explanation that cannot be reproduced is not evidence.
"""

from __future__ import annotations

import json
import os
import time

import httpx

ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
DEFAULT_MODEL = "gemini-3.5-flash-lite"


class ModelUnavailable(RuntimeError):
    """No key, or the provider refused.

    The caller degrades and leaves the debt open. It does not invent an explanation, and it does
    not fall back to a cheaper answer that looks like one.
    """


def model_name() -> str:
    return os.environ.get("GEMINI_MODEL", DEFAULT_MODEL)


def configured() -> bool:
    return bool(os.environ.get("GEMINI_API_KEY", "").strip())


def generate(system: str, user: str, schema: dict) -> tuple[dict, dict]:
    """Returns (parsed json, usage)."""
    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key:
        raise ModelUnavailable("GEMINI_API_KEY is not set")

    body = {
        "systemInstruction": {"parts": [{"text": system}]},
        "contents": [{"role": "user", "parts": [{"text": user}]}],
        "generationConfig": {
            "temperature": 0,
            "responseMimeType": "application/json",
            "responseSchema": schema,
        },
    }

    started = time.monotonic()
    try:
        response = httpx.post(
            ENDPOINT.format(model=model_name()),
            params={"key": key},
            json=body,
            timeout=90.0,
        )
    except httpx.HTTPError as exc:
        raise ModelUnavailable("transport: " + str(exc)) from exc
    latency_ms = int((time.monotonic() - started) * 1000)

    if response.status_code != 200:
        raise ModelUnavailable(str(response.status_code) + ": " + response.text[:400])

    payload = response.json()
    try:
        text = payload["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError) as exc:
        raise ModelUnavailable("no candidate: " + json.dumps(payload)[:400]) from exc

    meta = payload.get("usageMetadata", {})
    usage = {
        "prompt_tokens": meta.get("promptTokenCount", 0),
        "completion_tokens": meta.get("candidatesTokenCount", 0),
        "latency_ms": latency_ms,
        "model": model_name(),
    }
    return json.loads(text), usage
