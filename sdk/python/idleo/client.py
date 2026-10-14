"""The client: who you are, your credit, the services, and your sessions."""

from __future__ import annotations

import builtins
import itertools
import time
import uuid
from collections.abc import Callable, Iterator, Mapping
from datetime import timedelta
from pathlib import Path
from types import TracebackType
from typing import Any

import httpx

from idleo import _run_args as args
from idleo._config import api_url as resolve_api_url
from idleo._config import config_dir as resolve_config_dir
from idleo._generated.models import (
    Availability,
    CreateSessionRequest,
    MeResponse,
    SessionList,
    SessionResponse,
    TemplateList,
    TemplateResponse,
    WalletResponse,
)
from idleo._http import Transport
from idleo._keys import stored_key
from idleo._notebook import Status
from idleo._problem import IdleoError, NetworkError, ServerError, UsageError
from idleo._resume import PendingKeys, ResumeStore
from idleo._upload import Uploader
from idleo.session import Session

#: How many times a booking is tried when the answer doesn't come, always with the same key.
BOOKING_TRIES = 3
PAGE = 100


class Templates:
    """The services you can run, from `GET /v1/templates`: the one list the web form and the command line's flags are
    made from."""

    def __init__(self, transport: Transport) -> None:
        self._transport = transport

    def list(self) -> builtins.list[TemplateResponse]:
        """Every template you can book, with its inputs, parameters and questions."""
        return self._transport.get("/v1/templates", TemplateList).templates

    def get(self, template_id: str) -> TemplateResponse:
        """One template by its id. Raises UsageError, naming the ones there are, for an id there isn't."""
        templates = self.list()
        for template in templates:
            if template.id == template_id:
                return template
        names = ", ".join(template.id for template in templates)
        raise UsageError(f"there's no template {template_id!r}; the templates are: {names}")


class Uploads:
    """Files and folders on their way to storage; the same upload the command line and the web app make."""

    def __init__(self, transport: Transport, resume: ResumeStore) -> None:
        self._transport = transport
        self._resume = resume

    def upload(self, path: str | Path, *, label: str | None = None, progress: bool | None = None) -> uuid.UUID:
        """Uploads a folder (every file, paths kept) or one file, straight to storage, and returns the upload's id,
        for `inputs=`. Called again with the same files after a failure, it carries on from the last part storage
        took."""
        root = Path(path).expanduser()
        name = label or root.name or "files"
        status = Status(progress)

        def show(label: str, sent: int, total: int) -> None:
            status.update(f"{label}: {sent * 100 // total if total else 100}%  {sent:,} of {total:,} bytes")

        uploader = Uploader(self._transport, self._resume, on_progress=show)
        return uuid.UUID(uploader.upload(root, name))


