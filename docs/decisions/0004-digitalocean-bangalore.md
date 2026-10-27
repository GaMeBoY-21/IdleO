# 0004. DigitalOcean Bangalore for building and the pilot, two zones at Growth

- **Status:** Accepted
- **Date:** 2026-10-04

## Context

The first users are in Bengaluru. Everything that must not be lost runs as managed services in one region, close to them.

## Decision

- Host on DigitalOcean Bangalore while building and during the pilot; move to two availability zones (AWS Mumbai or Hyderabad) at the Growth stage.
- **Build, up to 10 hosts:** one droplet running the control plane, Headscale, cache and monitoring in Docker Compose; a single-node managed database; one small edge node. About $51, or ₹4,900, a month.
- **Pilot, up to 100 hosts:** two control-plane droplets behind a load balancer, managed PostgreSQL with a standby node, managed Valkey, separate Headscale and monitoring droplets, one edge node. About $188, or ₹18,000, a month plus the load balancer.
- **Growth, up to 2,000 hosts:** two availability zones with the database standby in the second zone; read replicas for search and dashboards; monthly partitions for usage tables; two edge nodes per city.
- Object storage sits on Cloudflare R2.

## Alternatives considered

Open question: the plan does not record which other providers were weighed for building and the pilot.

## Consequences

- Close to Bengaluru users, flat pricing and bundled bandwidth for relays.
- Edge nodes and hosts hold nothing that cannot be rebuilt.
- Until Growth, daily backups copied to R2 cover the loss of the whole datacenter.
- Multi-zone resilience waits until revenue justifies it.
- Staging can run on the build-phase droplet instead of a second full environment.

Open question: the plan does not choose between AWS Mumbai and Hyderabad for Growth.
Open question: the load balancer's price is not in the plan; it says "check the current price".
