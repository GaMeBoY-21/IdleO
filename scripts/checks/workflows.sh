#!/usr/bin/env bash
# The workflows' own checks (CLAUDE.md, "CI and GitHub"): actionlint (syntax, expressions, and shellcheck on every run
# block) and zizmor (template injection, credentials left in a checkout, unpinned actions, excessive permissions), both
# pinned by digest. Any finding fails; there are no exceptions on file.
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry workflows "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
cd "$(dirname "$0")/../.."

actionlint=rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667
zizmor=ghcr.io/zizmorcore/zizmor:1.16.3@sha256:f09cee55087d54e7a162fc255ffe82ef942f68e683f967c8a6471ffe35e7ec64

docker run --rm -v "$PWD:/repo:ro" -w /repo "$actionlint" -color=false
# Offline: it reads only the files, so it never needs a token, and an informational note isn't a failure.
docker run --rm -v "$PWD:/repo:ro" -w /repo "$zizmor" --offline --min-severity low --format plain .github/workflows
