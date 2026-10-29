# GPU Rental Platform: project memory for Claude Code

## How we work
Read docs/WORKFLOW.md before every task. You are the builder: build, run the checks, commit, and report in the format the prompt asks for. Prompts are kept private: the next one is in `private/next-prompts/`, and a used one moves to `private/prompts-done/` (git-ignored), never into `docs/`.

## What we are building
A peer-to-peer GPU marketplace for India. Hosts (people with RTX gaming laptops and desktops) rent out GPU time by the minute; renters (students, developers, small startups) run AI jobs on them. Cloud gaming comes later, and only for legally cleared titles. The plan and the technical design are kept in a separate private repo; the rules below and the ADRs in docs/decisions/ are the public record.

## Current phase
Phase 1 (compute core: batch jobs end to end, built to production standard; free credits until the company and payment partner exist). Phase 0's hardware runs wait for a borrowed Windows RTX laptop; Phase 1 work that needs no GPU goes ahead now. The web app is built in rounds, each reviewed by the product owner before the next: foundations and the renter's home first, then booking, then the host side.
Do NOT build yet: live payments or payouts (Razorpay's and RazorpayX's live modes; test mode is built, ADR 0039, ADR 0040), gaming runtime, notebooks or endpoints, multi-region.

## Architecture rules (never break these)
1. Nothing at home is exposed: agents dial out over gRPC with TLS; host services listen only on the overlay network.
2. Every session is a lease: batch 2 minutes, endpoint and game 10 minutes, renewed every 30 seconds, never longer than the renter's reserved credit. An agent stops work when its lease lapses.
3. Database first, then side effects: commit the state change and an outbox row in one transaction, then send messages.
4. Everything is idempotent: commands carry a command_id; APIs that create things take an Idempotency-Key header.
5. Two witnesses for money: bill a minute only if the agent reported it AND the control plane saw the lease alive.
6. Least privilege: containers run non-root, read-only root, no host mounts, all capabilities dropped, LAN egress blocked.
7. Reuse proven open source for hard problems: Tailscale/Headscale, Sunshine/Moonlight, Docker, Pingora.
8. Compatible by default: .proto changes are additive only; the control plane supports the last three agent releases.

## Quality bar
We are building the final product, not an MVP (ADR 0006). Every piece meets this bar from the start:
- No temporary hacks or dead features.
- Tests: unit, integration (Testcontainers), contract and end-to-end, plus failure drills.
- Security: mTLS, short-lived tokens, secrets never in code, signed releases, rate limits.
- Data: Flyway migrations and restore-tested backups.
- Operations: metrics, logs and traces, with an alert and a runbook for every user-facing failure.
- Delivery: CI on every change, staging, one-click deploy and rollback.
- Web app: a real design system, accessible and mobile-friendly, with proper loading, error and empty states.

