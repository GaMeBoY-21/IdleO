# 0030. The Python SDK: a generated core, a thin hand-written layer, exceptions from the problem catalog, and the command line's key and files

- **Status:** Accepted
- **Date:** 2026-10-14

Sources:
- docs/plan/product-surface.md section 5 (the Python SDK) and section 13 (build order)
- ADRs 0018 (the API contract and the generated clients), 0019 and 0022 (inputs, uploads and what a renter brings), 0025 (API keys, the command line, the limits), 0029 (the public docs)
- docs/prompts/python-sdk.md

## Context

People who script sessions, and people working in notebooks, want Python. The command line (ADR 0025) already does everything a renter does: it signs in, uploads a folder in parts and carries on after a failure, books with an Idempotency-Key it keeps until the API answers, follows the live stream, reads logs by cursor and downloads outputs part by part. The SDK must behave the same way, and must not become a second place where those rules are decided differently.

The gate: on the local stack, a plain script and a Jupyter notebook can run `client.sessions.run(...)` with a model folder and a data file, follow the logs, wait, and download the adapter.

Constraints:
- **One implementation per rule** (CLAUDE.md): the upload steps, the key lookup and the problem-to-exit-code meanings already exist in the command line; the SDK follows them, and a test or a shared file format says where they must agree.
- **The contract is the source:** `api/openapi.yaml` and `api/problems.json` change, and a client that copied them by hand drifts.
- **We provide the GPU; renters bring their own model, data and code** (ADR 0022): the SDK's example and docs name no model of ours.

## Decision

### A generated core and a thin hand-written layer

