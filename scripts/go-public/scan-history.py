#!/usr/bin/env python3
"""Lists every file version in a repository's whole history that contains one of the strings on the left side of a
replacements file (private/go-public-replacements.txt). Commit messages and authors are not read here; verify.sh
checks those. Prints `blob-id path string` per hit and exits 1 if there is any.

    scan-history.py REPO REPLACEMENTS
"""
import subprocess
import sys


def main(repo: str, replacements: str) -> int:
    with open(replacements, encoding="utf-8") as f:
        needles = [line.split("==>")[0].encode() for line in f.read().splitlines() if line]
    paths: dict[str, str] = {}
    listing = subprocess.run(["git", "-C", repo, "rev-list", "--all", "--objects"], capture_output=True, check=True)
    for line in listing.stdout.decode(errors="replace").splitlines():
        oid, _, path = line.partition(" ")
        paths.setdefault(oid, path)
    hits = 0
    with subprocess.Popen(["git", "-C", repo, "cat-file", "--batch-all-objects", "--batch"], stdout=subprocess.PIPE) as proc:
        assert proc.stdout is not None
        while header := proc.stdout.readline():
            oid, kind, size = header.split()
            body = proc.stdout.read(int(size))
            proc.stdout.read(1)
            if kind != b"blob":
                continue
            for needle in needles:
                if needle in body:
                    hits += 1
                    print(f"{oid.decode()} {paths.get(oid.decode(), '?')} {needle.decode()}")
    return 1 if hits else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
