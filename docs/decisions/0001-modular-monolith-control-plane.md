# 0001. Modular monolith control plane

- **Status:** Accepted
- **Date:** 2026-10-04

Source: docs/plan/technical-design.md lines 18–47.

## Context

The system ships as seven deployable units (line 20). The control plane handles accounts, host registry, scheduling, sessions, metering, billing, payouts and trust, and also holds the agents' gRPC streams (line 24). Two people will run it (line 20).

## Decision

The control plane starts as one modular Spring Boot application on Java 21 (lines 20, 24). Spring Modulith enforces module boundaries (line 32).

Each agent's stream lands on one control-plane instance. Redis records which instance holds each host, and commands for that host, stored durably in agent\_commands, are published to that instance's channel. If an instance dies, its agents reconnect elsewhere within seconds and the map updates (line 34).

## Alternatives considered

- **Many services from the start:** lost, because two people can run one service far more reliably than ten (line 20).

## Consequences

- One deployment, one database transaction for a booking and its ledger entries, and simple debugging (line 32).
- Modules can later split into services along the same lines (line 32). The agent gateway is the first candidate, once agents number in the thousands (line 32). At the Scale stage (10,000 hosts and more), agentgateway and scheduling split into their own services and scheduling is sharded by region (line 45).
- Host counts at each stage are planning thresholds to confirm with load tests (line 38).

Open question: the plan does not define the load test that confirms when the agent gateway should split out.
