# templates

What renters can run. `catalog.yaml` is the only source of templates in Phase 1 (ADR 0015):
- it's reviewed in git and bundled into the control plane at build time;
- the control plane refuses to start if an entry is malformed or an image isn't pinned by digest;
- renters see it at `GET /v1/templates`.

The first entry is a small smoke test on the CUDA base image Phase 0 pins. More batch templates follow, starting with QLoRA (`phase0/qlora-test/`) once its image is published. Notebook and endpoint templates (Jupyter, vLLM) start in Phase 2. cosign signatures are verified once the registry exists (technical design line 83).
