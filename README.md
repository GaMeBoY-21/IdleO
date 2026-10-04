# GPU Rental Platform

A peer-to-peer GPU marketplace for India. Hosts with RTX gaming laptops and desktops rent out GPU time by the minute, and renters (students, developers, small startups) run AI jobs on them. A Java control plane books, schedules and bills; a Rust agent on each host runs the work; hosts connect outward over gRPC and an encrypted overlay, so nothing at home is exposed. Cloud gaming comes later, only for legally cleared titles.

**Current phase:** Phase 0 (feasibility), then Phase 1 (compute core).

## Layout

```text
control-plane/   Java 21, Spring Boot, Spring Modulith modules
agent/           Rust host agent and the WSL2 distribution build
edge/            Rust edge proxy on Pingora; DERP relay configuration
cli/             Rust command-line client
web/             Web app and admin console
proto/           Shared .proto files, checked by buf
infra/           Terraform, Docker Compose, monitoring configuration
templates/       Template images: Jupyter, vLLM, QLoRA, Blender
phase0/          Phase 0 feasibility work
docs/            Plan, decision records, runbooks, prompts, workflow
```

## Docs

- [Execution plan](docs/plan/execution-plan.md) and [technical design](docs/plan/technical-design.md), in `docs/plan/` (the PDFs beside them contain the diagrams)
- [Decision records](docs/decisions/)
- [How we work](docs/WORKFLOW.md)
