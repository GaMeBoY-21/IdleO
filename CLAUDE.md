# GPU Rental Platform: project memory for Claude Code

## How we work
Read docs/WORKFLOW.md before every task. You are the builder: build, run all tests, commit, and report in the format the prompt asks for. Prompts live in docs/prompts/.

## What we are building
A peer-to-peer GPU marketplace for India. Hosts (people with RTX gaming laptops and desktops) rent out GPU time by the minute; renters (students, developers, small startups) run AI jobs on them. Cloud gaming comes later, and only for legally cleared titles. Full plan: docs/plan/execution-plan.md and docs/plan/technical-design.md. The PDFs beside them contain the diagrams.

## Current phase
Phase 1 (compute core: batch jobs end to end, built to production standard; free credits until the company and payment partner exist). Phase 0's hardware runs wait for a borrowed Windows RTX laptop; Phase 1 work that needs no GPU goes ahead now. The web app is built in rounds, each reviewed by Nikki before the next: foundations and the renter's home first, then booking, then the host side.
Do NOT build yet: real payments, gaming runtime, notebooks or endpoints, multi-region.

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

## Conventions
- Money is integer paise (Java long, Rust i64, SQL bigint). Never floating point. In Java, money is the shared kernel's Paise type, whose arithmetic throws on overflow.
- Money moves only through billing's Ledger.post: one balanced transaction of two or more entries, with the wallet cache updated in the same database transaction. txn_id is derived from what caused the posting, so a retry is a no-op. Ledger entries are never edited or deleted; a correction is a new reversing transaction. The database enforces balance, append-only and non-negative wallets, and a test fails if anything but Ledger writes billing.ledger_entries or billing.wallets (ADR 0014).
- Charging (ADR 0017), rules every change must keep:
  - A minute is charged only when the agent reported it AND the control plane's own lease record (sessions.lease_coverage) has one unbroken stretch covering the whole minute, both from the same attempt. Coverage never comes from anything the agent says; minute k of an attempt starts at its first RUNNING as the control plane received it, plus k minutes.
  - A started minute both witnesses saw is charged as a whole minute. A minute only one witness saw is never charged; it goes to metering.review_minutes.
  - Metering settles every 15 minutes from minute 0 and at the end, through billing's SessionCharges, never past the spending cap or the hold plus the available balance. txn_id is a name-based UUID of the session and window number; a settlement's claims and posting are one transaction under the session's row lock.
  - Every hold change goes through CreditReservations and lands in the append-only billing.reservation_changes; the nightly check adds them up.
  - A lease is renewed only while the hold, less what's committed, covers the minutes it would start. Running out is a StopSession with checkpoint_first, never a negative wallet.
  - A lost attempt's minutes ending after its newest complete checkpoint (storage's marker time, never the agent's clock) are waived; settled ones are refunded once, txn_id a name-based UUID of the session and attempt, in the original proportions.
  - Directions: metering reads sessions (MeteredSessions) and agentgateway (UsageReports), and charges through billing; sessions declares SessionCosts and metering implements it. Nothing depends on metering. Billing asks hosts who owns a host (HostOwners).
