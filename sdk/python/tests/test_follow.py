"""Following a session: the live view, waiting, logs a line at a time, and how a session that failed raises."""

from __future__ import annotations

import json

import httpx
import pytest
from conftest import problem, session

from idleo import errors
from idleo._events import LogsEvent, SessionEvent, SseParser
from idleo.session import GIVE_UP_AFTER

ID = "01a10e8e-9c65-7789-a526-473491c82218"


def frame(name: str, data: dict) -> str:
    return f"event: {name}\ndata: {json.dumps(data)}\n\n"


def sse(*frames: str) -> httpx.Response:
    return httpx.Response(200, content="".join(frames).encode(), headers={"content-type": "text/event-stream"})


def log_chunk(seq: int, text: str) -> dict:
    return {"attempt": 1, "seq": seq, "text": text}


def get(fake, tmp_path, **over):
    fake.route("GET", f"/v1/sessions/{ID}", lambda r: httpx.Response(200, json=session(**over)))
    return fake.client(tmp_path).sessions.get(ID)


# The parser


def test_a_session_event_is_the_whole_session():
    events = SseParser().push(frame("session", session("RUNNING")))
    assert len(events) == 1
    assert isinstance(events[0], SessionEvent)
    assert events[0].session.state == "RUNNING"


def test_an_event_cut_anywhere_is_put_back_together():
    text = frame("session", session("RUNNING"))
    parser = SseParser()
    assert parser.push(text[:37]) == []
    assert len(parser.push(text[37:])) == 1


def test_keep_alives_and_unknown_events_are_ignored():
    text = (
        ": keep-alive\n\n"
        + frame("weather", {})
        + frame("logs", {"attempt": 1, "latestSeq": 3})
        + frame("session", session())
    )
    events = SseParser().push(text)
    assert [type(e) for e in events] == [LogsEvent, SessionEvent]
    assert events[0].latest_seq == 3


def test_windows_line_endings_are_fine():
    assert len(SseParser().push(frame("session", session()).replace("\n", "\r\n"))) == 1


# Events and waiting


def test_events_end_when_the_session_settles_and_the_session_is_updated(fake, tmp_path):
    s = get(fake, tmp_path)
    fake.route(
        "GET",
        f"/v1/sessions/{ID}/events",
        lambda r: sse(
            frame("session", session("RUNNING", accruedPaise=2)),
            frame("logs", {"attempt": 1, "latestSeq": 1}),
            frame("session", session("COMPLETED", True, chargedPaise=6)),
            frame("session", session("RUNNING")),  # never read: the stream is over at settled
        ),
    )
    seen = [type(e).__name__ for e in s.events()]
    assert seen == ["SessionEvent", "LogsEvent", "SessionEvent"]
    assert (s.state, s.settled, s.charged_paise) == ("COMPLETED", True, 6)


def test_a_dropped_stream_is_asked_about_and_opened_again(fake, tmp_path):
    s = get(fake, tmp_path)
    opened = []

    def events(request):
        opened.append(1)
        if len(opened) == 1:
            return sse(frame("session", session("RUNNING")))
        return sse(frame("session", session("COMPLETED", True)))

    fake.route("GET", f"/v1/sessions/{ID}/events", events)
    fake.route("GET", f"/v1/sessions/{ID}", lambda r: httpx.Response(200, json=session("RUNNING")))
    s.wait()
    assert len(opened) == 2
    assert s.settled


def test_wait_returns_the_settled_session_with_its_final_figures(fake, tmp_path):
    s = get(fake, tmp_path)
    fake.route(
        "GET", f"/v1/sessions/{ID}/events", lambda r: sse(frame("session", session("COMPLETED", True, chargedPaise=6)))
    )
    assert s.wait() is s
    assert s.charged_paise == 6


def test_wait_on_a_settled_session_opens_no_stream(fake, tmp_path):
    s = get(fake, tmp_path, state="COMPLETED", settled=True)
    s.wait()
    assert fake.calls("GET", f"/v1/sessions/{ID}/events") == []


def test_a_stream_that_will_not_open_gives_up_after_a_few_tries(fake, tmp_path):
    s = get(fake, tmp_path)
    fake.route("GET", f"/v1/sessions/{ID}/events", lambda r: httpx.Response(503, text="down"))
    with pytest.raises(errors.ServerError):
        s.wait()
    assert len(fake.calls("GET", f"/v1/sessions/{ID}/events")) == GIVE_UP_AFTER


def test_a_refusal_on_the_stream_is_raised_at_once(fake, tmp_path):
    s = get(fake, tmp_path)
    fake.route("GET", f"/v1/sessions/{ID}/events", lambda r: problem("not-found", 404, "no such session"))
    with pytest.raises(errors.NotFound):
        s.wait()


def test_wait_with_a_timeout_raises_and_leaves_the_session_going(fake, tmp_path):
    s = get(fake, tmp_path)
    import threading

    gate = threading.Event()

    class Quiet(httpx.SyncByteStream):
        def __iter__(self):
            gate.wait(5)
            yield b""

    fake.route("GET", f"/v1/sessions/{ID}/events", lambda r: httpx.Response(200, stream=Quiet()))
    try:
        with pytest.raises(errors.WaitTimeout):
            s.wait(timeout=0.05)
    finally:
        gate.set()
    assert issubclass(errors.WaitTimeout, TimeoutError)
    assert not s.settled


