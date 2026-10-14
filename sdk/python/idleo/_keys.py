"""Where the API key is kept: the command line's own storage, read the same way (docs/plan/product-surface.md 4.1).

`IDLEO_API_KEY` wins; otherwise (unless `IDLEO_NO_KEYCHAIN` is `1` or `true`) the operating system's keychain entry
the command line made on `idleo login` (service `idleo`, account the API address); otherwise its `credentials.json`,
a file only its owner can read. A key is kept per API address. The SDK only reads: signing in and out stays with the
command line.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from idleo._brand import ENV_PREFIX, NAME

FILE = "credentials.json"


def stored_key(api: str, directory: Path) -> str | None:
    """The key to use for this API address, or None when there isn't one."""
    env = os.environ.get(f"{ENV_PREFIX}_API_KEY", "").strip()
    if env:
        return env
    if not keychain_off(os.environ.get(f"{ENV_PREFIX}_NO_KEYCHAIN")):
        key = _from_keychain(api)
        if key:
            return key
    return _from_file(api, directory)


def keychain_off(value: str | None) -> bool:
    """`IDLEO_NO_KEYCHAIN` turns the keychain off only when it is `1` or `true`, as it does for the command line."""
    return (value or "").strip().lower() in ("1", "true")


def _key_of(blob: Any) -> str | None:
    key = blob.get("key") if isinstance(blob, dict) else None
    return key.strip() if isinstance(key, str) and key.strip() else None


def _from_keychain(api: str) -> str | None:
    try:
        import keyring

        text = keyring.get_password(NAME, api)
        return _key_of(json.loads(text)) if text else None
    except Exception:
        return None


def _from_file(api: str, directory: Path) -> str | None:
    try:
        return _key_of(json.loads((directory / FILE).read_text()).get(api))
    except (OSError, ValueError, AttributeError):
        return None
