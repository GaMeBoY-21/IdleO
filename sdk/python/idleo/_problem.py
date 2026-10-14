"""What can go wrong: every problem the API names is an exception, under one base class.

The classes for the API's problem types are generated from the catalog (_generated/errors.py, from
api/problems.json); this module holds the base they share and the few failures that happen before the API answers.
Each has the exit meaning the command line gives it (`exit_code`), so a script that moves from the CLI to Python
branches on the same things.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from idleo.session import Session

# The command line's exit codes (cli/src/error.rs): the one table both clients share.
EXIT_FAILED = 1
EXIT_USAGE = 2
EXIT_NOT_ENOUGH_CREDIT = 3
EXIT_NO_GPU_FREE = 4
EXIT_NOT_SIGNED_IN = 5
EXIT_RATE_LIMITED = 6
EXIT_NETWORK = 7

_EXIT_BY_SLUG = {
    "unauthenticated": EXIT_NOT_SIGNED_IN,
    "invite-required": EXIT_NOT_SIGNED_IN,
    "browser-sign-in-required": EXIT_NOT_SIGNED_IN,
    "insufficient-credit": EXIT_NOT_ENOUGH_CREDIT,
    "no-gpu-free": EXIT_NO_GPU_FREE,
    "rate-limited": EXIT_RATE_LIMITED,
    "too-many-new-sessions": EXIT_RATE_LIMITED,
    "validation-failed": EXIT_USAGE,
    "malformed-request": EXIT_USAGE,
}


def exit_code_for(slug: str) -> int:
    """The command line's exit code for a problem type; anything the table doesn't name is 1."""
    return _EXIT_BY_SLUG.get(slug, EXIT_FAILED)


class IdleoError(Exception):
    """Everything the SDK raises on purpose. `slug` is the API's problem type, or the SDK's own name for a failure."""

    slug: str = "error"
    status: int = 0
    title: str = "Something went wrong"

    def __init__(
        self,
        message: str | None = None,
        *,
        retry_after: int | None = None,
        fields: dict[str, str] | None = None,
        problem: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(message or self.title)
        #: Seconds to wait before trying again, when the API said so (every 429 does).
        self.retry_after = retry_after
        #: For a 400 about invalid fields: each field and what's wrong with it.
        self.fields = fields or {}
        #: The API's whole problem detail, as it came.
        self.problem = problem

    @property
    def exit_code(self) -> int:
        """What the command line would exit with for this failure."""
        return exit_code_for(self.slug)


class NotSignedIn(IdleoError):
    """There is no API key: none given, none in the environment, none stored by `idleo login`."""

    slug = "not-signed-in"
    title = "Not signed in"

    @property
    def exit_code(self) -> int:
        return EXIT_NOT_SIGNED_IN


class NetworkError(IdleoError):
    """The API couldn't be reached, or the connection dropped."""

    slug = "network"
    title = "Couldn't reach the API"

    @property
    def exit_code(self) -> int:
        return EXIT_NETWORK


class UsageError(IdleoError, ValueError):
    """The arguments don't fit the template: found before anything is uploaded."""

    slug = "usage"
    title = "The arguments don't fit the template"

    @property
    def exit_code(self) -> int:
        return EXIT_USAGE


class ServerError(IdleoError):
    """The service answered 5xx without a problem type."""

    slug = "server-error"
    title = "The service had a problem"


class SessionNotCompleted(IdleoError):
    """The session settled without completing: it failed, was cancelled, or was stopped. `session` says how."""

    slug = "session-not-completed"
    title = "The session didn't complete"

    def __init__(self, message: str, session: Session) -> None:
        super().__init__(message)
        self.session = session


class WaitTimeout(IdleoError, TimeoutError):
    """`wait(timeout=...)` ran out of time; the session is still going."""

    slug = "wait-timeout"
    title = "Still running"
