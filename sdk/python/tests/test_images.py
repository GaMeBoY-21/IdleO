"""Your own images (ADR 0042): submitted with a read-only token for a private registry, waited for until the check has
ended, and booked with the template that runs one."""

from __future__ import annotations

import httpx
import pytest
from conftest import body, problem, session, template

from idleo import Image, ImageNotChecked, errors
from idleo._problem import UsageError

IMAGE_ID = "01a1765a-0000-7000-8000-000000000001"


def image(state: str = "queued", **over: object) -> dict[str, object]:
    base: dict[str, object] = {
        "id": IMAGE_ID,
        "reference": "ghcr.io/me/job:v1",
        "privateRegistry": True,
        "state": state,
        "createdAt": "2026-10-26T10:00:00Z",
    }
    base.update(over)
    return base


def own_template() -> dict[str, object]:
    return template(id="custom-image", ownImage=True, inputs=[], questions=[], params=[], vramFromInput=None)


def test_a_private_image_is_submitted_with_its_token_and_waited_for(fake, tmp_path):
    answers = iter(
        [image("checking", progress={"phase": "downloading", "doneBytes": 5, "totalBytes": 10}), image("ready")]
    )
    fake.route("POST", "/v1/images", lambda r: httpx.Response(201, json=image()))
    fake.route("GET", f"/v1/images/{IMAGE_ID}", lambda r: httpx.Response(200, json=next(answers)))
    client = fake.client(tmp_path)
    submitted = client.images.submit("ghcr.io/me/job:v1", username="me", token="ghp_readonly")
    (post,) = fake.calls("POST", "/v1/images")
    assert body(post) == {"reference": "ghcr.io/me/job:v1", "registryUsername": "me", "registryToken": "ghp_readonly"}
    assert post.headers["Idempotency-Key"]
    ready = submitted.wait()
    assert ready.state == "ready" and ready.done
    assert "ghp_readonly" not in repr(ready)


def test_an_image_that_isnt_ready_raises_with_the_reason_unless_check_is_off(fake, tmp_path):
    blocked = image("blocked", reason="blocklisted", detail="Your image holds software that isn't allowed on IdleO.")
    fake.route("GET", f"/v1/images/{IMAGE_ID}", lambda r: httpx.Response(200, json=blocked))
    client = fake.client(tmp_path)
    with pytest.raises(ImageNotChecked, match="isn't allowed") as raised:
        client.images.get(IMAGE_ID).wait()
    assert raised.value.image.state == "blocked"
    assert client.images.get(IMAGE_ID).wait(check=False).state == "blocked"


def test_without_a_verified_pan_the_api_says_how(fake, tmp_path):
    fake.route(
        "POST", "/v1/images", lambda r: problem("verified-pan-required", 403, "Give your PAN in Payout details.")
    )
    with pytest.raises(errors.VerifiedPanRequired):
        fake.client(tmp_path).images.submit("ubuntu")


def test_booking_the_own_image_template_names_the_image_and_others_refuse_one(fake, tmp_path):
    fake.route(
        "GET",
        "/v1/templates",
        lambda r: httpx.Response(200, json={"services": [], "templates": [own_template(), template()]}),
    )
    fake.route("POST", "/v1/sessions", lambda r: httpx.Response(201, json=session(templateId="custom-image")))
    fake.route("GET", f"/v1/images/{IMAGE_ID}", lambda r: httpx.Response(200, json=image("ready")))
    client = fake.client(tmp_path)
    ready = client.images.get(IMAGE_ID)
    assert isinstance(ready, Image)
    client.sessions.create("custom-image", params={}, image=ready, max_spend_paise=1000)
    (post,) = fake.calls("POST", "/v1/sessions")
    assert body(post)["imageId"] == IMAGE_ID
    with pytest.raises(UsageError, match="runs your own image"):
        client.sessions.create("custom-image", max_spend_paise=1000)
    with pytest.raises(UsageError, match="runs its own image"):
        client.sessions.create(
            "fine-tune-simulated",
            inputs={"model": "https://x.example/m", "data": "https://x.example/d"},
            image=IMAGE_ID,
        )
