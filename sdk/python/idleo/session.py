"""A session: what it is now, its live view, its logs, and what to do with it."""

from __future__ import annotations

import contextlib
import queue
import threading
import time
from collections.abc import Iterator
from datetime import datetime
from typing import Any

import httpx

from idleo._events import Event, SessionEvent, SseParser
from idleo._generated.errors import NoGpuFree
from idleo._generated.models import SessionGpu, SessionLogPage, SessionResponse, StopRequest
from idleo._http import Transport
from idleo._notebook import Status
from idleo._problem import IdleoError, NetworkError, ServerError, SessionNotCompleted, WaitTimeout
from idleo.money import rupees
from idleo.outputs import Outputs

#: How often logs are read even when the stream says nothing, and how long to wait before reconnecting.
TICK = 2.0
#: Failures in a row (a stream that won't open, or keeps dropping) before giving up.
GIVE_UP_AFTER = 6
#: How long after a session settles its last log chunks may still be coming.
LOGS_GRACE = 30
LOG_PAGE = 100


def state_words(state: str) -> str:
    """The state in a person's words, lower case."""
    return state.lower().replace("_", " ")


class Session:
    """A booked session. `data` is the API's whole answer as of the last refresh; `refresh()` asks again."""

    def __init__(self, transport: Transport, data: SessionResponse) -> None:
        self._transport = transport
        self.data = data

    def __repr__(self) -> str:
        return f"Session(id={self.id!r}, state={self.state!r}, charged={rupees(self.charged_paise)})"

    # What most scripts ask about, named once; everything else is `data`.

    @property
    def id(self) -> str:
        return str(self.data.id)

    @property
    def state(self) -> str:
        return self.data.state

    @property
    def settled(self) -> bool:
        """True once nothing will change any more: the charge is final."""
        return self.data.settled

    @property
    def charged_paise(self) -> int:
        return self.data.charged_paise

    @property
    def cost_paise(self) -> int:
        """What it has cost so far: what's been charged, and what has run since but isn't settled yet."""
        return self.data.charged_paise + self.data.accrued_paise

    @property
    def max_spend_paise(self) -> int:
        return self.data.max_spend_paise

    @property
    def error_code(self) -> str | None:
        return self.data.error_code

    @property
    def gpu(self) -> SessionGpu | None:
        return self.data.gpu

    @property
    def started_at(self) -> datetime | None:
        return self.data.started_at

    @property
    def ended_at(self) -> datetime | None:
        return self.data.ended_at

    @property
    def outputs(self) -> Outputs:
        return Outputs(self, self._transport)

    # Reading

    def refresh(self) -> Session:
        """Asks the API for the session as it is now."""
        self.data = self._transport.get(f"/v1/sessions/{self.id}", SessionResponse)
        return self

    def events(self) -> Iterator[Event]:
        """The live view: the whole session each time it changes, and a note when a new log chunk exists. A dropped
        connection is picked up again; it ends once the session is settled."""
        for event in self._follow(None):
            if event is not None:
                yield event

    def wait(self, timeout: float | None = None, *, progress: bool | None = None, check: bool = True) -> Session:
        """Waits until the session is settled and returns it, with its final figures.

        `progress` shows its state and cost as it goes: by default only in a notebook. With `check` (the default) a
        session that didn't complete raises: NoGpuFree if no GPU became free for it, else SessionNotCompleted, which
        carries the session. Raises WaitTimeout if `timeout` seconds pass first; the session keeps going.
        """
        status = Status(progress)
        deadline = None if timeout is None else time.monotonic() + timeout
        status.update(self._words())
        if not self.settled:
            for _event in self._follow(None if timeout is None else min(1.0, max(timeout, 0.01))):
                status.update(self._words())
                if self.settled:
                    break
                if deadline is not None and time.monotonic() >= deadline:
                    raise WaitTimeout(f"the session is still {state_words(self.state)} after {timeout} s")
        status.update(self._words())
        if check:
            self.raise_unless_completed()
        return self

    def raise_unless_completed(self) -> None:
        """Raises if the session settled without completing (the command line's non-zero exits)."""
        if self.state == "COMPLETED":
            return
        if self.state == "NO_HOST_FREE":
            raise NoGpuFree("No GPU became free for it. Nothing was charged.")
        raise SessionNotCompleted(self.why_ended() or f"It ended: {state_words(self.state)}.", self)

    def why_ended(self) -> str | None:
        """Why it ended the way it did, in a sentence, when it didn't complete."""
        last = self.data.events[-1].reason if self.data.events else ""
        code = self.data.error_code or last or None
        if self.state == "COMPLETED":
            return None
        if self.state == "NO_HOST_FREE":
            return "No GPU became free for it. Nothing was charged."
        if self.state == "CANCELLED":
            return "It was cancelled before it started. Nothing was charged."
        if code:
            return f"It ended with: {code.replace('_', ' ')}."
        return f"It ended: {state_words(self.state)}."

    def _words(self) -> str:
        gpu = f" on {self.data.gpu.model}" if self.data.gpu else ""
        return f"{state_words(self.state)}{gpu}  ({rupees(self.cost_paise)} so far)"

    # Logs

    def logs(self, follow: bool = False, *, progress: bool | None = None) -> Iterator[str]:
        """The session's log, a line at a time, from the start. With `follow`, lines keep coming as they're written,
        until the session is settled and its log complete (a notebook also shows its state, `progress`)."""
        reader = _LogReader(self._transport, self.id)
        if not (follow and not self.settled):
            if follow:
                yield from self._finish_logs(reader)
                return
            yield from reader.read()
            yield from reader.flush()
            return
        status = Status(progress)
        status.update(self._words())
        for _event in self._follow(TICK):
            yield from reader.read()
            status.update(self._words())
            if self.settled:
                break
        yield from self._finish_logs(reader)

    def _finish_logs(self, reader: _LogReader) -> Iterator[str]:
        """Once settled, reads until the log says it's complete, giving the last chunks a moment to arrive."""
        for _ in range(LOGS_GRACE):
            yield from reader.read()
            if reader.complete:
                yield from reader.flush()
                return
            self._transport.sleep(1)
        raise IdleoError("The session has ended, but its last log lines didn't arrive. Try logs() again in a moment.")

    # Acting

    def stop(self, checkpoint_first: bool = True) -> Session:
        """Stops a running session, saving a last checkpoint first unless told not to; you're charged only for the
        minutes it ran."""
        body = StopRequest(checkpoint_first=checkpoint_first)
        self.data = self._transport.post(f"/v1/sessions/{self.id}/stop", SessionResponse, body)
        return self

    def cancel(self) -> Session:
        """Cancels a session that hasn't started; nothing is charged."""
        self.data = self._transport.post(f"/v1/sessions/{self.id}/cancel", SessionResponse)
        return self

    # The stream

    def _follow(self, tick: float | None) -> Iterator[Event | None]:
        """The live view with a heartbeat: the events, and None every `tick` seconds of quiet (never when `tick` is
        None). The stream is read on a thread of its own, so a quiet connection never holds the caller."""
        found: queue.Queue[Any] = queue.Queue()
        stop = threading.Event()
        live: dict[str, httpx.Response] = {}
        done = object()

        def pump() -> None:
            try:
                for event in self._stream(stop, live):
                    found.put(event)
                found.put(done)
            except BaseException as error:
                found.put(error)

        threading.Thread(target=pump, daemon=True, name=f"idleo-events-{self.id}").start()
        try:
            while True:
                try:
                    item = found.get(timeout=tick)
                except queue.Empty:
                    yield None
                    continue
                if item is done:
                    return
                if isinstance(item, BaseException):
                    raise item
                yield item
        finally:
            stop.set()
            response = live.get("response")
            if response is not None:
                # Closing a stream another thread is reading may fail; it is being closed either way.
                with contextlib.suppress(Exception):
                    response.close()

    def _pause(self, stop: threading.Event) -> None:
        """A moment before asking again; nothing if the caller has already gone."""
        if not stop.is_set():
            self._transport.sleep(TICK)

    def _stream(self, stop: threading.Event, live: dict[str, httpx.Response]) -> Iterator[Event]:
        failures = 0
        while not stop.is_set():
            try:
                request = self._transport.api.build_request(
                    "GET",
                    f"/v1/sessions/{self.id}/events",
                    headers={**self._transport.headers(), "Accept": "text/event-stream"},
                    # Long-lived: it must not carry the short request timeout.
                    extensions={"timeout": {"connect": 10, "read": 35 * 60, "write": 60, "pool": 60}},
                )
                response = self._transport.send(request, stream=True)
            except (NetworkError, ServerError):
                failures += 1
                if failures >= GIVE_UP_AFTER:
                    raise
                self._pause(stop)
                continue
            except IdleoError as error:
                if error.slug != "too-many-streams":
                    raise
                failures += 1
                if failures >= GIVE_UP_AFTER:
                    raise
                self._pause(stop)
                continue
            failures = 0
            live["response"] = response
            parser = SseParser()
            try:
                for text in response.iter_text():
                    for event in parser.push(text):
                        if isinstance(event, SessionEvent):
                            self.data = event.session
                        yield event
                        if isinstance(event, SessionEvent) and event.session.settled:
                            return
                    if stop.is_set():
                        return
            except httpx.TransportError:
                pass  # The connection dropped: ask where the session is, and open the stream again.
            finally:
                response.close()
            # The stream ended without the session settling: ask once, and carry on from there.
            try:
                self.refresh()
            except NetworkError:
                failures += 1
                if failures >= GIVE_UP_AFTER:
                    raise
            else:
                yield SessionEvent(self.data)
                if self.settled:
                    return
            self._pause(stop)


