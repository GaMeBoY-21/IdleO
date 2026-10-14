# idleo, the IdleO Python SDK

Run, follow and download sessions from a Python script or a Jupyter notebook. It works like the command line (`cli/`): the same sign-in, the same uploads that carry on after a failure, and the same meaning for what goes wrong. The decisions are in [ADR 0030](../../docs/decisions/0030-python-sdk.md); the reference is the docs site's SDK page, made from the docstrings ([docs/site/generated/sdk.md](../../docs/site/generated/sdk.md)).

We provide the GPU; you bring your own model, data and code.

## Install

From a copy of this repository (it isn't on PyPI yet):

```sh
pip install ./sdk/python        # or: uv add ./sdk/python
```

It needs Python 3.10 or newer.

## Sign in

`Client()` finds your API key in this order:

1. `Client(api_key="idleo_…")`;
2. the `IDLEO_API_KEY` environment variable (make a key on the Account page);
3. the key `idleo login` stored on this computer: the operating system's keychain, else `credentials.json` in the config folder (`IDLEO_CONFIG_DIR`, else `~/Library/Application Support/idleo` on a Mac, `$XDG_CONFIG_HOME/idleo` or `~/.config/idleo` on Linux, `%APPDATA%\idleo` on Windows).

`IDLEO_NO_KEYCHAIN=1` skips the keychain. The API's address is `Client(api_url=…)`, else `IDLEO_API_URL`, else the local stack, `http://localhost:8080`. With no key, every call raises `NotSignedIn`.

On macOS the SDK reads the same Keychain entry as the command line. On Linux and Windows the two libraries name keychain entries differently, so use the credentials file or `IDLEO_API_KEY` there if `Client()` can't find your key.

## Run a session

```python
from idleo import Client

client = Client()
s = client.sessions.run(
    "fine-tune-simulated",
    inputs={"model": "./my-model", "data": "./train.jsonl"},
    presets={"thoroughness": "standard"},
    max_spend=200_00,  # paise: ₹200
)
for line in s.logs(follow=True):
    print(line)
s.wait()
s.outputs.download("./out")
```

- `inputs` names each of the template's inputs: a folder or file on your computer (uploaded first, paths kept, carried on if a first try was cut off), an `https://` link the job fetches itself, or the id of an upload you made before. `client.templates.list()` says what each template takes: its inputs, parameters (`params={"epochs": 2}`) and questions (`presets=`).
- `run()` checks your arguments against the template before it uploads anything, books with an Idempotency-Key it keeps until the API answers, and returns a `Session` without waiting. `client.sessions.create()` books with links and existing uploads only.
- `s.wait()` returns the settled session, or raises if it didn't complete: `NoGpuFree`, or `SessionNotCompleted` (which carries the session). `s.wait(check=False)` never raises for that.
- Money is whole paise everywhere. `idleo.rupees(12_345_678)` is `"₹1,23,456.78"`.
- In a Jupyter notebook, `wait()`, `logs(follow=True)` and uploads show a status line that updates in place. Anywhere else they are silent unless you pass `progress=True`.

## When it fails

Every problem the API names is an exception under `idleo.errors.IdleoError`, named from the problem type: `InsufficientCredit`, `NoGpuFree`, `RateLimited` (with `retry_after`), `InputNotAccepted` and the rest (`docs/api/problems.md`). Each has an `exit_code`, the one the command line would exit with.

```python
from idleo import errors

try:
    s = client.sessions.run(...)
except errors.InsufficientCredit:
    ...
except errors.RateLimited as error:
    time.sleep(error.retry_after or 60)
```

## Develop

From `sdk/python/`, with [uv](https://docs.astral.sh/uv/):

```sh
uv sync                                  # the pinned Python (3.12) and the dev tools
uv run ruff format --check . && uv run ruff check .
uv run mypy idleo                        # strict on the hand-written layer
uv run pytest                            # unit tests against a fake API and storage
uv run python scripts/generate.py --check   # the generated layer matches api/openapi.yaml and api/problems.json
uv run python scripts/docs.py --check       # the docs site's SDK page matches the docstrings
```

- `idleo/_generated/` is made by `scripts/generate.py` (models from `api/openapi.yaml`, exceptions from `api/problems.json`) and committed; never edit it. After an API change, run `uv run python scripts/generate.py` and review the diff.
- After a change to a public call or its docstring, `uv run python scripts/docs.py` rewrites `docs/site/generated/sdk.md`.
- `scripts/e2e/agent-online.sh` runs the gate against a real stack (`scripts/e2e/sdk-gate.py`), signed in with the key the command line has just stored.
