# 0003. Rust agent, Java control plane, gRPC between them

- **Status:** Accepted
- **Date:** 2026-10-04

## Context

Fast languages go only where every millisecond or megabyte counts: the game stream, the edge proxy and the host agent. The control plane only books, schedules and bills, a few times per session, so its language does not change what users feel. The host agent runs 24/7 on someone's laptop.

## Decision

- **Host agent:** Rust, with tonic for gRPC, bollard for Docker and nvml-wrapper for GPU stats.
- **Control plane:** Java 25, Spring Boot 4, virtual threads, Spring Modulith. It is never in the live data path.
- **Edge and CLI:** Rust.
- **Link:** one gRPC bidirectional stream over TLS per agent carries every message, with grpc-java on the server. The hosts open these links themselves.
- **Contracts:** messages are defined once in a .proto file and compiled for both Rust and Java.

## Alternatives considered

- **Go instead of Rust in every row:** quicker to learn, at some cost in memory use and low-level control.
- **A fast language for the control plane:** lost, because the control plane books and bills only a few times per session, and Java gives mature security and transactions and is our core stack.

## Consequences

- The agent is a small single binary with low CPU and RAM, memory-safe on machines we do not control.
- Typed contracts are shared by Rust and Java through .proto files.
- When the stream drops, the agent reconnects with exponential backoff, 1 second doubling to 60 seconds, plus random jitter.
- A repeated StartSession with a known command\_id is acknowledged, never run twice.
- Welcome forces an update if the agent is older than the last three releases.

Answered: the control plane builds with Maven (ADR 0008).