class Sessions:
    """Booking, finding and following sessions."""

    def __init__(self, transport: Transport, config: Path, templates: Templates, uploads: Uploads) -> None:
        self._transport = transport
        self._pending = PendingKeys(config)
        self._templates = templates
        self._uploads = uploads

    def run(
        self,
        template: str,
        *,
        inputs: Mapping[str, args.InputValue] | None = None,
        params: Mapping[str, Any] | None = None,
        presets: Mapping[str, str] | None = None,
        gpu_memory_gb: int | None = None,
        max_spend: int | None = None,
        max_runtime: int | timedelta | None = None,
        progress: bool | None = None,
    ) -> Session:
        """Uploads the inputs and books the session; it doesn't wait for it (use `wait()` and `logs()`).

        `inputs` maps each of the template's input names to a folder or file on this computer (uploaded first, paths
        kept, carried on if a first try was cut off), an https:// link the job fetches itself, or the id of an upload
        you made before. `max_spend` is paise, the most the session may cost (`200_00` is ₹200); the template's usual
        budget if left out. `max_runtime` is seconds or a timedelta. `progress` shows the upload as it goes: by
        default only in a notebook.
        """

        def upload(name: str, path: Path) -> uuid.UUID:
            return self._uploads.upload(path, label=name, progress=progress)

        found = self._templates.get(template)
        request = self._request(found, inputs, params, presets, gpu_memory_gb, max_spend, max_runtime, upload)
        return self._book(request)

    def create(
        self,
        template: str,
        *,
        inputs: Mapping[str, args.InputValue] | None = None,
        params: Mapping[str, Any] | None = None,
        presets: Mapping[str, str] | None = None,
        gpu_memory_gb: int | None = None,
        max_spend: int | None = None,
        max_runtime: int | timedelta | None = None,
    ) -> Session:
        """Books a session without uploading anything: each input is an https:// link or the id of an upload made
        with `client.uploads.upload()`. It doesn't follow the session either."""

        def refuse(name: str, path: Path) -> uuid.UUID:
            raise UsageError(f"{name} is a path on this computer; use sessions.run() to upload it, or uploads.upload()")

        found = self._templates.get(template)
        request = self._request(found, inputs, params, presets, gpu_memory_gb, max_spend, max_runtime, refuse)
        return self._book(request)

    def get(self, session_id: str | uuid.UUID) -> Session:
        """One of your sessions by its id."""
        return Session(self._transport, self._transport.get(f"/v1/sessions/{session_id}", SessionResponse))

    def iter(self, *, active: bool | None = None) -> Iterator[Session]:
        """Your sessions, newest first, a page at a time as you read them. `active` keeps only those still going."""
        cursor: str | None = None
        while True:
            params: dict[str, str | int | bool] = {"limit": PAGE}
            if active is not None:
                params["active"] = active
            if cursor:
                params["cursor"] = cursor
            page = self._transport.get("/v1/sessions", SessionList, params)
            for data in page.sessions:
                yield Session(self._transport, data)
            cursor = page.next_cursor
            if not cursor or not page.sessions:
                return

    def list(self, *, active: bool | None = None, limit: int = 20) -> builtins.list[Session]:
        """Your newest sessions, up to `limit`."""
        return builtins.list(itertools.islice(self.iter(active=active), limit))

    def _request(
        self,
        template: TemplateResponse,
        inputs: Mapping[str, args.InputValue] | None,
        params: Mapping[str, Any] | None,
        presets: Mapping[str, str] | None,
        gpu_memory_gb: int | None,
        max_spend: int | None,
        max_runtime: int | timedelta | None,
        upload: Callable[[str, Path], uuid.UUID],
    ) -> CreateSessionRequest:
        """Everything is checked against the template first; only then is anything uploaded, in the order the
        template names its inputs."""
        given = args.map_inputs(inputs, template)
        request = CreateSessionRequest(
            template_id=template.id,
            params=args.map_params(params, template),
            presets=args.map_presets(presets, template),
            max_spend_paise=args.spend(max_spend, template),
            max_runtime_seconds=args.runtime_seconds(max_runtime),
            vram_needed_mb=args.vram_mb(gpu_memory_gb, template),
        )
        booked: dict[str, Any] = {}
        for name in (input_.name for input_ in template.inputs):
            value = given.get(name)
            if isinstance(value, args.Link):
                booked[name] = {"url": value.url}
            elif isinstance(value, args.Existing):
                booked[name] = {"uploadId": str(value.upload_id)}
            elif isinstance(value, args.Upload):
                booked[name] = {"uploadId": str(upload(name, value.path))}
        request.inputs = booked
        return request

    def _book(self, request: CreateSessionRequest) -> Session:
        """`POST /v1/sessions`, keeping its Idempotency-Key until the API answers, and trying again with the same key
        when no answer comes."""
        text = request.model_dump_json(by_alias=True, exclude_none=True)
        key = self._pending.key_for(text)
        tries = 0
        while True:
            tries += 1
            try:
                data = self._transport.post("/v1/sessions", SessionResponse, request, key)
            except (NetworkError, ServerError) as error:
                if tries >= BOOKING_TRIES:
                    raise type(error)(
                        f"{error}\nThe booking may or may not have gone through. Run the same call again: it won't "
                        "book twice."
                    ) from error
                self._transport.sleep(tries * 2)
            except IdleoError:
                # The API's answer is final: the key has done its job.
                self._pending.answered(text)
                raise
            else:
                self._pending.answered(text)
                return Session(self._transport, data)


class Client:
    """Your account's door to the API.

    The API key is `api_key`, else the `IDLEO_API_KEY` environment variable, else the one `idleo login` stored on this
    computer (the keychain, or the credentials file in the config folder). The API's address is `api_url`, else
    `IDLEO_API_URL`, else the local stack. Without a key every call raises NotSignedIn.
    """

    def __init__(
        self,
        api_key: str | None = None,
        api_url: str | None = None,
        *,
        config_dir: str | Path | None = None,
        transport: httpx.BaseTransport | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        base = resolve_api_url(api_url)
        directory = resolve_config_dir(config_dir)
        key = (api_key or "").strip() or stored_key(base, directory)
        self._transport = Transport(base, key, transport=transport, sleep=sleep)
        self.templates = Templates(self._transport)
        self.uploads = Uploads(self._transport, ResumeStore(directory))
        self.sessions = Sessions(self._transport, directory, self.templates, self.uploads)

    def me(self) -> MeResponse:
        """Who you're signed in as."""
        return self._transport.get("/v1/me", MeResponse)

    def credit(self) -> WalletResponse:
        """Your balance, what's held for running sessions, and what's available; all in paise."""
        return self._transport.get("/v1/wallet", WalletResponse)

    def availability(self) -> Availability:
        """Which GPU memory classes have a host online now, and their price."""
        return self._transport.get("/v1/availability", Availability)

    def close(self) -> None:
        """Closes the connections; a `with Client() as client:` block does it for you."""
        self._transport.close()

    def __enter__(self) -> Client:
        return self

    def __exit__(
        self, exc_type: type[BaseException] | None, exc: BaseException | None, tb: TracebackType | None
    ) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"Client(api_url={self._transport.base!r}, signed_in={bool(self._transport.key)})"
