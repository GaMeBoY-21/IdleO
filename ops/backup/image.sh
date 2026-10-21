#!/usr/bin/env bash
# Builds ops/postgres (PostgreSQL with WAL-G, ADR 0036) once for this machine's architecture and prints its tag, which
# names the Dockerfile's content, so a change builds a new one. Shared by the backup scripts; compose.yaml builds the
# same Dockerfile itself.
set -euo pipefail
cd "$(dirname "$0")/../postgres"
tag="idleo-postgres:$(cat Dockerfile archive-wal backup-full idleo-postgres | shasum -a 256 | cut -c1-12)"
docker image inspect "$tag" >/dev/null 2>&1 || docker build -q -t "$tag" . >/dev/null
echo "$tag"
