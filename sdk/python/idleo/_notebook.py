"""Progress for notebooks without a dependency on them: when the code runs in Jupyter (IPython is already loaded and
its shell is the notebook kind), a status line is shown and updated in place; anywhere else it stays silent unless a
caller asks for it, and then it's a plain line on stderr when the words change."""

from __future__ import annotations

import html
import sys
from typing import Any


def in_notebook() -> bool:
    """True inside a Jupyter notebook or console, found without importing IPython if it isn't already there."""
    ipython = sys.modules.get("IPython")
    if ipython is None:
        return False
    shell = ipython.get_ipython()
    return shell is not None and type(shell).__name__ == "ZMQInteractiveShell"


class Status:
    """One line of progress that changes: a display updated in place in a notebook, else lines on stderr."""

    def __init__(self, enabled: bool | None) -> None:
        self._on = in_notebook() if enabled is None else enabled
        self._handle: Any = None
        self._last = ""

    def update(self, text: str) -> None:
        if not self._on or text == self._last:
            return
        self._last = text
        if in_notebook():
            from IPython.display import HTML, display  # type: ignore[import-not-found]

            page = HTML(f"<code>{html.escape(text)}</code>")
            if self._handle is None:
                self._handle = display(page, display_id=True)
            else:
                self._handle.update(page)
        else:
            print(text, file=sys.stderr, flush=True)
