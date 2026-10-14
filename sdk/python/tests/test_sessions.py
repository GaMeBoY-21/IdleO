"""Booking: inputs mapped by name, uploads first, the Idempotency-Key kept until the API answers."""

from __future__ import annotations

import uuid

import httpx
import pytest
from conftest import body, new_id, problem, session

from idleo import errors

SESSION_ID = "01a10e8e-9c65-7789-a526-473491c82218"


def books(fake, answer=None):
    fake.route("POST", "/v1/sessions", answer or (lambda r: httpx.Response(201, json=session())))


def test_run_books_with_named_inputs_presets_and_the_budget(fake, tmp_path):
    books(fake)
    up = uuid.uuid4()
    s = fake.client(tmp_path).sessions.run(
        "fine-tune-simulated",
        inputs={"model": "https://huggingface.co/x/y", "data": up},
        params={"epochs": 2},
        presets={"thoroughness": "quick"},
        gpu_memory_gb=12,
        max_spend_paise=200_00,
        max_runtime=3600,
    )
    assert s.id == SESSION_ID
    (post,) = fake.calls("POST", "/v1/sessions")
    assert body(post) == {
        "templateId": "fine-tune-simulated",
        "inputs": {"model": {"url": "https://huggingface.co/x/y"}, "data": {"uploadId": str(up)}},
        "params": {"epochs": 2},
        "presets": {"thoroughness": "quick"},
        "maxSpendPaise": 20000,
        "maxRuntimeSeconds": 3600,
        "vramNeededMb": 12000,
    }
    assert post.headers["Authorization"] == "Bearer idleo_test"
    assert post.headers["Idempotency-Key"]


def test_the_templates_usual_answers_are_sent_when_none_are_given(fake, tmp_path):
    books(fake)
    fake.client(tmp_path).sessions.run(
        "fine-tune-simulated", inputs={"model": "https://x.example/m", "data": "https://x.example/d"}
    )
    sent = body(fake.calls("POST", "/v1/sessions")[0])
    assert sent["presets"] == {"thoroughness": "standard"}
    assert sent["maxSpendPaise"] == 10000
    assert "vramNeededMb" not in sent


def test_run_uploads_each_local_input_first_and_books_with_the_upload_ids(fake, tmp_path):
    from test_upload import Storage

    storage = Storage(fake, {"a.txt": 3})
    books(fake)
    folder = tmp_path / "model"
    folder.mkdir()
    (folder / "a.txt").write_bytes(b"abc")
    fake.client(tmp_path).sessions.run("fine-tune-simulated", inputs={"model": folder, "data": "https://x.example/d"})
    order = [r.method + " " + r.url.path for r in fake.requests if r.url.host == "api.test"]
    assert order.index("POST /v1/uploads") < order.index("POST /v1/sessions")
    assert body(fake.calls("POST", "/v1/sessions")[0])["inputs"]["model"] == {"uploadId": storage.id}


def test_nothing_is_uploaded_when_the_arguments_are_wrong(fake, tmp_path):
    folder = tmp_path / "model"
    folder.mkdir()
    (folder / "a.txt").write_bytes(b"abc")
    with pytest.raises(errors.UsageError, match="epochs"):
        fake.client(tmp_path).sessions.run(
            "fine-tune-simulated", inputs={"model": folder, "data": "https://x.example/d"}, params={"epochs": "many"}
        )
    assert fake.calls("POST", "/v1/uploads") == []
    assert fake.calls("POST", "/v1/sessions") == []


def test_an_unknown_template_names_the_ones_there_are(fake, tmp_path):
    with pytest.raises(errors.UsageError, match="fine-tune-simulated"):
        fake.client(tmp_path).sessions.run("no-such")


def test_create_never_uploads(fake, tmp_path):
    books(fake)
    with pytest.raises(errors.UsageError, match=r"uploads\.upload"):
        fake.client(tmp_path).sessions.create(
            "fine-tune-simulated", inputs={"model": tmp_path, "data": "https://x.example/d"}
        )
    s = fake.client(tmp_path).sessions.create(
        "fine-tune-simulated", inputs={"model": "https://x.example/m", "data": uuid.uuid4()}
    )
    assert s.state == "REQUESTED"


