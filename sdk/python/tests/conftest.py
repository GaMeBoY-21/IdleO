"""A fake API and a fake storage behind one httpx transport: enough of the contract to drive the SDK end to end."""

from __future__ import annotations

import json
import re
import uuid
from collections.abc import Callable
from typing import Any

import httpx
import pytest

from idleo import Client

API = "http://api.test"
STORAGE = "http://storage.test"


def template(**over: Any) -> dict[str, Any]:
    """The simulated fine-tune as GET /v1/templates describes it (templates/development.yaml)."""
    base: dict[str, Any] = {
        "id": "fine-tune-simulated",
        "name": "Fine-tune (simulated)",
        "service": "fine-tune",
        "description": "d",
        "image": "i",
        "budgetChips": [5000, 10000],
        "defaultBudgetPaise": 10000,
        "defaultMaxRuntimeSeconds": 600,
        "maxRuntimeCapSeconds": 900,
        "minVramMb": 6144,
        "inputs": [
            {"name": "model", "label": "Your model", "kind": "folder", "link": True, "upload": True, "required": True},
            {"name": "data", "label": "Data", "kind": "file", "link": True, "upload": True, "required": True},
        ],
        "params": [
            {"name": "epochs", "label": "E", "type": "integer", "group": "advanced", "choices": [], "required": False},
            {
                "name": "learning_rate",
                "label": "L",
                "type": "number",
                "group": "advanced",
                "choices": [],
                "required": False,
            },
            {
                "name": "lora_rank",
                "label": "R",
                "type": "choice",
                "group": "advanced",
                "choices": ["8", "16"],
                "required": False,
            },
            {"name": "verbose", "label": "V", "type": "boolean", "group": "advanced", "choices": [], "required": False},
        ],
        "questions": [
            {
                "kind": "preset",
                "name": "thoroughness",
                "label": "How thorough?",
                "choicesGb": [],
                "defaultOption": "standard",
                "options": [
                    {"id": "quick", "label": "Quick", "sets": {"epochs": 1}},
                    {"id": "standard", "label": "Standard", "sets": {"epochs": 3}},
                ],
            }
        ],
    }
    return {**base, **over}


def session(state: str = "REQUESTED", settled: bool = False, **over: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "id": "01a10e8e-9c65-7789-a526-473491c82218",
        "templateId": "fine-tune-simulated",
        "state": state,
        "settled": settled,
        "attempt": 1,
        "accruedPaise": 0,
        "chargedPaise": 0,
        "reservedPaise": 60,
        "checkpoints": [],
        "events": [],
        "inputs": {},
        "params": {},
        "presets": {},
        "lowCredit": False,
        "maxPricePaisePerMin": 5,
        "maxRuntimeSeconds": 300,
        "maxSpendPaise": 1000,
        "vramNeededMb": 6144,
        "createdAt": "2026-10-14T10:00:00Z",
    }
    return {**base, **over}


def problem(slug: str, status: int, detail: str = "no", **extra: Any) -> httpx.Response:
    body = {"type": f"/problems/{slug}", "title": slug, "status": status, "detail": detail, **extra}
    return httpx.Response(status, json=body, headers={"content-type": "application/problem+json"})


class Fake:
    """Routes by (method, path regexp); every request is kept, in order, for the tests to read."""

    def __init__(self) -> None:
        self.requests: list[httpx.Request] = []
        self.routes: list[tuple[str, re.Pattern[str], Callable[[httpx.Request], httpx.Response]]] = []
        self.sleeps: list[float] = []
        self.route(
            "GET", "/v1/templates", lambda r: httpx.Response(200, json={"services": [], "templates": [template()]})
        )

    def route(self, method: str, pattern: str, handler: Callable[[httpx.Request], httpx.Response]) -> None:
        # Later routes win, so a test can replace one.
        self.routes.insert(0, (method, re.compile(pattern + "$"), handler))

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        for method, pattern, handler in self.routes:
            if request.method == method and pattern.match(
                request.url.raw_path.decode().split("?")[0] if request.url.host == "api.test" else str(request.url)
            ):
                return handler(request)
        return problem("not-found", 404, f"no route for {request.method} {request.url}")

    def calls(self, method: str, pattern: str) -> list[httpx.Request]:
        regexp = re.compile(pattern + "$")
        return [r for r in self.requests if r.method == method and regexp.match(r.url.raw_path.decode().split("?")[0])]

    def client(self, tmp_path: Any, key: str | None = "idleo_test") -> Client:
        return Client(
            api_key=key,
            api_url=API,
            config_dir=tmp_path / "config",
            transport=httpx.MockTransport(self.handle),
            sleep=self.sleeps.append,
        )


@pytest.fixture
def fake() -> Fake:
    return Fake()


def body(request: httpx.Request) -> Any:
    return json.loads(request.content)


def new_id() -> str:
    return str(uuid.uuid4())