## Rules that hold everywhere
The full rules, area by area, are in [docs/reference/conventions.md](docs/reference/conventions.md); read the section for the area you change. Commands: [docs/reference/commands.md](docs/reference/commands.md).
- Money is integer paise (Java: the shared kernel's `Paise`; Rust i64; SQL bigint), never floating point. Money moves only through billing's `Ledger.post` (balanced, append-only, idempotent by txn_id). A minute is charged only when both witnesses saw it (ADR 0017). See "The ledger" and "Charging".
- IDs are UUIDv7; times are UTC; every "now" comes from an injected clock, never an agent's.
- One Spring Modulith module per bounded context; modules talk only through public APIs or events and never read another module's tables. One schema per module; Flyway owns all DDL and every migration names its schema. Persistence is Spring Data JDBC, no JPA.
- API errors only as `ApiProblem.of(Problem.X, detail)` from the catalog; another person's resource is `not-found`, never 403.
- Development-only behaviour is a setting, never a code path, and every new one goes into `ProductionSettingsGuard`.
- Keeping a new kind of personal data updates docs/reference/personal-data-map.md in the same commit (ADR 0037).
- Renters' own images (ADR 0042): checked by `scanner/` before they reach a host; every verdict is the control plane's `ImageJudge`; a check that doesn't finish is never a pass. See "Renters' own images".
- Agent (Rust): `#![forbid(unsafe_code)]` except agent-windows; leases count on `LeaseClock`; never fall back to fake telemetry silently. `.proto` changes are additive; generated code is never committed. Windows app, runner, sandbox and release rules: "The Windows app", "The container runner".
- Web app: every screen has loading, empty, error and stale states; money only through `formatPaise`; colours and sizes only from design tokens; sign-in SDKs only in `web/src/auth/`. The performance and accessibility budgets (web/README.md) are never loosened without the product owner's say: stop and report with numbers. See "Web app".
- Razorpay and RazorpayX are test mode only (ADRs 0039, 0040).
- Tests: JUnit 5 and Testcontainers for Java, cargo test for Rust. New behaviour ships with tests.
- Secrets never go in code or git; use a gitignored .env locally. Never ask for passwords or keys.
- Real personal data lives only in the gitignored private/ folder, never in commits, fixtures or tests.
- Sunshine and Moonlight are GPL-3.0: never copy their code into this repo.

## Commits
- One line, plainly human, lowercase, no trailing period. Example: "add heartbeat handling to the agent gateway".
- Never add Co-authored-by trailers, "Generated with" lines or session links. Attribution is off in .claude/settings.json; leave it off.
- Use the git identity already configured on this machine. Never change git config.
- Never mention phases, steps or prompts in a commit message. Say what changed.
- Prompt files are never committed: they stay in `private/` (git-ignored). They are named by topic (for example `identity-sign-in.md`), not by phase or step.

## Pull requests
Main is protected: changes reach it only through a pull request whose required checks are green, merged by rebase so history stays linear (ADR 0044). Every round works like this:
1. Branch from an up-to-date main, named after the round (for example `deploy`). Commit there, with the commit rules above.
2. Locally run `scripts/check-changed.sh` only. The full suites run in CI, which is free for this public repository.
3. Push the branch and open a pull request with `gh pr create`. Title and body read like a person wrote them: what changed and how it was tested, following `.github/pull_request_template.md`. Never mention Claude, a prompt or a review.
4. Wait for the required checks (`gh pr checks --watch`) and fix failures on the branch. When green, merge with `gh pr merge --rebase --delete-branch`.
5. Then look at the long suites that are not required (web browser, e2e, hostile) on main, and fix anything they catch in a follow-up pull request.
Never push to main directly, never force-push it, never weaken a check or the ruleset to get a merge through: stop and report. The required checks are listed in ADR 0044; a new workflow whose check should gate merges has no `paths:` filter on `pull_request`, or the check never starts for a change outside its paths and blocks the merge.

## Working rules
- Measure before fixing; report numbers, not impressions.
- One implementation per rule. Do not build features that nothing calls.
- If the prompt file already contains the plan and its decisions, start without asking. Stop only for decisions it doesn't settle.
- During work, run scripts/check-changed.sh; the full suites run in CI on the pull request. scripts/check-all.sh stays for a machine without CI (or a doubt only a full local run settles). Read a suite's log only when it fails.
- Record significant decisions as ADRs in docs/decisions/ using 0000-template.md.
- Update this file, and the detail in docs/reference/, when commands, layout or conventions change.

## Checks
- `scripts/check-changed.sh`: only the suites the files changed since origin/main touch (`--list` shows which). `scripts/check-all.sh`: every suite. Both print one line per suite, keep the whole output in `private/logs/<date-time>/<suite>.log` and show a failed suite's last 40 lines; `--verbose` prints everything. The path-to-suite map is `scripts/lib/suites.sh`.
- Suites: agent, cli, scanner, control-plane, ops, drill, proto, distro, images, sdk, web, deploy, web-browser, e2e, k8s. Change a check in scripts/checks/<suite>.sh, once; CI calls the same scripts.
- CI is free for this public repository, so the full suites run there on every pull request; push the branch when the changed suites pass locally.
- Control plane: `./mvnw verify` from control-plane/ (`./mvnw spotless:apply` fixes formatting). Everything else: docs/reference/commands.md.
- `deploy/helm/idleo/`: the one Helm chart for the server side, on k3s (ADR 0045); `deploy/k3s/`: k3s's own settings. `scripts/checks/deploy.sh` lints, kubeconforms and unit-tests it.
- `scripts/k8s/`: the Mac's k3d cluster: `up.sh`, `smoke.sh`, `status.sh`, `down.sh`, `ops.sh` (an operator command as a pod), `secrets.sh` (docs/runbooks/kubernetes.md).
- `.github/workflows/images.yml` builds, scans, signs and attests the server's images (`control-plane/Dockerfile`, `web/Dockerfile`, `scanner/`, `ops/postgres/`); `scripts/images/verify.sh <image>` checks one.
