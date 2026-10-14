"""A session's live view: Server-Sent Events from `GET /v1/sessions/{id}/events` (ADR 0019), the one stream the web app,
the command line and the SDK follow. `event: session` carries the whole session; `event: logs` says a new log chunk
exists. A comment line keeps a quiet stream open, and the stream ends once the session is settled."""

from __future__ import annotations

import json
from dataclasses import dataclass

from pydantic import ValidationError

from idleo._generated.models import SessionLogsEvent, SessionResponse


@dataclass(frozen=True)
class SessionEvent:
    """The whole session as it is now."""

    session: SessionResponse


@dataclass(frozen=True)
class LogsEvent:
    """A new log chunk exists; the logs themselves are read with `Session.logs()`."""

    attempt: int
    latest_seq: int


Event = SessionEvent | LogsEvent


class SseParser:
    """Splits a stream of text into events, a piece at a time, however the network cut it."""

    def __init__(self) -> None:
        self._buffer = ""
        self._name: str | None = None
        self._data: list[str] = []

    def push(self, text: str) -> list[Event]:
        """Takes the next text and returns the events it completed."""
        self._buffer += text
        events: list[Event] = []
        while "\n" in self._buffer:
            line, self._buffer = self._buffer.split("\n", 1)
            line = line.rstrip("\r")
            if not line:
                found = self._dispatch()
                if found:
                    events.append(found)
            elif line.startswith("event:"):
                self._name = line[len("event:") :].strip()
            elif line.startswith("data:"):
                data = line[len("data:") :]
                self._data.append(data.removeprefix(" "))
            # A line starting with a colon is a comment (the keep-alive), and anything else is ignored.
        return events

    def _dispatch(self) -> Event | None:
        name, data = self._name, "\n".join(self._data)
        self._name, self._data = None, []
        try:
            if name == "session":
                return SessionEvent(SessionResponse.model_validate_json(data))
            if name == "logs":
                logs = SessionLogsEvent.model_validate(json.loads(data))
                return LogsEvent(logs.attempt, logs.latest_seq)
        except (ValidationError, ValueError):
            return None
        return None
