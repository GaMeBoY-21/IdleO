#!/usr/bin/env bash
# Expand and contract (ADR 0046): a release's migrations run before its canary, while the release before still serves,
# and a canary may be rolled back to it, so every migration must work with both. Additive changes ship any time;
# dropping, renaming or changing the type of anything happens only in a later release, once nothing reads the old
# shape. A new migration that contains DROP, RENAME or ALTER ... TYPE (outside comments) fails this check unless the
# file says why it is safe, in a line of its own:
#
#   -- contract: <what goes and why>, safe since <the release since which nothing reads it>
#
# The migrations up to V61, written before this rule, are as they are (Flyway's checksums forbid editing them).
#
#   scripts/checks/migrations.sh [folder]     the control plane's migrations by default
#
# scripts/checks/control-plane.sh runs it first; scripts/k8s/canary-test.sh shows it refusing one.
set -euo pipefail
cd "$(dirname "$0")/../.."
folder="${1:-control-plane/src/main/resources/db/migration}"
python3 - "$folder" <<'PY'
import pathlib, re, sys
LAST_BEFORE_THE_RULE = 61
RULE = """A migration runs before its release's canary, while the release before still serves, so it must work with both
(ADR 0046, "Expand and contract"). Add columns and tables any time; drop, rename or change the type of one only in a
later release, once no release that runs reads it, and say so in the file:

  -- contract: <what goes and why>, safe since <the release since which nothing reads it>"""
contract = re.compile(r"^-- contract: \S.*, safe since \S+\s*$", re.M)
risky = [
    ("DROP", re.compile(r"\bdrop\b", re.I)),
    ("RENAME", re.compile(r"\brename\b", re.I)),
    ("ALTER ... TYPE", re.compile(r"\balter\b[^;]*\btype\b", re.I)),
]
problems = []
for path in sorted(pathlib.Path(sys.argv[1]).glob("V*__*.sql")):
    version = int(re.match(r"V(\d+)__", path.name).group(1))
    if version <= LAST_BEFORE_THE_RULE:
        continue
    text = path.read_text()
    code = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    code = re.sub(r"--[^\n]*", " ", code)
    found = [name for name, pattern in risky if pattern.search(code)]
    if found and not contract.search(text):
        problems.append(f"{path.name}: {', '.join(found)} without a '-- contract: ..., safe since ...' line")
if problems:
    print("migrations: " + "\nmigrations: ".join(problems), file=sys.stderr)
    print("\n" + RULE, file=sys.stderr)
    sys.exit(1)
PY
