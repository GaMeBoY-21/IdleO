# 0006. Build the final product to a production quality bar

- **Status:** Accepted
- **Date:** 2026-10-04

Source: the product owner's decision of 2026-10-04.

## Context

The product owner chose to build the final product, not an MVP. The roadmap's order still holds: payments wait for the company, and paid gaming waits for the lawyer. But nothing built along the way is a throwaway.

## Decision

Every piece is built to production standard from the start:

- No temporary hacks or dead features.
- Tests: unit, integration (Testcontainers), contract and end-to-end, plus failure drills.
- Security: mTLS, short-lived tokens, secrets never in code, signed releases, rate limits.
- Data: Flyway migrations and restore-tested backups.
- Operations: metrics, logs and traces, with an alert and a runbook for every user-facing failure.
- Delivery: CI on every change, staging, one-click deploy and rollback.
- Web app: a real design system, accessible and mobile-friendly, with proper loading, error and empty states.

Phase 1 becomes the compute core: batch jobs end to end, built to production standard, on free credits until the company and payment partner exist. The web app's renter and host flows are built properly in Phase 1; the admin console follows in Phase 2.

## Alternatives considered

- **An MVP first, hardened later:** rejected by the product owner on 2026-10-04.

## Consequences

- Steps are smaller and slower, but nothing is rebuilt later.
- CLAUDE.md carries the bar in its "Quality bar" section, so every session starts with it.
