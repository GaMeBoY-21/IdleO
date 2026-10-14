"""Every exception the SDK raises on purpose, in one place: `from idleo.errors import InsufficientCredit`.

One class for each problem type the API names (generated from api/problems.json; docs/api/problems.md says what each
means), and the few failures of the SDK's own. All of them are `IdleoError`s, and each says what the command line
would exit with (`exit_code`).
"""

from idleo._generated.errors import *  # noqa: F403
from idleo._generated.errors import PROBLEMS
from idleo._problem import (
    IdleoError,
    NetworkError,
    NotSignedIn,
    ServerError,
    SessionNotCompleted,
    UsageError,
    WaitTimeout,
)

__all__ = [
    "IdleoError",
    "NetworkError",
    "NotSignedIn",
    "ServerError",
    "SessionNotCompleted",
    "UsageError",
    "WaitTimeout",
]
from idleo._generated import errors as _generated_errors

__all__ += _generated_errors.__all__
del _generated_errors, PROBLEMS
