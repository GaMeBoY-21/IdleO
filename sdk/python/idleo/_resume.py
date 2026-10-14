"""What the SDK remembers between runs, in the command line's config folder and in its formats (cli/src/resume.rs and
cli/src/pending.rs): the parts storage took of an unfinished upload, so running the same call again carries on from
the last one; and the Idempotency-Key of a booking, kept until the API answers, so a dropped connection never books
twice. Remembering is a convenience: every failure to read or write is swallowed, and the next run starts over."""

from __future__ import annotations

import contextlib
import hashlib
import json
import os
import uuid
from dataclasses import dataclass, field
from pathlib import Path


def new_key() -> str:
    """An Idempotency-Key: a UUID, as the command line's are."""
    return str(uuid.uuid4())


@dataclass
class Unfinished:
    upload_id: str | None = None
    #: The Idempotency-Key the upload was opened with: opening it again with the same key returns it as it stands.
    key: str = ""
    #: Each file's parts storage took, by path: part number to ETag.
    parts: dict[str, dict[int, str]] = field(default_factory=dict)


class ResumeStore:
    def __init__(self, config_dir: Path) -> None:
        self.dir = config_dir / "uploads"

    def _path(self, fingerprint: str) -> Path:
        return self.dir / f"{fingerprint}.json"

    def read(self, fingerprint: str) -> Unfinished | None:
        try:
            raw = json.loads(self._path(fingerprint).read_text())
            return Unfinished(
                upload_id=raw.get("upload_id"),
                key=raw["key"],
                parts={path: {int(n): etag for n, etag in numbered.items()} for path, numbered in raw["parts"].items()},
            )
        except (OSError, ValueError, KeyError, AttributeError, TypeError):
            return None

    def write(self, fingerprint: str, unfinished: Unfinished) -> None:
        text = json.dumps({"upload_id": unfinished.upload_id, "key": unfinished.key, "parts": unfinished.parts})
        try:
            self.dir.mkdir(parents=True, exist_ok=True)
            # Written to a side file and moved into place, so a crash never leaves half a record.
            side = self._path(f"{fingerprint}.tmp.{os.getpid()}")
            side.write_text(text)
            side.replace(self._path(fingerprint))
        except OSError:
            pass

    def forget(self, fingerprint: str) -> None:
        with contextlib.suppress(OSError):
            self._path(fingerprint).unlink()


class PendingKeys:
    def __init__(self, config_dir: Path) -> None:
        self.dir = config_dir / "pending"

    def _file(self, request: str) -> Path:
        return self.dir / f"{hashlib.sha256(request.encode()).hexdigest()}.key"

    def key_for(self, request: str) -> str:
        """The key for this request: the one a first try left behind, else a new one, kept from now on."""
        file = self._file(request)
        try:
            key = file.read_text().strip()
        except OSError:
            key = ""
        if key:
            return key
        key = new_key()
        try:
            self.dir.mkdir(parents=True, exist_ok=True)
            file.write_text(key)
        except OSError:
            pass
        return key

    def answered(self, request: str) -> None:
        """The API has answered, so the key has done its job."""
        with contextlib.suppress(OSError):
            self._file(request).unlink()
