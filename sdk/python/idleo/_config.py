"""Where the SDK talks to and keeps its files: the same API address and config folder as the command line."""

from __future__ import annotations

import os
import sys
from pathlib import Path

import httpx

from idleo._brand import ENV_PREFIX, NAME
from idleo._problem import UsageError

#: The local stack until the product has an address: production sets IDLEO_API_URL at deployment (as the CLI does).
DEFAULT_API_URL = "http://localhost:8080"


def api_url(given: str | None = None) -> str:
    """The API's address: the argument, else IDLEO_API_URL, else the local stack; no trailing slash."""
    text = given or os.environ.get(f"{ENV_PREFIX}_API_URL") or ""
    text = text.strip() or DEFAULT_API_URL
    try:
        url = httpx.URL(text)
    except httpx.InvalidURL as error:
        raise UsageError(f"the API address {text!r} isn't a URL: {error}") from error
    if url.scheme not in ("http", "https") or not url.host:
        raise UsageError(f"the API address {text!r} must start with http:// or https://")
    return str(url).rstrip("/")


def config_dir(given: str | Path | None = None) -> Path:
    """The folder for the command line's and the SDK's own files: IDLEO_CONFIG_DIR, else the system's usual place."""
    if given:
        return Path(given)
    env = os.environ.get(f"{ENV_PREFIX}_CONFIG_DIR", "").strip()
    if env:
        return Path(env)
    home = Path.home()
    if sys.platform == "win32":
        appdata = os.environ.get("APPDATA")
        base = Path(appdata) if appdata else home / "AppData" / "Roaming"
    elif sys.platform == "darwin":
        base = home / "Library" / "Application Support"
    else:
        xdg = os.environ.get("XDG_CONFIG_HOME")
        base = Path(xdg) if xdg else home / ".config"
    return base / NAME
