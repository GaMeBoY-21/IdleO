# 0034. Signed release feed

- **Status:** Accepted
- **Date:** 2026-10-18

## Context

The Windows app (ADR 0033) has to be installed by a one-click download and kept up to date without the owner doing
anything (docs/plan/host-experience.md §6 rows 8 to 10). An update replaces programs that run on someone's own
laptop, with a sealed Linux space beside them, so a tampered or half-downloaded file must never run. A bad release
must reach few hosts and roll itself back. Phase 1 has no release server, no admin API for releases and no
Authenticode certificate yet.

## Decision

**One signed manifest is the truth, for installing and updating.** `stable/latest.json` and its signature
`stable/latest.json.sig`, in a `releases` bucket of the object storage the stack already uses (MinIO locally, R2 in
production). The manifest names `version`, `channel`, `released_at`, `rollout_percent` (0 to 100),
`min_windows_build`, and for each of `app` (a zip of the five programs), `distro` (the sealed Linux tarball, with its
own version) and `setup` (the bare `IdleO-Setup.exe`) a `file`, `sha256` and `size`. Download addresses are not
signed: integrity comes from the size and SHA-256 the signed manifest gives.

- **Signatures** are Ed25519 (`ed25519-dalek` in Rust, the JDK's own in Java) over the manifest's exact bytes.
- **Keys:** the production public key is `agent/release-keys/release.pub`, committed, empty until Nikki makes it with
  `scripts/release/new-release-key.sh`, which writes the private half only where she chooses and says to keep it
  offline. A development pair is made by `scripts/release/dev-key.sh` into the git-ignored `private/release-dev/`.
- **Builds:** a normal build bakes in the development public key (or none, in CI and tests, which sign with their
  own), shows "Development build" in the setup window's title and the tray's tooltip, and refuses any control plane
  whose host ends in `idleo.in`. A release build (`--features prod-keys`) bakes in `release.pub` and fails to compile
  while it's empty. Only `idleo-release sign --key <path>` ever reads a private key.
- **The control plane** (`AgentReleaseFeed`, hosts module) reads the manifest every `hosts.releases.refresh-every`
  (5 minutes; 10 s on the local stack) and caches it only if its signature verifies against
  `IDLEO_RELEASE_PUBLIC_KEY` and it isn't older than what it has. With no key the feed is off.
  `GET /v1/agent-releases/latest` (no sign-in, limited per address) gives the version, the setup's size, the manifest
  and signature as signed, and hour-long download links; `/setup` redirects to the setup program, saved as
  `IdleO-Setup.exe`. With no release it's the problem `no-agent-release`, and the Download buttons say "coming soon".
- **Rollout:** a host is in a release when `sha256(host_id ‖ version) mod 100 < rollout_percent`, so each release
  picks its own hosts and raising the percentage only adds hosts. A host gets an `UpdateAgent` (manifest, signature,
  links) through the durable command queue when it's welcomed or when a new manifest is cached, if the release is
  newer than its agent, it's in the rollout, and it hasn't rolled that version back. The same rule shows "Updating when
  idle" on the machine page.
- **The agent** checks the signature with its baked-in key and refuses a version that isn't newer, downloads with
  resume into `updates\`, checks size and SHA-256, and installs only while no session runs and no offer was just
  accepted. The new version must pass `self-test --quick` before `current` switches. The launcher then gives it a
  trial: confirmed once it's connected and its own quick self-test passes; rolled back if it isn't confirmed within 5
  minutes or crashes 3 times. Each outcome is an `UpdateResult` (installed, rolled back, failed verify, failed
  download) the admin console shows.
- **Publishing** is one command: `scripts/release/publish.sh --version X.Y.Z --rollout N` builds the programs with
  that version, Authenticode-signs them (`scripts/release/sign-windows.sh`, the one `TODO(signing)`), zips the app,
  writes and signs the manifest, and uploads the files before the manifest. Raising, lowering or stopping a rollout
  (`--rollout 0`) is a new signed manifest (`--manifest-only`). There is no database table and no admin API for
  releases.

## Alternatives considered

- **A releases table and an admin API to publish and roll out.** More moving parts, and the control plane's database
  would become a place a release could be changed without the signing key. Lost: the signed file is enough, and
  a key kept offline is a stronger control than an admin session.
- **Trusting HTTPS and Authenticode alone.** Authenticode isn't available until the company has a certificate, and
  neither protects against a compromised bucket or control plane serving an older, vulnerable build. Lost: the
  manifest's signature and the newer-only rule do.
- **minisign or TUF.** minisign's format adds a dependency for no gain over raw Ed25519; TUF's full role model is more
  than one channel with one key needs today. Open question: revisit TUF if we add channels or delegate signing.
- **Signing each file instead of a manifest.** Many signatures to check and no single place for the rollout. Lost.

## Consequences

- A tampered file, a wrong file or a truncated download never installs; a release can only be rolled out by whoever
  holds the offline private key.
- Losing the private key means shipping a new public key in a release signed by the old one; with the old key gone,
  every host would have to reinstall. Open question: a second, offline backup key baked in beside the first.
- The control plane can withhold an update (rollout, rollbacks) but never invent one.
- The agent, the setup app and the launcher share one implementation of the manifest, download, and version rules
  (`agent/release`), so they can't disagree.
