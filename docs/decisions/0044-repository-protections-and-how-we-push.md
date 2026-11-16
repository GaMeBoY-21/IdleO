# 0044. Repository protections, and how we push

- **Status:** Accepted. The way of working below ("Direct to main") replaced pull requests on 2026-11-16; the rest stands.
- **Date:** 2026-10-29 (amended 2026-11-16)

## Context

The repository is public (all rights reserved, published for transparency), and GitHub's protections for public repositories cost nothing. Until now every round was committed on main, checked on the Mac with `scripts/check-all.sh` and pushed once, because CI minutes were short. That is no longer the constraint, and a single person pushing straight to main has no safety net if a check was skipped, a secret slipped into a commit, or an action or dependency changed underneath us.

## Decision

**Scanning.** CodeQL (`.github/workflows/codeql.yml`, `security-extended` queries) for Java/Kotlin, JavaScript/TypeScript, Python and Rust, on pull requests, pushes to main and weekly, with source analysis and no build. gitleaks runs on every pull request and push with the repository's `.gitleaks.toml` (`gitleaks.yml`, the image pinned by digest, the same file `scripts/go-public/verify.sh` uses). GitHub's secret scanning and push protection, Dependabot alerts and security updates, and private vulnerability reporting (what `SECURITY.md` points to) are on.

