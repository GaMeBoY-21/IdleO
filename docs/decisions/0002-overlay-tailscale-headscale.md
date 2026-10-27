# 0002. Overlay with Tailscale clients and self-hosted Headscale

- **Status:** Accepted
- **Date:** 2026-10-04

## Context

Game streams and endpoint traffic must reach hosts with no router changes. NAT traversal and encryption are hard to write ourselves.

## Decision

- The overlay is WireGuard through open-source Tailscale clients, coordinated by our self-hosted Headscale server, with our own DERP relays.
- Headscale runs in our cloud, admits devices to the overlay and enforces per-session access rules.
- The edge node, one to start in our cloud in Bangalore, runs Tailscale's DERP relay, which relays overlay traffic that cannot connect directly.
- All overlay calls go through one internal module.

## Alternatives considered

Open question: the plan names no alternatives to Tailscale clients and Headscale that were weighed and rejected.

## Consequences

- We get proven NAT traversal and encryption we do not have to write.
- Headscale is built for smaller self-hosted networks, so its replacement is planned from day one.
- Swapping the coordinator touches only the one overlay module.

Open question: the plan does not say what replaces Headscale, or at what host count.
