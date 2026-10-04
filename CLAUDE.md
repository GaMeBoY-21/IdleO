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
- Database: PostgreSQL 18 with Flyway migrations, expand then migrate then contract. One schema per module; Flyway owns all DDL, and Hibernate never creates or changes tables (ddl-auto validate once JPA arrives). Valkey (Redis-compatible) only for state that is allowed to expire.
- API errors use RFC 9457 Problem Details.
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
- ./mvnw spring-boot:run -Dspring-boot.run.profiles=local: run against compose.yaml's PostgreSQL

Agent protocol, run from proto/:
- buf lint: STANDARD + COMMENTS, no exceptions
- buf format -d: must print nothing (buf format -w fixes it)
- buf build
- buf breaking --against '../.git#branch=main,subdir=proto': FILE rules
