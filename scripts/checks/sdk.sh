#!/usr/bin/env bash
# The Python SDK's checks (.github/workflows/sdk.yml runs this, on each Python it tests; so does scripts/check-all.sh).
# SDK_PYTHON picks the Python; uv fetches it.
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry sdk "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
cd "$(dirname "$0")/../../sdk/python"
uv sync --locked ${SDK_PYTHON:+--python "$SDK_PYTHON"}
uv run ruff format --check .
uv run ruff check .
uv run mypy idleo
uv run pytest
uv run python scripts/generate.py --check
uv run python scripts/docs.py --check
