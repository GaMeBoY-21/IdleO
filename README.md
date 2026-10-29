# GPU Rental Platform

[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/GaMeBoY-21/IdleO/badge)](https://scorecard.dev/viewer/?uri=github.com/GaMeBoY-21/IdleO)

A peer-to-peer GPU marketplace for India. Hosts with RTX gaming laptops and desktops rent out GPU time by the minute, and renters (students, developers, small startups) run AI jobs on them. A Java control plane books, schedules and bills; a Rust agent on each host runs the work; hosts connect outward over gRPC and an encrypted overlay, so nothing at home is exposed. Cloud gaming comes later, only for legally cleared titles.

**Current phase:** Phase 0 (feasibility), then Phase 1 (compute core).

## Run everything

One command starts the whole system on your machine, for local development only (it never touches production settings or real money):

```sh
./start.sh
```

It checks Docker, Java 25, Node 24.21.0 and Rust are in place and the ports are free, then starts PostgreSQL, Valkey, object storage, the local sign-in server and Mailpit (`control-plane/compose.yaml`), the control plane, two laptops' fake GPUs (RTX 4060 Laptop, 8 GB; RTX 4080 Laptop, 12 GB), the web app's dev server and the website. When everything answers, it prints where each is and opens the website, whose "Get started" leads into the app:

- website http://localhost:3002/, web app http://localhost:3000, email http://localhost:8025, admin console http://localhost:3000/admin/;
- sign in as `nikki@example.com` (₹500, owns both laptops), `nikki-low@example.com` (₹1) or `nikki-broke@example.com` (₹0), with no invite step;
- every service's log in `logs/`.

The first run installs and builds what's missing; later runs start in under a minute. Ctrl-C stops everything, containers too (their data is kept). From another terminal: `./start.sh --stop` and `./start.sh --status`. `./start.sh --fresh` wipes the local data first (it asks), `./start.sh --real-sign-in` adds Google sign-in from `control-plane/.env` and `web/.env.local` (docs/runbooks/identity-provider-setup.md), and `--no-browser` doesn't open one. Every check CI runs, on this machine: `scripts/check-all.sh` (docs/runbooks/local-stack.md).

## Layout

```text
control-plane/   Java 25, Spring Boot 4, Spring Modulith modules
agent/           Rust host agent and the WSL2 distribution build
edge/            Rust edge proxy on Pingora; DERP relay configuration
cli/             Rust command-line client
web/             Web app and admin console
proto/           Shared .proto files, checked by buf
infra/           Terraform, Docker Compose, monitoring configuration
templates/       Template images: Jupyter, vLLM, QLoRA, Blender
phase0/          Phase 0 feasibility work
docs/            Decision records, runbooks, reference, site content, workflow
```

## Docs

- [Personal data map](docs/reference/personal-data-map.md)
- [Decision records](docs/decisions/)
- [How we work](docs/WORKFLOW.md)

## Licence

All rights reserved. The code is published for transparency and may not be copied or reused; see [LICENSE](LICENSE). Outside contributions aren't accepted ([CONTRIBUTING.md](CONTRIBUTING.md)). To report a security problem, see [SECURITY.md](SECURITY.md).
