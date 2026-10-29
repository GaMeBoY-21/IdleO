# 0044. Repository protections and working through pull requests

- **Status:** Accepted
- **Date:** 2026-10-29

## Context

The repository is public (all rights reserved, published for transparency), and GitHub's protections for public repositories cost nothing. Until now every round was committed on main, checked on the Mac with `scripts/check-all.sh` and pushed once, because CI minutes were short. That is no longer the constraint, and a single person pushing straight to main has no safety net if a check was skipped, a secret slipped into a commit, or an action or dependency changed underneath us.

## Decision

**Scanning.** CodeQL (`.github/workflows/codeql.yml`, `security-extended` queries) for Java/Kotlin, JavaScript/TypeScript, Python and Rust, on pull requests, pushes to main and weekly, with source analysis and no build. gitleaks runs on every pull request and push with the repository's `.gitleaks.toml` (`gitleaks.yml`, the image pinned by digest, the same file `scripts/go-public/verify.sh` uses). GitHub's secret scanning and push protection, Dependabot alerts and security updates, and private vulnerability reporting (what `SECURITY.md` points to) are on.

**Updates.** Dependabot (`.github/dependabot.yml`) opens grouped pull requests weekly for Maven, npm, Cargo (agent, cli, scanner), uv (the SDK), GitHub Actions and the Docker files that nothing else moves. Left out on purpose, with the reason in the file: the sealed Linux image and the job image (their own bump scripts keep digests and versions in step), the hostile test's and GPU check's Ubuntu (the same digest as the distribution's), the `rust` base image (`rust-toolchain.toml` pins the compiler), PostgreSQL's major version, and test fixtures.

**Workflows.** Every workflow has top-level `permissions: contents: read`; a job asks for more only where it needs it (CodeQL and Scorecard `security-events: write`, the browser run `actions: read`, the release job its three). Every third-party action is pinned to a full commit SHA with its version in a comment, and the repository setting that refuses an unpinned action is on. No workflow uses `pull_request_target` or `workflow_run`, none reads a secret, and runs from first-time and other outside contributors wait for approval. Superseded runs on a branch are cancelled.

**Supply chain.** OpenSSF Scorecard (`scorecard.yml`, weekly and on pushes to main) publishes its result, and README shows the badge. A real release writes CycloneDX bills of materials (`scripts/release/sbom.py`): the crates the Windows programs are built from, and the Linux image's Ubuntu packages and runner. `publish.sh` uploads them to the release feed beside the files. The release workflow (`release.yml`, run by hand after `publish.sh`) holds no key: it reads the public feed, checks the signed manifest with the committed release key and each file's SHA-256, makes a build provenance attestation for the setup program, the app zip, each program in it and the Linux image tarball, and attaches the manifest and the bills of materials to a GitHub release.

**Main.** A repository ruleset on `main`: pull requests only, merged by rebase only, linear history, no force pushes, no deletion, and these required checks (GitHub Actions' own): `control-plane verify`, `agent (ubuntu-latest)`, `agent deny`, `cli (ubuntu-latest)`, `cli deny`, `scanner`, `scanner deny`, `proto`, `sdk (python 3.10)`, `sdk (python 3.13)`, `web checks`, the four `codeql (language)` checks and `gitleaks`; since ADR 0045, also `deploy chart` and the four `image (name)` checks, which build and scan the server's images. There are no required reviewers (one person works here); the repository admin can bypass the rules on a pull request in an emergency, but cannot push to main directly. The long suites (the web browser run, the end-to-end run, the hostile tests) run but do not gate. `CODEOWNERS`, a pull request template and `CONTRIBUTING.md` (no outside contributions) complete it.

**Way of working.** CLAUDE.md, "Pull requests": a branch per round, `scripts/check-changed.sh` locally, a pull request, the required checks, a rebase merge, then a look at the long suites on main.

## Alternatives considered

- **Squash merges.** One commit per round would hide the commit-by-commit history the commit rules are written for. Rebase merges keep each commit and a linear main.
- **Required long suites.** The web browser run takes about an hour and has failed on hosted-runner timing, not on defects; gating on it would block every merge on a runner's mood. They run on every relevant change and are read after the merge.
- **Keeping path filters on required workflows.** A workflow skipped by a path filter never reports its check, so a required check stays pending and the merge is blocked. The required workflows run on every pull request instead; only the long web browser job is skipped by a job-level condition on a pull request that touches nothing it covers (a skipped job counts as passing).
- **Attesting binaries built in CI.** The Windows programs are cross-built and Authenticode-signed on the product owner's machine, with a key that must not be in CI. The attestation therefore says which workflow run and commit vouched for those exact, signature-checked bytes; it does not say CI built them.
- **A bill of materials from `cargo-cyclonedx` and `syft`.** More fields, but two more tools to install and trust on the release machine. `sbom.py` reads `cargo metadata --locked` and the package list `build-distro.sh` already writes.

## Consequences

- Every change waits for CI before it reaches main; a failing check is fixed on the branch.
- A workflow that should gate merges must not filter `pull_request` by path; CLAUDE.md says so.
- Dependabot's pull requests go through the same checks; a bump that breaks a pin that must move in step (a digest also written in a Java test, for example) shows up in CI or review, not silently.
- The first release after this needs the repository variable `IDLEO_RELEASE_FEED_URL` and a committed `release.pub`.
- The Linux image's bill of materials lists packages and the runner's crates, not what the job image (pulled at run time) contains; that image is scanned on its own (ADR 0042).
- Open question: whether the attested exes should one day be built in CI and signed by a signing service, which would make the provenance a build provenance in the full sense.