def test_the_apis_refusal_is_its_exception_and_the_key_is_let_go(fake, tmp_path):
    books(fake, lambda r: problem("insufficient-credit", 402, "Booking holds 150 paise, but only 40 is available."))
    client = fake.client(tmp_path)
    with pytest.raises(errors.InsufficientCredit, match="only 40"):
        client.sessions.run(
            "fine-tune-simulated", inputs={"model": "https://x.example/m", "data": "https://x.example/d"}
        )
    assert list((tmp_path / "config" / "pending").glob("*.key")) == []


def test_a_dropped_connection_is_retried_with_the_same_key(fake, tmp_path):
    tries = []

    def flaky(request):
        tries.append(request.headers["Idempotency-Key"])
        if len(tries) < 3:
            raise httpx.ConnectError("reset")
        return httpx.Response(201, json=session())

    books(fake, flaky)
    s = fake.client(tmp_path).sessions.run(
        "fine-tune-simulated", inputs={"model": "https://x.example/m", "data": "https://x.example/d"}
    )
    assert s.id == SESSION_ID
    assert len(tries) == 3
    assert len(set(tries)) == 1
    assert fake.sleeps == [2, 4]
    assert list((tmp_path / "config" / "pending").glob("*.key")) == []


def test_after_giving_up_the_next_call_reuses_the_key_so_it_cannot_book_twice(fake, tmp_path):
    keys = []
    fail = {"on": True}

    def maybe(request):
        keys.append(request.headers["Idempotency-Key"])
        if fail["on"]:
            raise httpx.ConnectError("reset")
        return httpx.Response(200, json=session())

    books(fake, maybe)
    inputs = {"model": "https://x.example/m", "data": "https://x.example/d"}
    with pytest.raises(errors.NetworkError, match="won't book twice"):
        fake.client(tmp_path).sessions.run("fine-tune-simulated", inputs=inputs)
    assert len(list((tmp_path / "config" / "pending").glob("*.key"))) == 1
    fail["on"] = False
    fake.client(tmp_path).sessions.run("fine-tune-simulated", inputs=inputs)
    assert len(set(keys)) == 1
    assert list((tmp_path / "config" / "pending").glob("*.key")) == []


def test_another_request_gets_another_key(fake, tmp_path):
    keys = []
    books(fake, lambda r: (keys.append(r.headers["Idempotency-Key"]), httpx.Response(201, json=session()))[1])
    client = fake.client(tmp_path)
    inputs = {"model": "https://x.example/m", "data": "https://x.example/d"}
    client.sessions.run("fine-tune-simulated", inputs=inputs, max_spend_paise=100)
    client.sessions.run("fine-tune-simulated", inputs=inputs, max_spend_paise=200)
    assert keys[0] != keys[1]


def test_list_pages_through_and_stops_at_the_limit(fake, tmp_path):
    def page(request):
        cursor = request.url.params.get("cursor")
        if cursor is None:
            return httpx.Response(200, json={"sessions": [session(id=new_id()) for _ in range(3)], "nextCursor": "c2"})
        return httpx.Response(200, json={"sessions": [session(id=new_id()) for _ in range(3)]})

    fake.route("GET", "/v1/sessions", page)
    client = fake.client(tmp_path)
    assert len(client.sessions.list(limit=2)) == 2
    assert len(client.sessions.list(limit=10)) == 6
    assert len(fake.calls("GET", "/v1/sessions")) == 3  # the first list stopped before asking for the second page


def test_me_credit_and_availability_read_the_api(fake, tmp_path):
    fake.route(
        "GET",
        "/v1/me",
        lambda r: httpx.Response(
            200,
            json={
                "id": new_id(),
                "status": "active",
                "createdAt": "2026-10-14T10:00:00Z",
                "signInMethods": [],
                "unreadNotifications": 0,
                "email": "a@example.com",
            },
        ),
    )
    fake.route(
        "GET",
        "/v1/wallet",
        lambda r: httpx.Response(200, json={"availablePaise": 50000, "balancePaise": 50000, "reservedPaise": 0}),
    )
    client = fake.client(tmp_path)
    assert client.me().email == "a@example.com"
    assert client.credit().available_paise == 50000