class _LogReader:
    """A session's logs: read in pages from `GET /v1/sessions/{id}/logs`, each page after the cursor the last one gave,
    so a log is never read twice and a reader that starts late sees the whole of it. Reads with a cursor don't count
    against the request limit; the first read does (ADR 0025)."""

    def __init__(self, transport: Transport, session_id: str) -> None:
        self._transport = transport
        self._path = f"/v1/sessions/{session_id}/logs"
        self._cursor: str | None = None
        self.complete = False
        self._carry = ""

    def read(self) -> list[str]:
        """Every whole line there is now. A line cut by a chunk's end waits for the rest of it."""
        lines: list[str] = []
        while not self.complete:
            params: dict[str, str | int | bool] = {"limit": LOG_PAGE}
            if self._cursor:
                params["cursor"] = self._cursor
            page = self._transport.get(self._path, SessionLogPage, params)
            for chunk in page.chunks:
                *whole, self._carry = (self._carry + chunk.text).split("\n")
                lines.extend(line.removesuffix("\r") for line in whole)
            self.complete = page.complete
            if page.next_cursor:
                self._cursor = page.next_cursor
            if not page.chunks:
                break
        return lines

    def flush(self) -> list[str]:
        """The last line, if the log didn't end with a newline."""
        rest, self._carry = self._carry, ""
        return [rest.removesuffix("\r")] if rest else []
