"""The SDK's page in the public docs is made from the code's docstrings, and a test fails when it drifts (as the command
line's reference does): `uv run python scripts/docs.py` rewrites it."""

from __future__ import annotations

import importlib.util
import os
from pathlib import Path

ROOT = Path(__file__).parents[3]
SCRIPT = Path(__file__).parents[1] / "scripts" / "docs.py"


def load():
    spec = importlib.util.spec_from_file_location("sdk_docs", SCRIPT)
    module = importlib.util.module_from_spec(spec)  # type: ignore[arg-type]
    spec.loader.exec_module(module)  # type: ignore[union-attr]
    return module


def test_the_committed_reference_is_the_codes():
    module = load()
    made = module.page()
    if os.environ.get("IDLEO_DOCS_WRITE"):
        module.PAGE.write_text(made)
    assert module.PAGE.read_text() == made, "run: uv run python scripts/docs.py"


def test_every_public_call_is_in_it_with_its_words():
    page = load().page()
    for call in (
        "client.sessions.run(",
        "session.wait(",
        "session.logs(",
        "session.outputs.download(",
        "idleo.rupees(",
    ):
        assert call in page, call
    assert "InsufficientCredit" in page
    assert "| `NoGpuFree` | `no-gpu-free` | 409 | 4 |" in page


def test_the_page_never_says_we_supply_a_model():
    page = load().page().lower()
    assert "you bring the model and the data; we provide the gpu" in page
    for forbidden in ("our models", "provided model", "we supply", "base model we"):
        assert forbidden not in page