- **Where:** `sdk/python/`, the package `idleo` (its name is `idleo/_brand.py`, one constant, as the CLI's is `src/brand.rs`). Python 3.10 or newer, `uv` for the project (3.12 pinned for development, 3.10 and 3.13 in CI), `httpx` for HTTP, pydantic v2 for the models, `keyring` to read the command line's keychain entry. Not published to PyPI yet (the deployment round).
- **Generated** (`idleo/_generated/`, committed, by `scripts/generate.py`): `models.py`, every request and answer model from `api/openapi.yaml` (without `/admin/v1`, which no client of ours calls, and only the schemas the rest reaches) by `datamodel-code-generator`; and `errors.py`, one exception class per problem type from `api/problems.json`. CI runs `scripts/generate.py --check`, which fails when what is committed differs from what the contract makes, as the web app's generated types and the CLI's do.
- **Hand-written** (about 1,900 lines with their comments, strict types): the HTTP transport, the key and config lookup, the upload, the session and its live view, the outputs download, and the client that ties them. The low-level calls are not generated: the API has about fifteen calls the SDK makes, each a path and a model in one place, so generating a client for the other hundred would only add code nothing calls. Instead `tests/test_api_contract.py` lists every call the SDK makes and fails if the contract loses or changes one (the CLI's `tests/api_contract.rs`, in Python).
- **Synchronous only.** One sync client. An async one would double the surface for people who have not asked; `httpx` makes it a later, mechanical addition. **Open question:** whether anyone needs it.

### Exceptions are the problem catalog

- Every problem type is a class named from its slug (`insufficient-credit` is `InsufficientCredit`, `no-gpu-free` is `NoGpuFree`, `rate-limited` is `RateLimited`), generated, all under `IdleoError`, importable from `idleo.errors` (and `idleo.InsufficientCredit`). Each has `slug`, `status` and `title`; an instance has `retry_after` (every 429 carries one, ADR 0025), `fields` (a 400 about invalid fields) and the API's whole `problem`.
- **The same exit meanings as the command line,** from one table (`exit_code`, copied from `cli/src/error.rs`: 2 usage, 3 not enough credit, 4 no GPU free, 5 not signed in, 6 rate limited, 7 network, 1 anything else), so a script that moves from `idleo` to Python branches on the same things. A test lists the table; the problem types it names must be in the catalog.
- A type this version has not seen is still an `IdleoError` with its slug, so a newer API never breaks an older SDK with an unexplained crash.
- The SDK's own failures are `NotSignedIn`, `NetworkError`, `ServerError` (a 5xx with no problem type), `UsageError` (the arguments don't fit the template, found before anything is uploaded) and `WaitTimeout`.
- **`Session.wait()` raises by default** (`check=True`) when the session settled without completing: `NoGpuFree` if no GPU became free (the CLI's exit 4), otherwise `SessionNotCompleted`, which carries the session. A script that goes on to `outputs.download()` after a failed run would otherwise fail somewhere less clear. `wait(check=False)` returns the settled session either way.

### One key, one config folder, shared with the command line

- **The key** is read in the command line's order (docs/plan/product-surface.md 4.1): the `api_key` argument, `IDLEO_API_KEY`, the operating system's keychain entry `idleo login` made (service `idleo`, account the API address, a JSON value), then `credentials.json` in the config folder, kept per API address. `IDLEO_NO_KEYCHAIN=1` skips the keychain. The SDK only reads: signing in and out stays with the command line, which has the browser approval (RFC 8628 shaped, ADR 0025).
- **The config folder** is `IDLEO_CONFIG_DIR`, else the system's usual place (`~/Library/Application Support/idleo`, `$XDG_CONFIG_HOME` or `~/.config`, `%APPDATA%`); the API's address is the argument, `IDLEO_API_URL`, else the local stack.
- **Uploads and bookings use the command line's files and formats there:** an unfinished upload is `uploads/<fingerprint>.json` (the upload's id, its Idempotency-Key and each file's parts with their ETags), the fingerprint the same hash of the name, paths, sizes and modification times; a booking's Idempotency-Key is `pending/<hash>.key`, kept until the API answers. So running the same upload again carries on from the last part storage took, and so does the command line carrying on an SDK upload of the same files (and the reverse). Remembering is a convenience: every failure to read or write is swallowed and the next run starts over.
- **Caveat:** on macOS the SDK and the command line read the same Keychain entry. On Linux and Windows the two keychain libraries name their entries differently, so the SDK may not see a key the command line stored there; the credentials file, which `idleo login` writes when the keychain refuses, works everywhere, and `IDLEO_API_KEY` always does. **Open question:** whether the command line should write the file as well as the keychain, or the SDK should learn the Rust library's entry names.

### The same upload, booking and live view

- **`client.sessions.run(template, inputs=, params=, presets=, gpu_memory_gb=, max_spend=, max_runtime=)`** checks the arguments against the template first (`GET /v1/templates`: the input names and kinds, each parameter's type, each question's options and its usual answer, the GPU memory classes), so nothing is uploaded for a booking that would be refused. Then it uploads each local input (a folder keeps its paths, up to 1,000 files, parts of the size the API says, three at a time, a failed part tried three times with backoff, an expired link replaced, ETags remembered), books with an Idempotency-Key kept until the API answers (tried three times with the same key when no answer comes), and returns the `Session`. It does not wait. `create()` books with links and existing upload ids only and never uploads.
- **Money is integer paise everywhere** (`max_spend=200_00` is ₹200). `idleo.rupees(paise)` writes it for people, with Indian grouping, checked against `api/money-format.json`; `idleo.paise("₹1,200.05")` reads it exactly.
- **The live view** is the one Server-Sent Events stream (`GET /v1/sessions/{id}/events`, ADR 0019). It is read on a thread of its own with a two-second heartbeat, so logs are also read on a quiet stream and `wait(timeout=)` can give up; a dropped stream is asked about (`GET /v1/sessions/{id}`) and opened again, six failures in a row end it. `session.logs()` reads pages by cursor (a cursor read does not count against the request limit, ADR 0025), gives whole lines even when a chunk cuts one, and with `follow=True` ends only when the session is settled and its log complete.
- **Outputs** download file by file into a `NAME.idleo-part` file that carries on from where it stopped by a range request, a whole file is left alone, a link that has expired is replaced once by asking for the list again, and a path that would leave the folder is refused.
- **Notebooks without a dependency on them:** when IPython is already loaded and its shell is a notebook's, `wait()`, `logs(follow=True)` and uploads show one status line (the state, the GPU and what it has cost so far) that updates in place; anywhere else they are silent unless `progress=True`.

### Docs and checks

- **The docs site's SDK page** (`docs/site/generated/sdk.md`) is made from the SDK's own docstrings by `scripts/docs.py`, committed, with a test that fails when it differs (the CLI's reference works the same way); the site's build renders it.
- **CI** (`.github/workflows/sdk.yml`): ruff format and lint, mypy `--strict`, the tests on Python 3.10 and 3.13, and the generated-layer check. The end-to-end script (scripts/e2e/agent-online.sh) runs the gate against the real stack with the key the command line just stored.

## Alternatives considered

- **A fully generated client** (`openapi-python-client`). Rejected: it generates a method for every operation, admin and host ones included, with its own idea of errors and none of the upload, resume, idempotency and follow logic, which would then be written around it. Its generated names for the problem catalog are less readable than the catalog's own slugs.
- **Hand-written models.** Rejected: they would drift from the contract, which is the thing the drift check exists for.
- **Calling the command line from Python.** Rejected: a subprocess is a poor fit for a notebook (no iterator, no exceptions, no progress), and the SDK would depend on a binary being installed.
- **Making the SDK sign people in.** Rejected for now: the browser approval is the command line's, and a notebook on a remote machine can already use an API key (`IDLEO_API_KEY`, made on the Account page).
- **An async client first.** Rejected: see above.
- **`wait()` that never raises.** Rejected: a failed session whose caller carries on to download outputs fails later with a less clear error. `check=False` is there for those who want to look first.

## Consequences

- A renter can script the whole product from Python, with the same sign-in, the same uploads and the same exit meanings as the command line, and the exceptions, models and docs cannot drift from the API's contract: the CI checks fail first.
- The upload logic now exists twice (Rust and Python), as the web app's does in TypeScript. They agree on the wire (the API) and on the files they leave (fingerprints and records); a test in each language holds its side.
- A new problem type needs nothing by hand: regenerate, and the class exists. A new exit meaning needs the one table in `idleo/_problem.py` and the command line's, which a test compares.
- **Open question:** publishing to PyPI and the package's licence (the deployment round; the repository has none yet); an async client; the keychain entry names on Linux and Windows.

## Addendum (2026-10-14)

- **Money arguments say their unit:** `max_spend` is now `max_spend_paise`. The old name still works for one release, with a `DeprecationWarning`, and giving both with different values is a `UsageError`.
- **`IDLEO_NO_KEYCHAIN` means the same in the SDK and the CLI:** only `1` or `true` (any case) turns the keychain off; `0`, `false` or anything else leaves it on. Before, the CLI took anything but `0` and the SDK anything at all.
- **A booking's key comes from what was asked for** (ADR 0025's addendum), and an upload's record is kept until the booking is answered, in the SDK as in the CLI.
- `sdk.yml` also runs when `cli/src/error.rs` changes, since a test reads the exit table from it.