- IDs are UUIDv7. Times are UTC (timestamptz). Never trust agent clocks for billing; every "now" comes from an injected clock.
- Control plane: Java 25, Spring Boot 4, Spring Modulith, built with Maven through ./mvnw (ADR 0008). One Spring Modulith module per bounded context (identity, hosts, agentgateway, overlay, scheduling, sessions, metering, billing, payouts, trust, notifications). Modules talk only through public APIs or events and never read another module's tables.
- Database: PostgreSQL 18 with Flyway migrations, expand then migrate then contract. One schema per module; Flyway owns all DDL. Persistence is Spring Data JDBC with explicit SQL (JdbcClient where needed), no JPA (ADR 0009). Valkey 9 (Redis-compatible, via Spring Data Redis) only for state that is allowed to expire, and never in readiness. Only the agentgateway module writes host presence, always conditional on the connection that owns the record (ADR 0011); others ask whether a host is online through the hosts module's HostPresence port, which agentgateway implements, so the dependency never points back from hosts (ADR 0013).
- Migrations name the schema of every object they create or change (identity.users, never users). Flyway's default schema is flyway, so an unqualified name lands there silently; a test fails if any table other than Flyway's history table appears in the flyway schema.
- API errors use RFC 9457 Problem Details, and go only through the catalog (ADR 0019): every error is `ApiProblem.of(Problem.X, detail)`, its `type` `/problems/<slug>` from the one enum `Problem`. A test fails if a `ResponseStatusException` or a bare `ProblemDetail` is built anywhere else, or if any test gets a problem without a catalog type; `docs/api/problems.md` is generated from the enum (`./mvnw test -Dtest=ProblemCatalogTests -Dproblems.write=true`). Slugs never change once published; another person's resource is `not-found`, never a 403.
- Browsers call /v1 only from the origins in web.allowed-origins, with a bearer token and never cookies; production refuses *, plain HTTP and localhost (ProductionSettingsGuard).
- Module directions (ADR 0015): sessions publishes SessionRequested and SessionCancelled, and scheduling listens; scheduling reads hosts (SchedulableHosts), sessions (SessionBookings), presence (AgentPresence) and holds credit (CreditReservations), and queues agent messages through agentgateway's AgentCommands; agentgateway declares the OfferReplies port and scheduling implements it. Events are durable (Spring Modulith's JDBC registry, table modulith.event_publication, created by Flyway). Never add a dependency back from hosts, billing, sessions or agentgateway to scheduling.
- Everything sent to an agent goes through agentgateway.agent_commands; Valkey pub/sub is only a hint, and a sweep delivers anyway. The one exception is LeaseRenew, sent every 30 s straight down a live stream through agentgateway's AgentStreams, only by the instance holding it.
- Sessions (ADR 0016): sessions depends on agentgateway and implements its ports SessionCredentials (fresh storage grants at each StartSession delivery; no credential is ever stored or logged), SessionStatuses and SessionReconciliation. Object storage is sessions.storage: MinIO (STS) locally and in tests, Cloudflare R2's temporary credentials in prod. A checkpoint or the outputs count only once the control plane finds their _COMPLETE marker in storage. A host counts as lost only sessions.leases.transit-allowance after lease_expires_at, so two attempts never run at once.
- Only the hosts module issues or reads host certificates and enrolment codes; other modules go through its public API (ADR 0010). Agents trust only our own root CA, and the gRPC port trusts only our root for client certificates (ADR 0011).
- Create requests take an Idempotency-Key through the shared IdempotentRequests helper, with a <schema>.idempotency_keys table in each module.
- Tests: JUnit 5 and Testcontainers for Java, cargo test for Rust. New behaviour ships with tests.
- Secrets never go in code or git; use a gitignored .env locally. Never ask for passwords or keys.
- Real personal data lives only in the gitignored private/ folder, never in commits, fixtures or tests.
- Sunshine and Moonlight are GPL-3.0: never copy their code into this repo.
- Web app (ADR 0018), rules every change must keep:
  - Every screen follows web/DESIGN.md and has its four states: loading (a skeleton exactly the content's height), empty, error with a retry, and stale. Zero axe violations, and no layout shift (CLS 0).
  - Money is formatted only by formatPaise (web/src/lib/money.ts), from integer paise, never floating point, never optimistically.
  - The sign-in SDKs (firebase, oidc-client-ts) are imported only inside web/src/auth/; a lint rule enforces it. Our code never stores a token.
  - Colours, sizes and durations come only from web/src/design/tokens.ts (the accent from web/src/brand/); a lint rule refuses raw colours and arbitrary values.
  - The budgets (web/README.md: LCP 2.0 s, INP 200 ms, CLS 0, TBT 150 ms, 150 KB first-load JavaScript, accessibility 100, on Lighthouse's mobile profile) are never loosened without Nikki's say; if one can't be met, stop and report it with numbers.
  - The local sign-in adapter is for local builds only; a staging or production build refuses it (web/src/build-settings.ts).

## Commits
- One line, plainly human, lowercase, no trailing period. Example: "add heartbeat handling to the agent gateway".
- Never add Co-authored-by trailers, "Generated with" lines or session links. Attribution is off in .claude/settings.json; leave it off.
- Use the git identity already configured on this machine. Never change git config.
- Push only when a remote exists and the prompt says to.
- Never mention phases, steps or prompts in a commit message. Say what changed.
- A prompt file is committed together with the first piece of work it asks for, never in a commit of its own. Prompt files are named by topic (for example identity-sign-in.md), not by phase or step.

## Working rules
- Measure before fixing; report numbers, not impressions.
- One implementation per rule. Do not build features that nothing calls.
- For changes touching more than two files, show a short plan and wait for approval.
- Record significant decisions as ADRs in docs/decisions/ using 0000-template.md.
- Update this file when commands, layout or conventions change.

## Commands
Control plane, run from control-plane/ (needs Java 25 and Docker):
- ./mvnw verify: compile with -Werror, enforcer, all tests (Testcontainers, modularity), Spotless check, JaCoCo report
- ./mvnw spotless:apply: fix formatting
- scripts/dev-ca.sh: create the throwaway development CA under the gitignored private/dev-ca/ (--force to replace it)
- scripts/run-local.sh: run locally against compose.yaml's PostgreSQL and the dev CA; loads .env, always uses the local profile, refuses any prod profile
- Java code for proto/ is generated during the build (target/generated-sources); never commit it
- api/openapi.yaml is the API contract, generated by springdoc from the controllers (ADR 0018). ApiContractTests fails verify when it drifts; after an intended API change, run ./mvnw test -Dtest=ApiContractTests -Dopenapi.write=true and review the diff. Record fields are required in it unless @Nullable

Host agent, run from agent/ (Rust; rustup installs the toolchain pinned in rust-toolchain.toml):
- cargo fmt --all --check
- cargo clippy --workspace --all-targets -- -D warnings: pedantic is on for the whole workspace; allow a single lint only with a comment saying why
- cargo test --workspace: unit tests, and integration tests against an in-process mutual-TLS control plane
- cargo deny check: permissive licences only (no GPL in the agent) and RustSec advisories
- cargo run -p agent -- run --fake-gpu [--dev-runner]: run on a Mac with invented GPUs (needs agent.toml and an enrolment; see agent/README.md); --dev-runner, only with --fake-gpu, runs sessions by simulating them against real object storage. Without a runner the agent declines offers
- Leases count on the LeaseClock (CLOCK_BOOTTIME on Linux, QueryPerformanceCounter on Windows, CLOCK_MONOTONIC on macOS), never std::time::Instant (ADR 0016)
- Rust code for proto/ is generated at build time by protox (agent-proto/build.rs), never by a system protoc; never commit it
- #![forbid(unsafe_code)] everywhere except the agent-windows crate, the one documented place for Windows API calls (ADR 0012)
- Never fall back to fake telemetry silently: fake GPUs only with --fake-gpu, and a missing NVML is an error

End to end, run from the repository root (needs Docker, Java 25 and Rust; docs/runbooks/local-stack.md):
- scripts/e2e/agent-online.sh: the compatibility test. Starts the local stack (PostgreSQL, Valkey, MinIO, the development sign-in server, the control plane with templates/development.yaml, a 10 s lease and 60 s storage grants), enrols four real agents with fake GPUs and the development runner, checks the hosts go online, then runs four charged sessions side by side, one per host: 150 s charged exactly 3 minutes (6 paise; host +4, platform +2), a renter stop at ~70 s, a kill -9 mid-run (attempt 2 resumes, attempt 1's minutes after its checkpoint waived), and a 6 paise spending cap. It runs reconcile-wallets (0 mismatches), checks the hosts go offline, and tears everything down. CI runs it on every control-plane, agent, proto or template change
- scripts/e2e/agent-online.sh --keep-running: only the stack, with a ready curl and a fresh token, until Ctrl-C
- The default run also builds the web app and runs its journey on a fifth agent (web/e2e/journey.spec.ts); --web runs the web app's browser checks instead of the charged scenarios
- Development-only behaviour is a setting, never a code path (for example agentgateway.allow-development-hosts, trusting the local sign-in issuer, sessions.storage.provider minio, or a sessions.templates.catalog other than the bundled one). Every new development setting must be added to ProductionSettingsGuard, which refuses to start the prod profile with any of them (ADR 0013)

Operator commands, the control plane in the ops profile (runs once, starts no web or gRPC server, exits with 0 done, 2 bad command line, 3 refused, 4 a check found a problem; docs/runbooks/granting-credits.md, docs/runbooks/hosts.md):
- OPERATOR=<you> java -jar control-plane.jar --spring.profiles.active=prod,ops grant-credits --user <id> --paise <n> --reason "<why>" --key <key>
- OPERATOR=<you> ... reverse-grant --grant <id> --reason "<why>"
- OPERATOR=<you> ... reconcile-wallets: the nightly wallet check, now; changes nothing
- OPERATOR=<you> ... activate-host --host <id> --reason "<why>" and suspend-host --host <id> --reason "<why>" (docs/runbooks/hosts.md)
- Locally: COMPOSE_PROJECT_NAME=idleo-e2e OPERATOR=<you> ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local,ops -Dspring-boot.run.arguments="…", against the scripts/e2e/agent-online.sh --keep-running stack
- A module adds an operator command as an OperatorCommand bean in the ops profile; OperatorCommandRunner picks it by name

Web app, run from web/ (Node 24.21.0 from .node-version, pnpm 12.9.1; web/README.md):
- pnpm install, then pnpm dev against scripts/e2e/agent-online.sh --keep-running (copy env.local.example to .env.local); sign in with any @example.com address
- pnpm typecheck, pnpm lint, pnpm test (Vitest), pnpm build (static dist/ with pre-rendered frames, the CSP and dist/headers.json), pnpm size (the first-load budget)
- pnpm e2e, pnpm lighthouse and pnpm gallery need the stack and pnpm serve; scripts/e2e/agent-online.sh --web runs all the browser checks against a fresh stack, as .github/workflows/web.yml does
- API types come from ../api/openapi.yaml on every build, typecheck and test (src/api/schema.gen.ts, never committed)
- Visual baselines live in CI's visual-baseline artifact, not git; accept a change by running the web workflow with "update screenshots"
- The review gallery (pnpm gallery) goes to private/review/, never committed

Agent protocol, run from proto/:
- buf lint: STANDARD + COMMENTS, no exceptions
- buf format -d: must print nothing (buf format -w fixes it)
- buf build
- buf breaking --against '../.git#branch=main,subdir=proto': FILE rules
