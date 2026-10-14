"""What gets uploaded: a folder's files, or one file, with their paths and sizes, and a fingerprint that says whether
it's the same set as last time (so a half-sent upload carries on instead of starting over). The same rules and the
same fingerprint as the command line's (cli/src/files.rs), so either can carry on the other's upload."""

from __future__ import annotations

import hashlib
import os
from dataclasses import dataclass
from pathlib import Path

from idleo._problem import IdleoError, UsageError

#: The most files one upload takes (ADR 0022).
MAX_FILES = 1_000


@dataclass(frozen=True)
class LocalFile:
    #: The path inside the upload, `/`-separated and relative: `config.json`, `shards/part-0.bin`.
    path: str
    size: int
    #: Where it is on this computer.
    source: Path
    #: When it last changed, in whole seconds, so an edited file isn't taken for the one sent before.
    modified: int


def collect(root: Path) -> tuple[str, list[LocalFile]]:
    """The files of a folder (every regular file at any depth; links and other special files are left out), or the one
    file, sorted by path."""
    name = root.name or "files"
    files: list[LocalFile] = []
    try:
        if root.is_dir():
            _walk(root, root, files)
        else:
            files.append(_local(root, name))
    except OSError as error:
        raise IdleoError(f"couldn't read {error.filename or root}: {error.strerror or error}") from error
    if not files:
        raise UsageError(f"{root} has no files in it to upload")
    if len(files) > MAX_FILES:
        raise UsageError(
            f"{root} has {len(files)} files; an upload takes at most {MAX_FILES}. "
            "Put them in an archive, or send a smaller folder."
        )
    files.sort(key=lambda file: file.path.encode())
    return name, files


def _walk(root: Path, directory: Path, out: list[LocalFile]) -> None:
    with os.scandir(directory) as entries:
        for entry in entries:
            path = Path(entry.path)
            if entry.is_dir(follow_symlinks=False):
                _walk(root, path, out)
            elif entry.is_file(follow_symlinks=False):
                out.append(_local(path, "/".join(path.relative_to(root).parts)))


def _local(path: Path, inside: str) -> LocalFile:
    meta = path.stat()
    return LocalFile(inside, meta.st_size, path, int(meta.st_mtime))


def fingerprint(name: str, files: list[LocalFile]) -> str:
    """The same files, with the same sizes and modification times, always have the same fingerprint."""
    digest = hashlib.sha256(name.encode())
    for file in files:
        digest.update(f"\n{file.path}\t{file.size}\t{file.modified}".encode())
    return digest.hexdigest()


def part_range(number: int, part_size: int, file_size: int) -> tuple[int, int]:
    """The bytes a part covers: part numbers start at 1, each `part_size` long but the last, which is what's left."""
    start = (number - 1) * part_size
    return min(start, file_size), min(start + part_size, file_size)
