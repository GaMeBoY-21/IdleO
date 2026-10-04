# 0002. Overlay with Tailscale clients and self-hosted Headscale

- **Status:** Accepted
- **Date:** 2026-10-04

Sources: docs/plan/technical-design.md lines 25–26 and 47; docs/plan/execution-plan.md line 151.

## Context

Game streams and endpoint traffic must reach hosts with no router changes (execution plan 151). NAT traversal and encryption are hard to write ourselves (execution plan 151).

## Decision

- The overlay is WireGuard through open-source Tailscale clients, coordinated by our self-hosted Headscale server, with our own DERP relays (execution plan 151).
- Headscale runs in our cloud, admits devices to the overlay and enforces per-session access rules (technical design 25).
- The edge node, one to start in our cloud in Bangalore, runs Tailscale's DERP relay, which relays overlay traffic that cannot connect directly (technical design 26).
- All overlay calls go through one internal module (technical design 47).

## Alternatives considered

Open question: the plan names no alternatives to Tailscale clients and Headscale that were weighed and rejected.

## Consequences

- We get proven NAT traversal and encryption we do not have to write (execution plan 151).
- Headscale is built for smaller self-hosted networks, so its replacement is planned from day one (technical design 47).
- Swapping the coordinator touches only the one overlay module (technical design 47).

Open question: the plan does not say what replaces Headscale, or at what host count.
