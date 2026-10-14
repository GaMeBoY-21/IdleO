"""Calls to the API: the key on every one, the API's problems turned into exceptions, and a retry for what's safe to
repeat (the same as the command line's client, cli/src/client.rs)."""

from __future__ import annotations

import time
from collections.abc import Callable, Mapping
from typing import Any, TypeVar

import httpx
from pydantic import BaseModel, ValidationError

from idleo._brand import NAME, PRODUCT
from idleo._generated.errors import PROBLEMS
from idleo._problem import IdleoError, NetworkError, NotSignedIn, ServerError

CONNECT_TIMEOUT = 10.0
REQUEST_TIMEOUT = 60.0
#: Whole-file transfers to and from storage: a 64 MiB part over home internet can take minutes.
TRANSFER_TIMEOUT = 30 * 60.0
RETRIES = 2

M = TypeVar("M", bound=BaseModel)

try:
    from importlib.metadata import version as _version

    VERSION = _version(NAME)
except Exception:
    VERSION = "0"


class Transport:
    """One API address and one key; everything the SDK sends goes through here."""

    def __init__(
        self,
        base: str,
        key: str | None,
        *,
        transport: httpx.BaseTransport | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self.base = base
        self.key = key
        self.sleep = sleep
        headers = {"User-Agent": f"{NAME}-python/{VERSION}"}
        self.api = httpx.Client(
            base_url=base,
            headers=headers,
            timeout=httpx.Timeout(REQUEST_TIMEOUT, connect=CONNECT_TIMEOUT),
            transport=transport,
        )
        # Storage and long-lived streams: no short whole-request timeout. It never carries the key.
        self.transfers = httpx.Client(
            headers=headers,
            timeout=httpx.Timeout(TRANSFER_TIMEOUT, connect=CONNECT_TIMEOUT),
            transport=transport,
            follow_redirects=True,
        )

    def close(self) -> None:
        self.api.close()
        self.transfers.close()

    def require_key(self) -> str:
        if not self.key:
            raise NotSignedIn(
                f"You're not signed in. Run `{NAME} login` on this computer, or set {NAME.upper()}_API_KEY."
            )
        return self.key

    def headers(self, idempotency_key: str | None = None) -> dict[str, str]:
        headers = {"Authorization": f"Bearer {self.require_key()}"}
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        return headers

    def send(self, request: httpx.Request, *, stream: bool = False) -> httpx.Response:
        """Sends it; a problem answer becomes the exception."""
        try:
            response = self.api.send(request, stream=stream)
        except httpx.TransportError as error:
            raise NetworkError(_unreachable(self.base, error)) from error
        if response.is_success:
            return response
        try:
            response.read()
        except httpx.TransportError as error:
            raise NetworkError(_unreachable(self.base, error)) from error
        finally:
            response.close()
        raise problem_from(response)

    def call(
        self,
        method: str,
        path: str,
        *,
        params: Mapping[str, str | int | bool] | None = None,
        json: Any = None,
        idempotency_key: str | None = None,
        signed_in: bool = True,
        retry: bool = False,
    ) -> httpx.Response:
        """One call to the API. `retry` is for reads: a network failure or a 5xx is tried again, twice."""
        attempt = 0
        while True:
            headers = self.headers(idempotency_key) if signed_in else {}
            request = self.api.build_request(method, path, params=params, json=json, headers=headers)
            try:
                return self.send(request)
            except (NetworkError, ServerError):
                if not retry or attempt >= RETRIES:
                    raise
                self.sleep(0.5 * 2**attempt)
                attempt += 1

    def get(self, path: str, model: type[M], params: Mapping[str, str | int | bool] | None = None, **kw: Any) -> M:
        return parse(model, self.call("GET", path, params=params, retry=True, **kw))

    def post(self, path: str, model: type[M], body: BaseModel | None = None, idempotency_key: str | None = None) -> M:
        return parse(model, self.post_raw(path, body, idempotency_key))

    def post_raw(self, path: str, body: BaseModel | None = None, idempotency_key: str | None = None) -> httpx.Response:
        """A POST whose body is a model, sent as the API names its fields; what's unset is left out."""
        payload = body.model_dump(mode="json", by_alias=True, exclude_none=True) if body is not None else None
        return self.call("POST", path, json=payload, idempotency_key=idempotency_key)


def parse(model: type[M], response: httpx.Response) -> M:
    try:
        return model.model_validate_json(response.content)
    except ValidationError as error:
        raise IdleoError(
            f"the answer wasn't what this version of the {PRODUCT} SDK expects ({error.error_count()} problems); "
            "try updating it"
        ) from error


def _unreachable(base: str, error: httpx.TransportError) -> str:
    place = httpx.URL(base).host or "the API"
    if isinstance(error, httpx.TimeoutException):
        why = "it took too long to answer"
    elif isinstance(error, httpx.ConnectError):
        why = "the connection failed"
    else:
        why = "the request failed"
    return f"Couldn't reach {place}: {why}. Check your connection and try again."


def problem_from(response: httpx.Response) -> IdleoError:
    """The API's problem detail (RFC 9457) as the exception its type names."""
    try:
        body = response.json()
    except ValueError:
        body = None
    if not isinstance(body, dict):
        body = {}
    raw = body.get("type")
    slug = raw.removeprefix("/problems/") if isinstance(raw, str) and raw.startswith("/problems/") else None
    retry_header = response.headers.get("Retry-After", "")
    retry_after = int(retry_header) if retry_header.isdigit() else None
    if slug is None and response.status_code >= 500:
        return ServerError(f"The service had a problem ({response.status_code}). Try again in a moment.")
    message = str(body.get("detail") or body.get("title") or "The request was refused.")
    fields = {
        str(entry["field"]): str(entry["detail"])
        for entry in body.get("errors") or []
        if isinstance(entry, dict) and "field" in entry and "detail" in entry
    }
    if fields:
        message += "".join(f"\n  {name}: {why}" for name, why in fields.items())
    if retry_after is not None:
        message += f"\nTry again in {retry_after} s."
    cls = PROBLEMS.get(slug or "", IdleoError)
    error = cls(message, retry_after=retry_after, fields=fields, problem=body)
    if cls is IdleoError and slug:
        error.slug = slug
    return error
