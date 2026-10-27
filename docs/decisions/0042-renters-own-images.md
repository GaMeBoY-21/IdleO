# 0042. Renters' own images

- **Status:** Accepted
- **Date:** 2026-10-26

## Context

Every job ran in our PyTorch image (ADR 0035). Many renters already have an image with their code, libraries and CUDA
version baked in. The technical design allows it: "custom images for verified renters only, scanned before first run".
A renter's image is untrusted software on a host's laptop. The sandbox (user 10001, a read-only root, every capability
dropped, the network rules, the encrypted scratch disk, ADRs 0035 and 0038) applies to it unchanged. This decision adds
the gate in front of it: a check on our server, before the image can reach a host.

## Decision

### Who and what

- Only a renter with a **verified PAN** (billing's `VerifiedPans`, which payouts implements, ADR 0040) may submit or
  book their own image (`verified-pan-required`, 403). Everyone else is told why, and that Payout details is where to
  verify.
- An image comes from a **public registry** (Docker Hub, GHCR, quay.io, NGC, any other with a public name) or a
  **private one, with a read-only token**. The token is sealed at rest (the shared kernel's `Sealer`, AES-256-GCM, under
  `images.registry-token.key`, the PAN key's pattern), opened only to pull, never logged, never shown back, and deleted
  `legal.retention.registry-tokens` (30 days, LEGAL(confirm)) after it was last used. A reference that names an
  address, `localhost`, a name without a dot or an `.internal` name is refused at submission, and the scanner resolves
  every registry name itself to public addresses only, on every request and redirect: a reference can never point the
  scanner at our own network.
- **Pinned by digest.** At the check the reference is resolved to the one `linux/amd64` manifest. Its digest is the only
  thing a session of it ever runs: the booking stores `registry/repository@sha256:…` and the host pulls exactly that. A
  new push to the tag is a new submission and a new check.
- **Limits**, all settings: one platform (`images.platform`, `linux/amd64`; a Mac's development stack runs arm64, which
  production refuses), at most 15 GB compressed and 127 layers, and at most 40 GB unpacked for a layer and 60 GB for the
  image (decimal GB, as registries count them).

### The check

- **The scanner** is `scanner/`, a Rust program in a container of its own built from Trivy's image pinned by digest
  (0.75.0): user 10001, a read-only root, every capability dropped, no database address, no storage keys, limits of its
  own (2 CPUs, 2 GB, 256 processes), its own scratch volume (which bounds what one check can download) and Trivy's cache.
  It sits on a network of its own and reaches the control plane only on `images.scanner.port`, a port the load balancer
  never routes, with its bearer token (`images.scanner.token`, 32 characters or more, compared in constant time). That
  port answers nothing else, and the public port answers `/internal/` with 404.
- **It works a queue it pulls from.** It claims the oldest check, with a claim token every later step names; its
  progress (every 20 seconds) keeps the claim. It says what the reference resolved to, and the control plane decides:
  the platform, the size and the layers against the limits, or a cached result for that digest, which answers the check
  at once.
- **Layers are never unpacked.** The blobs are downloaded as they are, each checked against its digest and its declared
  size, into an OCI layout on the scratch disk. Each layer is then read as a stream by a tar reader of our own whose every
  size is capped: what it decompresses to is counted against the per-layer and per-image caps (a layer that decompresses
  to far more than its size stops at the cap, `too_large_unpacked`), a GNU long name or a PAX header is read up to
  64 KiB, a path with a `..` part, a hard link to one, or a symbolic link that climbs above the image's root is refused
  (`unsafe_path`). Every ELF file in every layer is hashed with SHA-256, a file a later layer deleted included, and
  whether `/bin/sh` survives the whiteouts is noted. Trivy then reads the same layout for known vulnerabilities and what
  looks like a secret. Our own pass runs first, so Trivy only ever sees an image inside the caps.
- **The control plane decides every verdict** (`ImageJudge`, the one place): blocked for the wrong platform, a limit, a
  layer it can't read or a path outside the image, an image it can't pull, or **any ELF file whose hash is on the signed
  blocklist** (ADR 0041; its `hashes` and its `learned` builds). Warnings, never blocking: the critical and high counts
  with the full list (`GET /v1/images/{id}/vulnerabilities`, at most 5,000), each secret by its file and kind ("Your image
  seems to contain an AWS key in /app/.env. Anyone with this image can read it."; the secret itself is never kept), a
  root `USER` (we always run as 10001), and no `/bin/sh` (a typed command can't run, and booking one is refused). From
  the config: the CUDA version it needs (`NVIDIA_REQUIRE_CUDA`'s `cuda>=`, else `CUDA_VERSION`), its entrypoint and
  command, its working directory. Of its environment only those two variables are read.
- **Not checked is never a pass.** A check that crashes, runs past `images.check-deadline` (60 minutes) or loses its
  scanner `images.check-attempts` times is `failed`, reason `not_checked`; nothing was decided, and the image can't be
  booked until a check completes. A step from a scanner whose claim is gone is refused (409) and dropped. With no
  blocklist cached yet, a checked image waits: nothing is ready until a list has judged it.
- **Cached by digest, judged again.** What a check found is kept for its digest with every ELF hash. When the hosts
  module caches a newer blocklist (`BlocklistCached`, and a sweep every minute), every cached scan the list hasn't judged
  is judged again from its hashes, without downloading anything. One it blocks is blocked for every renter who has it;
  `ImageBlocked` cancels its sessions that haven't started (`image_blocked`, uncharged) and tells each owner (the locked
  `image-blocked` notification). A running session goes on; its runner judges its processes by the same list.
- An admin sees every submission by state, one with its findings and, for a blocklisted one, which file, and can block a
  digest for everyone or lift their own block, each audited with a reason.

### Matching and the host

- **CUDA:** the scheduler offers a session of a renter's image only to a host whose GPU's driver runs the CUDA version it
  needs, by the rule `MIN_NVIDIA_DRIVER` follows (minor version compatibility: each major from its X.0 floor), now kept
  once in `api/cuda-drivers.json`, which the agent's `drivers.rs` and the control plane's `CudaDrivers` both read. The
  filter is in `CandidateFinder`, so matching, quotes and the server's price ceiling agree. An image that names no CUDA
  runs on any host.
- **The pull:** at the attempt's start, never before. Pre-download (ADR 0035) never fetches one: the template names no
  image. A private image's token reaches the host only in that one `StartSession` as delivered (never in the stored
  command), is held in the runner's memory, and for its pull is a Docker config only root can read, in a folder of its
  own on the distribution's memory-backed `/run`, named to `nerdctl` alone by `DOCKER_CONFIG` and deleted after the pull:
  no environment holds it, no disk keeps it. The screen shows "Downloading your image · 3.1 of 8.4 GB" from the
  heartbeats. The pull has `images.pull-time-limit` (20 minutes): past it the attempt fails `image_pull_timed_out`,
  before minute 0, and the control plane's start timeout allows for it.
- **Deleted after the session.** A renter's image may hold their code, and a host's image store isn't encrypted, so the
  runner deletes it from containerd's store when the attempt ends, however it ended. The cost: a run again, even on the
  same host, downloads it again.
- **Billing:** the download is never charged and never counted against the job's maximum runtime. Minute 0 is when the
  job starts (ADR 0017), after the pull, as for our own images: one rule. The host isn't paid for the download either;
  the pull limit protects their bandwidth.
- **What runs:** the renter's command, run with `/bin/sh -c` in the image's own working directory, or, left empty, the
  image's own entrypoint and command; the job contract is the same (`SESSION_*`, `/work`, checkpoints, outputs).

### Surfaces

`POST /v1/images`, `GET /v1/images[/{id}]`, `GET /v1/images/{id}/vulnerabilities`, `DELETE /v1/images/{id}`; the
`custom-image` template under Run your code (`own_image: true` in the catalog, booked with `imageId`; `image-not-ready`
409 and `image-blocked` 422); the web app's "Use my own image" and Account, My images; the CLI's `idleo images` and
`idleo run custom-image --image`; the SDK's `client.images`; the admin console's Images. Metrics: checks submitted,
ended (ready, blocked, failed), answered from the cache, blocked after their check, and the backlog; the alert
ImageCheckBacklog fires when the oldest check has waited over 30 minutes.

## Alternatives considered

- **Mirror each checked image into our own registry**, so hosts pull from us and a token never leaves our server. It
  pins the bytes just as well, but stores up to 15 GB per image per renter. The token's exposure is bounded instead: a
  read-only token, which we tell renters to scope to the one repository, given to a host only for the pull of an image
  that host receives anyway. Open question: whether to mirror once storage costs are known.
- **Let Trivy pull the image itself.** That downloads it twice (once for our layer pass) and puts the token in Trivy's
  environment. The OCI layout of the blobs as downloaded serves both.
- **Unpack layers to disk and walk the tree.** It's what tools do, and it's where a crafted layer does its harm (links
  out of the tree, files over everything). A stream through a capped reader never writes a file the layer names.
- **Decide verdicts in the scanner.** The rules (the limits, the blocklist, the words) would then live in two places and
  the blocklist would need a second verifier. The scanner reports facts; the control plane, which already holds the
  verified list, decides.
- **Block on vulnerabilities or secrets.** Most real images have known vulnerabilities, and a secret in an image hurts
  its owner, not a host. Both are warnings.
- **Keep the image on the host as a cache.** Faster re-runs, but the renter's code would stay on a stranger's
  unencrypted disk.

## Consequences

- A renter's image runs only as checked, and only on a host whose driver can run it. A newer blocklist reaches images
  already checked within a minute.
- The server runs one more container. Its limits (2 CPUs, 2 GB) fit beside the control plane only with room to spare
  for one check at a time; the report of this round gives the numbers.
- Run your code now shows in the catalog with "Use my own image", even before our job image is published.
- We must watch: the backlog alert, the scanner's disk, and Trivy's database (it updates itself; a scanner offline for
  days warns with an old list).
- Own images are off in production (`images.enabled`, default false; `IMAGES_ENABLED=true` turns them on) until the
  scanner has been measured on the real server in the deployment round. Off, the list says "Your own images aren't
  available yet", a submission is `not-found`, and the `custom-image` template isn't offered. `ProductionSettingsGuard`
  allows it on only with every scanner limit set above zero, its token and the registry tokens' key.
- Open question: whether warning a renter about a secret in their image creates an obligation on us (LEGAL(confirm)).
- Open question: NVIDIA's container toolkit itself enforces `NVIDIA_REQUIRE_CUDA` against the driver's own CUDA version,
  which is stricter than minor version compatibility for an image built on a newer CUDA 12.x than the driver reports. The
  laptop day is where this is first seen on Windows.
