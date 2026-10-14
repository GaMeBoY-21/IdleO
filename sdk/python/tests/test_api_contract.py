"""Every call the SDK makes, listed, and checked against the API's contract (the command line's tests/api_contract.rs
does the same): an operation that goes away or changes its method fails here, not in somebody's script. Where the SDK
sends or reads something by name, the contract must have it too."""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

SPEC = yaml.safe_load((Path(__file__).parents[3] / "api" / "openapi.yaml").read_text())

# (method, path, what the SDK uses it for)
CALLS = [
    ("get", "/v1/me", "client.me()"),
    ("get", "/v1/wallet", "client.credit()"),
    ("get", "/v1/availability", "client.availability()"),
    ("get", "/v1/templates", "client.templates"),
    ("post", "/v1/uploads", "client.uploads.upload(): open, with an Idempotency-Key"),
    ("post", "/v1/uploads/{id}/part-urls", "an upload's part links"),
    ("post", "/v1/uploads/{id}/complete", "an upload's files, completed"),
    ("post", "/v1/sessions", "client.sessions.run() and create(), with an Idempotency-Key"),
    ("get", "/v1/sessions", "client.sessions.list() and iter()"),
    ("get", "/v1/sessions/{id}", "client.sessions.get() and session.refresh()"),
    ("get", "/v1/sessions/{id}/events", "session.events(), wait() and logs(follow=True)"),
    ("get", "/v1/sessions/{id}/logs", "session.logs()"),
    ("post", "/v1/sessions/{id}/stop", "session.stop()"),
    ("post", "/v1/sessions/{id}/cancel", "session.cancel()"),
    ("get", "/v1/sessions/{id}/outputs", "session.outputs"),
]


@pytest.mark.parametrize(("method", "path", "use"), CALLS, ids=[f"{m} {p}" for m, p, _ in CALLS])
def test_the_operation_is_in_the_contract(method, path, use):
    assert method in SPEC["paths"].get(path, {}), f"{method.upper()} {path} ({use}) isn't in api/openapi.yaml"


def operation(method: str, path: str) -> dict:
    return SPEC["paths"][path][method]


def test_the_creating_calls_take_an_idempotency_key():
    for path in ("/v1/uploads", "/v1/sessions"):
        names = [p.get("name") for p in operation("post", path).get("parameters", [])]
        assert "Idempotency-Key" in names, path


def test_the_sessions_list_pages_and_filters_as_the_sdk_asks():
    names = {p["name"] for p in operation("get", "/v1/sessions")["parameters"]}
    assert {"cursor", "limit", "active"} <= names


def test_the_logs_read_pages_with_a_cursor():
    names = {p["name"] for p in operation("get", "/v1/sessions/{id}/logs")["parameters"]}
    assert {"cursor", "limit"} <= names


def test_a_booking_has_the_fields_the_sdk_sends():
    fields = set(SPEC["components"]["schemas"]["CreateSessionRequest"]["properties"])
    assert {"templateId", "inputs", "params", "presets", "maxSpendPaise", "maxRuntimeSeconds", "vramNeededMb"} <= fields


def test_the_event_stream_has_both_events_the_sdk_reads():
    schemas = operation("get", "/v1/sessions/{id}/events")["responses"]["200"]["content"]
    text = yaml.safe_dump(schemas)
    assert "SessionResponse" in text
    assert "SessionLogsEvent" in text


def test_every_problem_type_the_sdk_maps_to_an_exit_code_is_in_the_catalog():
    from idleo._problem import _EXIT_BY_SLUG

    catalog = SPEC["components"]["schemas"]["ProblemDetail"]["properties"]["type"]["enum"]
    slugs = {entry.removeprefix("/problems/") for entry in catalog}
    assert set(_EXIT_BY_SLUG) <= slugs
