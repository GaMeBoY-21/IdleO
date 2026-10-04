# GPU Rental Platform: project memory for Claude Code

## How we work
Read docs/WORKFLOW.md before every task. You are the builder: build, run all tests, commit, and report in the format the prompt asks for. Prompts live in docs/prompts/.

## What we are building
A peer-to-peer GPU marketplace for India. Hosts (people with RTX gaming laptops and desktops) rent out GPU time by the minute; renters (students, developers, small startups) run AI jobs on them. Cloud gaming comes later, and only for legally cleared titles. Full plan: docs/plan/execution-plan.md and docs/plan/technical-design.md. The PDFs beside them contain the diagrams.

## Current phase
Phase 0 (feasibility), then Phase 1 (compute MVP: batch jobs end to end, free credits only).
Do NOT build yet: real payments, gaming runtime, notebooks or endpoints, web UI polish, multi-region.

## Architecture rules (never break these)
1. Nothing at home is exposed: agents dial out over gRPC with TLS; host services listen only on the overlay network.
2. Every session is a lease: batch 2 minutes, endpoint and game 10 minutes, renewed every 30 seconds, never longer than the renter's reserved credit. An agent stops work when its lease lapses.
3. Database first, then side effects: commit the state change and an outbox row in one transaction, then send messages.
4. Everything is idempotent: commands carry a command_id; APIs that create things take an Idempotency-Key header.
5. Two witnesses for money: bill a minute only if the agent reported it AND the control plane saw the lease alive.
6. Least privilege: containers run non-root, read-only root, no host mounts, all capabilities dropped, LAN egress blocked.
7. Reuse proven open source for hard problems: Tailscale/Headscale, Sunshine/Moonlight, Docker, Pingora.
8. Compatible by default: .proto changes are additive only; the control plane supports the last three agent releases.

## Conventions
- Money is integer paise (Java long, Rust i64, SQL bigint). Never floating point.
- IDs are UUIDv7. Times are UTC (timestamptz). Never trust agent clocks for billing; every "now" comes from an injected clock.
- Control plane: one Spring Modulith module per bounded context (identity, hosts, agentgateway, overlay, scheduling, sessions, metering, billing, payouts, trust, notifications). Modules talk only through public APIs or events and never read another module's tables.
- Database: PostgreSQL with Flyway migrations, expand then migrate then contract. Valkey (Redis-compatible) only for state that is allowed to expire.
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

## Working rules
- Measure before fixing; report numbers, not impressions.
- One implementation per rule. Do not build features that nothing calls.
- For changes touching more than two files, show a short plan and wait for approval.
- Record significant decisions as ADRs in docs/decisions/ using 0000-template.md.
- Update this file when commands, layout or conventions change.

## Commands
(filled in as modules are created)
