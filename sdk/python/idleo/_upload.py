"""A folder of files, or one file, uploaded straight to storage (ADR 0019, ADR 0022), as home internet allows: the same
steps as the command line's (cli/src/upload.rs) and the web app's.

- `POST /v1/uploads` opens it with each file's path and size; each file's parts are signed on demand
  (`POST /v1/uploads/{id}/part-urls`), a hundred at a time, so a 4,000-part model never asks for 4,000 links at once;
- each part is a slice of its file, never the whole file in memory, PUT three at a time;
- each ETag is remembered in a file as it arrives (`_resume.py`): running the same call again opens the same upload
  with the same Idempotency-Key and sends only the parts storage hasn't taken;
- every file is then completed (`POST /v1/uploads/{id}/complete`), a hundred to a call;
- a failed part is retried three times with backoff; an expired link (403) gets a fresh one.
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable
from concurrent.futures import FIRST_EXCEPTION, ThreadPoolExecutor, wait
from pathlib import Path

import httpx

from idleo._files import LocalFile, collect, fingerprint, part_range
from idleo._generated.models import (
    CompletedFile,
    CompleteUploadRequest,
    CreateUploadRequest,
    PartLinksRequest,
    PartNumber,
    Status,
    UploadDetail,
    UploadedPart,
    UploadFileRequest,
    UploadPartLinks,
)
from idleo._http import Transport
from idleo._problem import IdleoError, NetworkError
from idleo._resume import ResumeStore, Unfinished, new_key

CONCURRENCY = 3
#: The most part links, and the most files to complete, one request takes, as the API allows.
PER_REQUEST = 100
RETRIES = 3

#: Called now and then with (label, bytes sent, bytes in all); `label` is the input's name.
OnProgress = Callable[[str, int, int], None]
OnNote = Callable[[str], None]


class _Links:
    """Part links already asked for, by file path and part number."""

    def __init__(self, transport: Transport, upload_id: str, todo: list[tuple[str, int]]) -> None:
        self._transport = transport
        self._id = upload_id
        self._todo = todo
        self._known: dict[tuple[str, int], str] = {}
        self._lock = threading.Lock()

    def url(self, path: str, number: int) -> str:
        """The link for one part, asking for it and the file's next parts still to send, a hundred at most."""
        with self._lock:
            if (path, number) in self._known:
                return self._known[(path, number)]
            wanted = [number]
            wanted += [n for p, n in self._todo if p == path and n != number and (p, n) not in self._known]
            body = PartLinksRequest(path=path, part_numbers=[PartNumber(n) for n in wanted[:PER_REQUEST]])
            links = self._transport.post(f"/v1/uploads/{self._id}/part-urls", UploadPartLinks, body)
            for link in links.parts:
                self._known[(path, link.number)] = link.url
            if (path, number) not in self._known:
                raise IdleoError(f"the API sent no link for part {number} of {path}")
            return self._known[(path, number)]

    def forget(self, path: str, number: int) -> None:
        with self._lock:
            self._known.pop((path, number), None)


class _Progress:
    """Bytes storage has taken, reported about once a second."""

    def __init__(self, label: str, base: int, total: int, on_progress: OnProgress | None) -> None:
        self._label, self._sent, self._total, self._on = label, base, total, on_progress
        self._last = 0.0
        self._lock = threading.Lock()

    def add(self, size: int) -> None:
        with self._lock:
            self._sent += size
            now = time.monotonic()
            if self._on and (now - self._last >= 1 or self._sent >= self._total):
                self._last = now
                self._on(self._label, self._sent, self._total)