def test_a_session_that_did_not_complete_raises_by_how_it_ended(fake, tmp_path):
    failed = get(fake, tmp_path, state="FAILED", settled=True, errorCode="agent_lost")
    with pytest.raises(errors.SessionNotCompleted, match="agent lost") as raised:
        failed.wait()
    assert raised.value.session is failed
    assert raised.value.exit_code == 1
    assert failed.wait(check=False) is failed
    nohost = get(fake, tmp_path, state="NO_HOST_FREE", settled=True)
    with pytest.raises(errors.NoGpuFree):
        nohost.wait()
    assert get(fake, tmp_path, state="COMPLETED", settled=True).wait().state == "COMPLETED"


def test_stop_and_cancel_post_and_take_the_new_state(fake, tmp_path):
    s = get(fake, tmp_path, state="RUNNING")
    fake.route("POST", f"/v1/sessions/{ID}/stop", lambda r: httpx.Response(200, json=session("STOPPING")))
    fake.route("POST", f"/v1/sessions/{ID}/cancel", lambda r: httpx.Response(200, json=session("CANCELLED", True)))
    assert s.stop().state == "STOPPING"
    assert json.loads(fake.calls("POST", f"/v1/sessions/{ID}/stop")[0].content) == {"checkpointFirst": True}
    assert s.stop(checkpoint_first=False).state == "STOPPING"
    assert s.cancel().settled


def test_progress_is_silent_outside_a_notebook_unless_asked(fake, tmp_path, capsys):
    s = get(fake, tmp_path, state="COMPLETED", settled=True, chargedPaise=6)
    s.wait()
    assert capsys.readouterr().err == ""
    s.wait(progress=True)
    assert "completed  (₹0.06 so far)" in capsys.readouterr().err


# Logs


def logs(fake, pages: list[dict]):
    """Pages by cursor: the first has no cursor; each says where the next starts."""
    served = []

    def handler(request):
        cursor = request.url.params.get("cursor")
        index = 0 if cursor is None else int(cursor)
        served.append(cursor)
        page = pages[min(index, len(pages) - 1)]
        return httpx.Response(200, json=page)

    fake.route("GET", f"/v1/sessions/{ID}/logs", handler)
    return served


def test_logs_come_a_line_at_a_time_even_when_chunks_cut_lines(fake, tmp_path, monkeypatch):
    monkeypatch.setattr("idleo.session.LOG_PAGE", 2)  # pages of two chunks, so the reader goes on to the next
    s = get(fake, tmp_path, state="COMPLETED", settled=True)
    logs(
        fake,
        [
            {
                "chunks": [log_chunk(0, "loading the mo"), log_chunk(1, "del\nepoch 1\nepo")],
                "complete": False,
                "nextCursor": "1",
            },
            {"chunks": [log_chunk(2, "ch 2\r\ndone")], "complete": True, "nextCursor": "2"},
        ],
    )
    assert list(s.logs()) == ["loading the model", "epoch 1", "epoch 2", "done"]


def test_a_log_is_never_read_twice(fake, tmp_path, monkeypatch):
    monkeypatch.setattr("idleo.session.LOG_PAGE", 1)
    s = get(fake, tmp_path, state="COMPLETED", settled=True)
    served = logs(
        fake,
        [
            {"chunks": [log_chunk(0, "a\n")], "complete": False, "nextCursor": "1"},
            {"chunks": [log_chunk(1, "b\n")], "complete": True, "nextCursor": "2"},
            {"chunks": [], "complete": True, "nextCursor": "2"},
        ],
    )
    assert list(s.logs()) == ["a", "b"]
    assert served == [None, "1"]


def test_following_a_running_session_prints_lines_as_they_come_and_ends_with_the_log(fake, tmp_path):
    s = get(fake, tmp_path, state="RUNNING")
    logs(
        fake,
        [
            {"chunks": [log_chunk(0, "start\n")], "complete": False, "nextCursor": "1"},
            {"chunks": [log_chunk(1, "saving the adapter\n")], "complete": True, "nextCursor": "2"},
        ],
    )
    fake.route(
        "GET",
        f"/v1/sessions/{ID}/events",
        lambda r: sse(frame("logs", {"attempt": 1, "latestSeq": 1}), frame("session", session("COMPLETED", True))),
    )
    assert list(s.logs(follow=True)) == ["start", "saving the adapter"]
    assert s.settled


def test_a_page_that_isnt_full_ends_a_read_so_no_empty_page_is_asked_for(fake, tmp_path):
    s = get(fake, tmp_path, state="RUNNING")
    served = logs(fake, [{"chunks": [log_chunk(0, "a\n")], "complete": False, "nextCursor": "1"}])
    assert list(s.logs()) == ["a"]
    assert served == [None], "one read: a page short of the limit means there's nothing more yet"


def test_following_goes_on_through_a_log_read_refused_for_the_limit(fake, tmp_path):
    s = get(fake, tmp_path, state="RUNNING")
    reads = {"n": 0}

    def handler(request):
        reads["n"] += 1
        if reads["n"] == 1:
            return problem("rate-limited", 429, "too many log reads", **{})
        return httpx.Response(200, json={"chunks": [log_chunk(0, "done\n")], "complete": True})

    fake.route("GET", f"/v1/sessions/{ID}/logs", handler)
    fake.route(
        "GET",
        f"/v1/sessions/{ID}/events",
        lambda r: sse(frame("logs", {"attempt": 1, "latestSeq": 0}), frame("session", session("COMPLETED", True))),
    )
    assert list(s.logs(follow=True)) == ["done"]


def test_a_log_that_never_completes_after_the_session_settles_is_an_error(fake, tmp_path):
    s = get(fake, tmp_path, state="COMPLETED", settled=True)
    logs(fake, [{"chunks": [], "complete": False}])
    with pytest.raises(errors.IdleoError, match="last log lines didn't arrive"):
        list(s.logs(follow=True))
    assert len(fake.sleeps) == 30
