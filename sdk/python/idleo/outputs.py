"""A completed session's files, and downloading them; a file that was part-way carries on from where it stopped,
through a range request, so a dropped connection never means starting a model over (cli/src/commands/outputs.rs)."""

from __future__ import annotations

import builtins
from collections.abc import Callable
from pathlib import Path, PurePosixPath
from typing import TYPE_CHECKING

import httpx

from idleo._generated.models import Output
from idleo._generated.models import Outputs as OutputsResponse
from idleo._http import Transport
from idleo._problem import IdleoError, NetworkError

if TYPE_CHECKING:
    from idleo.session import Session

RETRIES = 3
#: A file being downloaded is `NAME.idleo-part` until it's whole.
PART_SUFFIX = ".idleo-part"


def destination(directory: Path, relative: str) -> Path:
    """Where an output goes under `directory`: its path, which must stay inside it."""
    parts = PurePosixPath(relative).parts
    safe = bool(relative) and "\\" not in relative and all(part not in ("", ".", "..", "/") for part in parts)
    if not safe or PurePosixPath(relative).is_absolute():
        raise IdleoError(f"The output {relative!r} has a path that would leave the download folder.")
    return directory.joinpath(*parts)


class _LinkExpired(Exception):
    pass


class Outputs:
    """`session.outputs`: what a completed session made."""

    def __init__(self, session: Session, transport: Transport) -> None:
        self._session = session
        self._transport = transport

    def list(self) -> builtins.list[Output]:
        """The files, each with its size and a short-lived download link. Raises OutputsNotReady until it's complete."""
        return self._fetch().files

    def _fetch(self) -> OutputsResponse:
        return self._transport.get(f"/v1/sessions/{self._session.id}/outputs", OutputsResponse)

    def download(
        self, directory: str | Path, *, on_progress: Callable[[str, int, int], None] | None = None
    ) -> builtins.list[Path]:
        """Downloads every file into `directory`, keeping its path; a file already whole is left, a part-way one is
        carried on. Returns where each went."""
        target = Path(directory)
        listing = self._fetch().files
        done: builtins.list[Path] = []
        refreshed = False
        for file in listing:
            while True:
                try:
                    done.append(self._download_one(file, target, on_progress))
                    break
                except _LinkExpired:
                    if refreshed:
                        raise IdleoError("storage refused the download link; ask for the outputs again") from None
                    # The links are short-lived: ask once for fresh ones, and carry on with this file.
                    refreshed = True
                    fresh = {f.path: f for f in self._fetch().files}
                    file = fresh.get(file.path, file)
        return done

    def _download_one(self, file: Output, directory: Path, on_progress: Callable[[str, int, int], None] | None) -> Path:
        target = destination(directory, file.path)
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and target.stat().st_size == file.size_bytes:
            return target
        part = target.with_name(target.name + PART_SUFFIX)
        failures = 0
        while True:
            try:
                self._fetch_part(file, part, on_progress)
                break
            except NetworkError:
                failures += 1
                if failures > RETRIES:
                    raise
                self._transport.sleep(2 * failures)
        part.replace(target)
        return target

    def _fetch_part(self, file: Output, part: Path, on_progress: Callable[[str, int, int], None] | None) -> None:
        """Gets the rest of a file into its part file: from where the part file ends when storage can give a range."""
        have = part.stat().st_size if part.exists() else 0
        if have > file.size_bytes:
            have = 0
        if have == file.size_bytes and file.size_bytes > 0:
            return
        headers = {"Range": f"bytes={have}-"} if have else {}
        try:
            with self._transport.transfers.stream("GET", file.url, headers=headers) as response:
                if response.status_code == 403:
                    raise _LinkExpired
                if response.status_code not in (200, 206):
                    raise IdleoError(f"storage answered {response.status_code} for {file.path}")
                append = response.status_code == 206
                written = have if append else 0
                with part.open("ab" if append else "wb") as out:
                    for chunk in response.iter_bytes():
                        out.write(chunk)
                        written += len(chunk)
                        if on_progress:
                            on_progress(file.path, written, file.size_bytes)
        except httpx.TransportError as error:
            raise NetworkError(f"the download of {file.path} dropped: {type(error).__name__}") from error
        size = part.stat().st_size
        if size != file.size_bytes:
            raise NetworkError(f"only {size} of {file.size_bytes} bytes of {file.path} arrived")
