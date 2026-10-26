# scanner

The image scanner (ADR 0042): checks a renter's own container image on our server before it can reach a host. It takes
work from the control plane's internal API, reports facts, and the control plane decides the verdict.

One check: resolve the reference to its `linux/amd64` manifest; read the config; tell the control plane (which may answer
from a cached result for the digest, or stop it at a limit); download the blobs as they are into an OCI layout on the
scratch disk, each checked against its digest and size; read every layer as a stream, never unpacked, through a tar
reader whose every size is capped (`src/tar.rs`, `src/layers.rs`), hashing every ELF file in every layer; run Trivy on the
layout for vulnerabilities and secrets; report; delete the scratch folder. A check that can't finish is reported as a
failure, never a pass.

## Running it

It runs in its container (`Dockerfile`: Trivy 0.75.0 pinned by digest, our binary beside it), as user 10001, on a
read-only root, with every capability dropped. Give it a scratch volume (`/scratch`, which bounds what a check can
download: at least the largest image allowed plus 2 GB) and a cache volume for Trivy's database (`/cache`). It needs no
database, no storage keys, and no network but the registries and the control plane's internal port.

| Variable | What |
| --- | --- |
| `SCANNER_CONTROL_PLANE` | The control plane's internal address, `http://<host>:<images.scanner.port>/` |
| `SCANNER_TOKEN_FILE` or `SCANNER_TOKEN` | Its token for the internal API (`images.scanner.token`), 32 characters or more |
| `SCANNER_SCRATCH` | The scratch disk (`/scratch`) |
| `SCANNER_TRIVY`, `SCANNER_TRIVY_CACHE` | Trivy, and its cache (`/cache/trivy`) |
| `SCANNER_TRIVY_OFFLINE` | `1` to use the cached database only (tests) |
| `SCANNER_TRIVY_TIMEOUT_SECONDS` | Trivy's limit for one image (1800) |
| `SCANNER_ALLOW_PRIVATE` | Development only: registry hosts that may be at a private address and plain HTTP |
| `SCANNER_KEEP_FREE_BYTES` | Room kept free on the scratch disk beyond a check's download (2 GB) |
| `SCANNER_POLL_SECONDS` | How often it asks for work when there's none (5) |
| `SCANNER_NAME` | How it names itself in the control plane's log (the host name) |

`idleo-scanner work` takes checks until stopped (the container's default). `idleo-scanner check REFERENCE` checks one image
here and prints each step with its time and the result, for measuring; `SCANNER_CHECK_USERNAME` and `SCANNER_CHECK_TOKEN`
give a private one's login.

The development stack runs it as compose.yaml's `scanner` service (`stack_start_scanner` in scripts/lib/stack.sh).

## Checks

`scripts/checks/scanner.sh`: fmt, clippy (pedantic), the tests (`tests/check.rs` runs whole checks against a registry in
the test, with an image for each refusal), `cargo deny`, and the container built and run once read-only.
