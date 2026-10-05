# GPU Rental Platform: project memory for Claude Code

## How we work
Read docs/WORKFLOW.md before every task. You are the builder: build, run all tests, commit, and report in the format the prompt asks for. Prompts live in docs/prompts/.

## What we are building
A peer-to-peer GPU marketplace for India. Hosts (people with RTX gaming laptops and desktops) rent out GPU time by the minute; renters (students, developers, small startups) run AI jobs on them. Cloud gaming comes later, and only for legally cleared titles. Full plan: docs/plan/execution-plan.md and docs/plan/technical-design.md. The PDFs beside them contain the diagrams.

## Current phase
Phase 1 (compute core: batch jobs end to end, built to production standard; free credits until the company and payment partner exist). Phase 0's hardware runs wait for a borrowed Windows RTX laptop; Phase 1 work that needs no GPU goes ahead now.
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
- Money is integer paise (Java long, Rust i64, SQL bigint). Never floating point.
- IDs are UUIDv7. Times are UTC (timestamptz). Never trust agent clocks for billing; every "now" comes from an injected clock.
- Control plane: Java 25, Spring Boot 4, Spring Modulith, built with Maven through ./mvnw (ADR 0008). One Spring Modulith module per bounded context (identity, hosts, agentgateway, overlay, scheduling, sessions, metering, billing, payouts, trust, notifications). Modules talk only through public APIs or events and never read another module's tables.
- Database: PostgreSQL 18 with Flyway migrations, expand then migrate then contract. One schema per module; Flyway owns all DDL. Persistence is Spring Data JDBC with explicit SQL (JdbcClient where needed), no JPA (ADR 0009). Valkey 9 (Redis-compatible, via Spring Data Redis) only for state that is allowed to expire, and never in readiness. Only the agentgateway module writes host presence, always conditional on the connection that owns the record (ADR 0011).
- Migrations name the schema of every object they create or change (identity.users, never users). Flyway's default schema is flyway, so an unqualified name lands there silently; a test fails if any table other than Flyway's history table appears in the flyway schema.
- API errors use RFC 9457 Problem Details.
- Only the hosts module issues or reads host certificates and enrolment codes; other modules go through its public API (ADR 0010). Agents trust only our own root CA, and the gRPC port trusts only our root for client certificates (ADR 0011).
- Create requests take an Idempotency-Key through the shared IdempotentRequests helper, with a <schema>.idempotency_keys table in each module.
- Tests: JUnit 5 and Testcontainers for Java, cargo test for Rust. New behaviour ships with tests.
- Secrets never go in code or git; use a gitignored .env locally. Never ask for passwords or keys.
- Real personal data lives only in the gitignored private/ folder, never in commits, fixtures or tests.
- Sunshine and Moonlight are GPL-3.0: never copy their code into this repo.

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

Host agent, run from agent/ (Rust; rustup installs the toolchain pinned in rust-toolchain.toml):
- cargo fmt --all --check
- cargo clippy --workspace --all-targets -- -D warnings: pedantic is on for the whole workspace; allow a single lint only with a comment saying why
- cargo test --workspace: unit tests, and integration tests against an in-process mutual-TLS control plane
- cargo deny check: permissive licences only (no GPL in the agent) and RustSec advisories
- cargo run -p agent -- run --fake-gpu: run on a Mac with invented GPUs (needs agent.toml and an enrolment; see agent/README.md)
- Rust code for proto/ is generated at build time by protox (agent-proto/build.rs), never by a system protoc; never commit it
- #![forbid(unsafe_code)] everywhere except the agent-windows crate, the one documented place for Windows API calls (ADR 0012)
- Never fall back to fake telemetry silently: fake GPUs only with --fake-gpu, and a missing NVML is an error

Agent protocol, run from proto/:
- buf lint: STANDARD + COMMENTS, no exceptions
- buf format -d: must print nothing (buf format -w fixes it)
- buf build
- buf breaking --against '../.git#branch=main,subdir=proto': FILE rules
