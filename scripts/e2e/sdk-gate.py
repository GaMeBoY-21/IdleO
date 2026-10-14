"""The Python SDK's gate (ADR 0030), run by scripts/e2e/agent-online.sh against the live stack as a person would use it:
a plain script, with the key `idleo login` stored (nothing passed in), a model folder and examples uploaded, the
simulated fine-tune booked with a preset, its logs followed live, and the adapter downloaded.

    sdk-gate.py WORK_DIR EXPECTED_EMAIL

WORK_DIR holds my-model/ and train.jsonl, which the command line's scenario made. One JSON line goes to stdout for the
script to check against the API and the ledger; progress goes to stderr.
"""

from __future__ import annotations

import json
import sys
import time
import uuid
from pathlib import Path

import idleo
from idleo import Client, errors
from idleo._events import SessionEvent


def main(work: Path, email: str) -> None:
    client = Client()

    # Signed in with the command line's stored key, and the live API's problems are the SDK's exceptions.
    assert client.me().email == email, "the stored key signs in as somebody else"
    assert idleo.rupees(client.credit().available_paise).startswith("₹"), "credit isn't rupees"
    try:
        client.sessions.get(uuid.uuid4())
        raise AssertionError("a session nobody has was found")
    except errors.NotFound:
        pass
    try:
        Client(api_key="idleo_not_a_real_key").me()
        raise AssertionError("a made-up key signed in")
    except errors.Unauthenticated as error:
        assert error.exit_code == 5
    try:
        client.sessions.run("no-such-template")
        raise AssertionError("an unknown template was booked")
    except errors.UsageError as error:
        assert error.exit_code == 2

    started = time.monotonic()
    s = client.sessions.run(
        "fine-tune-simulated",
        inputs={"model": work / "my-model", "data": work / "train.jsonl"},
        presets={"thoroughness": "standard"},
        max_spend_paise=200_00,
    )
    booked = time.monotonic() - started
    to_running = None
    for event in s.events():
        if isinstance(event, SessionEvent) and event.session.state == "RUNNING":
            to_running = time.monotonic() - started
            break
    lines = []
    for line in s.logs(follow=True):
        print(line, file=sys.stderr)
        lines.append(line)
    s.wait()
    assert s.state == "COMPLETED", s.state
    assert any("saving the adapter" in line for line in lines), "the log never said it saved the adapter"

    listed = s.outputs.list()
    sizes = {f.path: f.size_bytes for f in listed}
    out = work / "sdk-out"
    downloaded = s.outputs.download(out)
    adapter = out / "adapter_model.safetensors"
    assert adapter in downloaded, "the adapter wasn't downloaded"
    assert adapter.stat().st_size == sizes["adapter_model.safetensors"], "the adapter's size differs from the list's"

    print(
        json.dumps(
            {
                "session": s.id,
                "state": s.state,
                "chargedPaise": s.charged_paise,
                "bookedSeconds": round(booked, 1),
                "toRunningSeconds": round(to_running, 1) if to_running is not None else None,
                "adapterBytes": adapter.stat().st_size,
                "listedBytes": sizes["adapter_model.safetensors"],
                "logLines": len(lines),
                "listed": sorted(sizes),
            }
        )
    )


if __name__ == "__main__":
    main(Path(sys.argv[1]), sys.argv[2])
