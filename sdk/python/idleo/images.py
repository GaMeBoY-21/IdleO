"""Your own container images, and the check each must pass on our server before it can run (ADR 0042)."""

from __future__ import annotations

import builtins
import time
import uuid

from idleo._generated.models import ImageList, ImageResponse, NewImage, VulnerabilityList
from idleo._http import Transport
from idleo._notebook import Status
from idleo._problem import IdleoError

#: How often a check's progress is asked for while waiting, in seconds.
POLL = 3.0


class ImageNotChecked(IdleoError):
    """The image's check ended without it being ready: blocked, or not checked (`image.detail` says why)."""

    def __init__(self, message: str, image: Image) -> None:
        super().__init__(message)
        self.image = image


class WaitTimeout(IdleoError):
    """The check was still going when the time given to `wait()` ran out; it carries on."""


class Image:
    """One of your images and its check. Its fields are the API's `ImageResponse` (`state`, `reason`, `detail`,
    `progress`, `result`); `refresh()` reads it again."""

    def __init__(self, transport: Transport, data: ImageResponse) -> None:
        self._transport = transport
        self.data = data

    @property
    def id(self) -> uuid.UUID:
        return self.data.id

    @property
    def reference(self) -> str:
        return self.data.reference

    @property
    def state(self) -> str:
        """`queued`, `checking`, `ready`, `blocked` or `failed` (not checked: check it again)."""
        return self.data.state.value

    @property
    def detail(self) -> str | None:
        """Why it's blocked or wasn't checked, in plain words."""
        return self.data.detail

    @property
    def warnings(self) -> builtins.list[str]:
        """What the check found that doesn't block it: known vulnerabilities, secrets, a root USER, no shell."""
        return [warning.message for warning in self.data.result.warnings] if self.data.result else []

    @property
    def done(self) -> bool:
        """Whether its check has ended."""
        return self.state not in ("queued", "checking")

    def refresh(self) -> Image:
        self.data = self._transport.get(f"/v1/images/{self.id}", ImageResponse)
        return self

    def wait(self, timeout: float | None = None, *, progress: bool | None = None, check: bool = True) -> Image:
        """Waits until the check has ended and returns the image. With `check` (the default) an image that isn't ready
        raises ImageNotChecked, which carries it. Raises WaitTimeout if `timeout` seconds pass first; the check goes on.
        `progress` shows it as it goes: by default only in a notebook."""
        status = Status(progress)
        deadline = None if timeout is None else time.monotonic() + timeout
        while not self.done:
            status.update(self._words())
            if deadline is not None and time.monotonic() >= deadline:
                raise WaitTimeout(f"{self.reference} is still being checked after {timeout} s")
            self._transport.sleep(POLL)
            self.refresh()
        status.update(self._words())
        if check and self.state != "ready":
            raise ImageNotChecked(self.detail or f"{self.reference} is {self.state}.", self)
        return self

    def vulnerabilities(self) -> VulnerabilityList:
        """Everything Trivy found, most severe first (at most 5,000), with the counts by severity."""
        return self._transport.get(f"/v1/images/{self.id}/vulnerabilities", VulnerabilityList)

    def _words(self) -> str:
        progress = self.data.progress
        if self.done or progress is None:
            return f"{self.reference}: {self.state}"
        if progress.phase.value == "downloading" and progress.total_bytes:
            return f"{self.reference}: downloading, {progress.done_bytes:,} of {progress.total_bytes:,} bytes"
        return f"{self.reference}: {progress.phase.value}"

    def __repr__(self) -> str:
        return f"Image(id={self.id}, reference={self.reference!r}, state={self.state!r})"


class Images:
    """Your own container images (`client.images`). Using one needs a verified PAN."""

    def __init__(self, transport: Transport) -> None:
        self._transport = transport

    def submit(self, reference: str, *, username: str | None = None, token: str | None = None) -> Image:
        """Queues a check of `reference` (a tag or a digest). A private registry needs `username` and a read-only
        `token`, which is kept sealed, used only to download the image, and never shown back. Call `wait()` on the
        result for the check."""
        body = NewImage(reference=reference, registry_username=username, registry_token=token)
        return Image(self._transport, self._transport.post("/v1/images", ImageResponse, body, str(uuid.uuid4())))

    def get(self, image_id: str | uuid.UUID) -> Image:
        return Image(self._transport, self._transport.get(f"/v1/images/{image_id}", ImageResponse))

    def list(self) -> builtins.list[Image]:
        """Your images, newest first."""
        return [Image(self._transport, data) for data in self._transport.get("/v1/images", ImageList).images]

    def can_use(self) -> tuple[bool, str | None]:
        """Whether you may use your own images now, and if not, what to do."""
        listing = self._transport.get("/v1/images", ImageList)
        return listing.can_use, listing.why_not

    def remove(self, image_id: str | uuid.UUID) -> None:
        """Removes an image, and its registry token unless another of your images uses it."""
        self._transport.call("DELETE", f"/v1/images/{image_id}")
