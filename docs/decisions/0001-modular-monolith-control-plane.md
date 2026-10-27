# 0001. Modular monolith control plane

- **Status:** Accepted
- **Date:** 2026-10-04

## Context

The system ships as seven deployable units. The control plane handles accounts, host registry, scheduling, sessions, metering, billing, payouts and trust, and also holds the agents' gRPC streams. Two people will run it.

## Decision

The control plane starts as one modular Spring Boot application on Java 25. Spring Modulith enforces module boundaries.

Each agent's stream lands on one control-plane instance. Redis records which instance holds each host, and commands for that host, stored durably in agent\_commands, are published to that instance's channel. If an instance dies, its agents reconnect elsewhere within seconds and the map updates.

## Alternatives considered

- **Many services from the start:** lost, because two people can run one service far more reliably than ten.

## Consequences

- One deployment, one database transaction for a booking and its ledger entries, and simple debugging.
- Modules can later split into services along the same lines. The agent gateway is the first candidate, once agents number in the thousands. At the Scale stage (10,000 hosts and more), agentgateway and scheduling split into their own services and scheduling is sharded by region.
- Host counts at each stage are planning thresholds to confirm with load tests.

Open question: the plan does not define the load test that confirms when the agent gateway should split out.