**Updates.** Dependabot (`.github/dependabot.yml`) opens grouped pull requests weekly for Maven, npm, Cargo (agent, cli, scanner), uv (the SDK), GitHub Actions and the Docker files that nothing else moves. Left out on purpose, with the reason in the file: the sealed Linux image and the job image (their own bump scripts keep digests and versions in step), the hostile test's and GPU check's Ubuntu (the same digest as the distribution's), the `rust` base image (`rust-toolchain.toml` pins the compiler), PostgreSQL's major version, and test fixtures.

**Workflows.** Every workflow has top-level `permissions: contents: read`; a job asks for more only where it needs it (CodeQL and Scorecard `security-events: write`, the browser run `actions: read`, the release job its three). Every third-party action is pinned to a full commit SHA with its version in a comment, and the repository setting that refuses an unpinned action is on. No workflow uses `pull_request_target` or `workflow_run`, none reads a secret, and runs from first-time and other outside contributors wait for approval. Superseded runs on a branch are cancelled.

**Supply chain.** OpenSSF Scorecard (`scorecard.yml`, weekly and on pushes to main) publishes its result, and README shows the badge. A real release writes CycloneDX bills of materials (`scripts/release/sbom.py`): the crates the Windows programs are built from, and the Linux image's Ubuntu packages and runner. `publish.sh` uploads them to the release feed beside the files. The release workflow (`release.yml`, run by hand after `publish.sh`) holds no key: it reads the public feed, checks the signed manifest with the committed release key and each file's SHA-256, makes a build provenance attestation for the setup program, the app zip, each program in it and the Linux image tarball, and attaches the manifest and the bills of materials to a GitHub release.

**Main.** A repository ruleset on `main`: pull requests only, merged by rebase only, linear history, no force pushes, no deletion, and these required checks (GitHub Actions' own): `control-plane verify`, `agent (ubuntu-24.04)`, `agent deny`, `cli (ubuntu-24.04)`, `cli deny`, `scanner`, `scanner deny`, `proto`, `sdk (python 3.10)`, `sdk (python 3.13)`, `web checks`, the four `codeql (language)` checks and `gitleaks`; since ADR 0045, also `deploy chart` and the `image (name)` checks, which build and scan the server's images: `image control-plane`, `image web` and `image scanner` (since ADR 0047 there is no `image postgres`: the database's image is CloudNativePG's). There are no required reviewers (one person works here); the repository admin can bypass the rules on a pull request in an emergency, but cannot push to main directly. The long suites (the web browser run, the end-to-end run, the hostile tests) run but do not gate. `CODEOWNERS`, a pull request template and `CONTRIBUTING.md` (no outside contributions) complete it.

**Way of working.** CLAUDE.md, "Pull requests": a branch per round, `scripts/check-changed.sh` locally, a pull request, the required checks, a rebase merge. What decides a merge is the pull request's final commit: its required checks pass, and every long suite that has run on it is green or still running. A red suite on an earlier commit doesn't block once later commits fix it and the report says what it was; an unexplained red always does. A long suite needn't finish first, and a red one on main is fixed before anything else. A pull request is fixed, never closed and recreated to reset its history.

## Alternatives considered

- **Squash merges.** One commit per round would hide the commit-by-commit history the commit rules are written for. Rebase merges keep each commit and a linear main.
- **Required long suites.** The web browser run takes about an hour and has failed on hosted-runner timing, not on defects; gating on it would block every merge on a runner's mood. They run on every relevant change and are read after the merge. Web browser and e2e run as parallel jobs behind one check each (`web browser`, `agent-online`): web browser when `web/` changes, e2e when the app, the control plane or the agent does.
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

**Runners (2026-11-06).** Every workflow pins `ubuntu-24.04`, so a change of what `ubuntu-latest` means can't break CI by itself. The weekly `next runner` workflow runs the fast suites on `ubuntu-latest`, and tells us (`NextRunnerSuiteFailing`) while moving the pin isn't safe.

## Direct to main (2026-11-16)

The product owner is the only developer and the product is pre-launch, so a pull request per round, its required checks and a rebase merge cost more than they protect: a stacked set of branches could not even be merged by rebase, and the long suites kept blocking work on a runner's mood. From now on:

1. **No pull requests.** Commit and push straight to main, with the commit rules unchanged. The ruleset on main no longer requires a pull request, a merge method or any check; it still refuses **force pushes and deletion**, and they are never worked round.
2. **Before each push, only fast checks on what changed:** `scripts/check-changed.sh --fast`: types, lint and the unit tests of the touched modules (`vitest --changed`, a clean compile with its formatting and the tests of the changed classes for the control plane, `cargo fmt`, `clippy` and `cargo test --lib --bins` for the crates, `buf`, the SDK's checks, the plain-Secret check of `deploy/`). No end-to-end, browser, Lighthouse, k8s, gitops, security, restore-drill, distro, images or ops suite, no audit and no build, unless the product owner asks. It runs in about three minutes.
3. **CI on a push to main runs the fast suites only:** `control-plane verify`, `agent`, `cli`, `scanner`, `proto`, `sdk`, `web checks` (types, lint, unit tests, the builds and the first-load budget), `deploy chart`, CodeQL, gitleaks and the server's images with their scan (which is how staging gets a release). The long suites run **every night** and on demand, staggered through the night (UTC): `e2e` 17:23, `web` (the browser run and Lighthouse) 18:47, `k8s` 19:17, `gitops` 20:43, `scan` 21:37 (already nightly), `security` 22:29, `failover` 23:11. The hostile tests stay weekly (Mondays) as they were, and the console's budgets and the next-runner check weekly. A pull request that still arrives (Dependabot's, Renovate's) gets the fast checks only. Their alerts follow the schedule (ADR 0050): 30 hours for a nightly suite, a week and a day for a weekly one (`scripts/checks/ops.sh` checks the two agree).
4. **Every session starts with last night's long suites on main** (`scripts/nightly-status.sh`); a red one is fixed first, and the product owner is told.
5. **Production stays deliberate:** the "Promote staging to production" pull request (`promote.yml`) is kept; so are staging's deploy pull requests that the build of main opens.
6. **No new tests, harnesses, audits or measurement reports unless asked**, except a unit test with a fix for a bug in money, billing, security or sign-in.
7. **Escalation for a red scan:** `NightlyScanRedForAWeek` (a page) fires when every nightly scan for seven days found something, because `ImageFindingOld` ages only the findings on main's own images, so a vendor's finding could stand for weeks with only the daily digest.

What was given up, knowingly: nothing now stops a push that fails a check, a secret that slips into a commit is caught after the push by gitleaks and GitHub's push protection rather than before the merge, and a long suite's regression is found the next morning rather than before it lands. The fast suites on every push, the nightly long ones and the first-thing-in-a-session read of them are what stand in for the gate.

**We go back to pull requests with the required checks (the list under "Main" above, restored in the ruleset) at go-live (S5), or when a second developer joins, whichever is first.**