class Uploader:
    def __init__(
        self,
        transport: Transport,
        resume: ResumeStore,
        on_progress: OnProgress | None = None,
        on_note: OnNote | None = None,
    ) -> None:
        self._transport = transport
        self._resume = resume
        self._on_progress = on_progress
        self._note = on_note or (lambda _text: None)

    def upload(self, root: Path, label: str) -> str:
        """Uploads what's at `root`, or carries on with it; the upload's id once it's ready."""
        name, files = collect(root)
        fp = fingerprint(name, files)
        total = sum(file.size for file in files)
        specs = CreateUploadRequest(name=name, files=[UploadFileRequest(path=f.path, size_bytes=f.size) for f in files])
        self._note(f"{label}: {len(files)} file(s), {total:,} bytes")
        opened, record = self._open(fp, specs)
        upload_id = str(opened.upload.id)
        record.upload_id = upload_id
        if opened.upload.status == Status.ready:
            self._resume.forget(fp)
            self._note(f"{label}: already uploaded")
            return upload_id
        part_size = opened.upload.part_size_bytes
        by_path = {file.path: file for file in files}

        # What storage still needs: every part of every unfinished file that the record doesn't have.
        todo: list[tuple[str, int]] = []
        base = 0
        left: list[str] = []
        for file in opened.files:
            if file.done:
                base += file.size_bytes
                continue
            left.append(file.path)
            taken = record.parts.setdefault(file.path, {})
            for number in range(1, file.part_count + 1):
                if number in taken:
                    start, end = part_range(number, part_size, file.size_bytes)
                    base += end - start
                else:
                    todo.append((file.path, number))

        links = _Links(self._transport, upload_id, todo)
        progress = _Progress(label, base, total, self._on_progress)
        lock = threading.Lock()

        def send(path: str, number: int) -> None:
            etag = self._send_part(links, by_path[path], number, part_size, progress)
            with lock:
                record.parts.setdefault(path, {})[number] = etag
                self._resume.write(fp, record)

        with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
            futures = [pool.submit(send, path, number) for path, number in todo]
            done = wait(futures, return_when=FIRST_EXCEPTION)
            for future in done.done:
                if future.exception():
                    for other in futures:
                        other.cancel()
                    raise future.exception()  # type: ignore[misc]
        self._complete(upload_id, left, record)
        self._resume.forget(fp)
        self._note(f"{label}: uploaded {total:,} bytes")
        return upload_id

    def _open(self, fp: str, specs: CreateUploadRequest) -> tuple[UploadDetail, Unfinished]:
        """The upload these files went to before, if one is remembered and still wanted, opened again with its key;
        else a new one."""
        saved = self._resume.read(fp)
        if saved:
            try:
                again = self._transport.post("/v1/uploads", UploadDetail, specs, saved.key)
                if str(again.upload.id) == saved.upload_id and again.upload.status in (Status.pending, Status.ready):
                    self._note("carrying on from where it stopped")
                    return again, saved
            except NetworkError:
                raise
            except IdleoError:
                pass  # Gone, failed or refused: start over.
            self._resume.forget(fp)
        key = new_key()
        opened = self._transport.post("/v1/uploads", UploadDetail, specs, key)
        record = Unfinished(upload_id=str(opened.upload.id), key=key)
        self._resume.write(fp, record)
        return opened, record

    def _send_part(self, links: _Links, file: LocalFile, number: int, part_size: int, progress: _Progress) -> str:
        start, end = part_range(number, part_size, file.size)
        failures = 0
        refreshes = 0
        while True:
            url = links.url(file.path, number)
            with file.source.open("rb") as handle:
                handle.seek(start)
                body = handle.read(end - start)
            try:
                response = self._transport.transfers.put(url, content=body)
                status = response.status_code
            except httpx.TransportError as error:
                status, why = 0, f"{type(error).__name__}"
            else:
                why = f"storage answered {status}"
                if response.is_success:
                    etag: str | None = response.headers.get("ETag")
                    if not etag:
                        raise IdleoError("storage didn't say it had the part (no ETag)")
                    progress.add(end - start)
                    return etag
            if status == 403 and refreshes < RETRIES:
                # The part's link has expired: forget it, so the next try asks for a fresh one.
                refreshes += 1
                links.forget(file.path, number)
                continue
            failures += 1
            if failures > RETRIES:
                raise NetworkError(f"part {number} of {file.path} couldn't be sent: {why}")
            self._transport.sleep(2 ** (failures - 1))

    def _complete(self, upload_id: str, left: list[str], record: Unfinished) -> None:
        finished = [
            CompletedFile(
                path=path,
                parts=[UploadedPart(number=n, etag=etag) for n, etag in sorted(record.parts.get(path, {}).items())],
            )
            for path in left
        ]
        for start in range(0, len(finished), PER_REQUEST):
            batch = CompleteUploadRequest(files=finished[start : start + PER_REQUEST])
            self._transport.post_raw(f"/v1/uploads/{upload_id}/complete", batch)
