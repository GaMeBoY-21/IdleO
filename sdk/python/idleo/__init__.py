"""IdleO's Python SDK: run, follow and download sessions from a script or a notebook.

    from idleo import Client
    client = Client()
    s = client.sessions.run("fine-tune-simulated", inputs={"model": "./my-model", "data": "./train.jsonl"},
                            presets={"thoroughness": "standard"}, max_spend_paise=200_00)
    for line in s.logs(follow=True):
        print(line)
    s.wait()
    s.outputs.download("./out")

The exceptions are in `idleo.errors`; `idleo.InsufficientCredit` and the rest work too.
"""

from typing import Any

from idleo import errors
from idleo.client import Client
from idleo.errors import IdleoError
from idleo.images import Image, ImageNotChecked
from idleo.money import paise, rupees
from idleo.session import Session

__all__ = ["Client", "IdleoError", "Image", "ImageNotChecked", "Session", "errors", "paise", "rupees"]


def __getattr__(name: str) -> Any:
    """The exception classes are reachable from the top: `idleo.NoGpuFree`."""
    try:
        return getattr(errors, name)
    except AttributeError:
        raise AttributeError(f"module 'idleo' has no attribute {name!r}") from None
