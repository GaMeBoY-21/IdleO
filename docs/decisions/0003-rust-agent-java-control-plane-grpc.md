# 0003. Rust agent, Java control plane, gRPC between them

- **Status:** Accepted
- **Date:** 2026-10-04

Sources: docs/plan/technical-design.md lines 3 and 241–264; docs/plan/execution-plan.md lines 142–169.

## Context

Fast languages go only where every millisecond or megabyte counts: the game stream, the edge proxy and the host agent (execution plan 144). The control plane only books, schedules and bills, a few times per session, so its language does not change what users feel (execution plan 146). The host agent runs 24/7 on someone's laptop (execution plan 153).

## Decision

- **Host agent:** Rust, with tonic for gRPC, bollard for Docker and nvml-wrapper for GPU stats (execution plan 153).
- **Control plane:** Java 25, Spring Boot 4, virtual threads, Spring Modulith (execution plan 156). It is never in the live data path (execution plan 144).
- **Edge and CLI:** Rust (technical design 3; execution plan 152, 163).
- **Link:** one gRPC bidirectional stream over TLS per agent carries every message, with grpc-java on the server (execution plan 157; technical design 221). The hosts open these links themselves (technical design 3).
- **Contracts:** messages are defined once in a .proto file and compiled for both Rust and Java (technical design 221; execution plan 157).

## Alternatives considered

- **Go instead of Rust in every row:** quicker to learn, at some cost in memory use and low-level control (execution plan 168).
- **A fast language for the control plane:** lost, because the control plane books and bills only a few times per session, and Java gives mature security and transactions and is our core stack (execution plan 146, 156).

## Consequences

- The agent is a small single binary with low CPU and RAM, memory-safe on machines we do not control (execution plan 153).
- Typed contracts are shared by Rust and Java through .proto files (execution plan 157).
- When the stream drops, the agent reconnects with exponential backoff, 1 second doubling to 60 seconds, plus random jitter (technical design 240).
- A repeated StartSession with a known command\_id is acknowledged, never run twice (technical design 240).
- Welcome forces an update if the agent is older than the last three releases (technical design 226).

Answered: the control plane builds with Maven (ADR 0008).