def test_a_read_that_fails_in_the_network_is_tried_again_twice(fake, tmp_path):
    calls = []

    def flaky(request):
        calls.append(1)
        if len(calls) < 3:
            return httpx.Response(502, text="bad gateway")
        return httpx.Response(200, json={"availablePaise": 1, "balancePaise": 1, "reservedPaise": 0})

    fake.route("GET", "/v1/wallet", flaky)
    assert fake.client(tmp_path).credit().balance_paise == 1
    assert fake.sleeps == [0.5, 1.0]
    fake.route("GET", "/v1/wallet", lambda r: httpx.Response(502, text="x"))
    with pytest.raises(errors.ServerError):
        fake.client(tmp_path).credit()


def test_a_lost_answer_then_a_rerun_books_once_with_the_same_upload_and_key(fake, tmp_path):
    from test_upload import Storage

    storage = Storage(fake, {"a.txt": 3})
    # Once the upload is ready, opening it again with its key answers with it as it stands: ready.
    opened = {"n": 0}

    def reopen(request):
        opened["n"] += 1
        storage.opens.append(request.headers.get("Idempotency-Key"))
        return httpx.Response(
            200 if opened["n"] > 1 else 201, json=storage.detail("ready" if storage.completed else "pending")
        )

    fake.route("POST", "/v1/uploads", reopen)
    keys, sent = [], {"lost": True}

    def booking(request):
        keys.append(request.headers["Idempotency-Key"])
        if sent["lost"]:
            raise httpx.ReadError("the answer never came")
        return httpx.Response(200, json=session())

    books(fake, booking)
    folder = tmp_path / "model"
    folder.mkdir()
    (folder / "a.txt").write_bytes(b"abc")
    inputs = {"model": folder, "data": "https://x.example/d"}

    with pytest.raises(errors.NetworkError, match="won't book twice"):
        fake.client(tmp_path).sessions.run("fine-tune-simulated", inputs=inputs)
    puts = len(storage.puts)
    first = body(fake.calls("POST", "/v1/sessions")[0])
    # The upload's record and the booking's key are both still there, for the next run.
    assert list((tmp_path / "config" / "uploads").glob("*.json"))
    assert len(list((tmp_path / "config" / "pending").glob("*.key"))) == 1

    sent["lost"] = False
    s = fake.client(tmp_path).sessions.run("fine-tune-simulated", inputs=inputs)
    assert s.id == SESSION_ID
    assert len(set(keys)) == 1, "every try, in both runs, carried the same key"
    assert len(storage.puts) == puts, "the second run sent nothing to storage"
    assert storage.opens[0] == storage.opens[-1], "the upload was opened again with its own key"
    assert body(fake.calls("POST", "/v1/sessions")[-1]) == first, "the same upload id, the same request"
    assert list((tmp_path / "config" / "uploads").glob("*.json")) == []
    assert list((tmp_path / "config" / "pending").glob("*.key")) == []


def test_the_old_budget_name_still_works_for_a_release_with_a_warning(fake, tmp_path):
    books(fake)
    inputs = {"model": "https://x.example/m", "data": "https://x.example/d"}
    with pytest.warns(DeprecationWarning, match="max_spend_paise"):
        fake.client(tmp_path).sessions.run("fine-tune-simulated", inputs=inputs, max_spend=300)
    assert body(fake.calls("POST", "/v1/sessions")[-1])["maxSpendPaise"] == 300
    with pytest.raises(errors.UsageError, match="max_spend_paise only"), pytest.warns(DeprecationWarning):
        fake.client(tmp_path).sessions.run("fine-tune-simulated", inputs=inputs, max_spend=300, max_spend_paise=400)
