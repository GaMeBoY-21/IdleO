# templates

What renters can run. `catalog.yaml` is the only source of templates in Phase 1 (ADR 0015):
- it's reviewed in git and bundled into the control plane at build time;
- the control plane refuses to start if an entry is malformed or an image isn't pinned by digest;
- renters see it at `GET /v1/templates`.

Each template also describes, once, what it takes (ADR 0019): its parameters (name, label, help, type, default and limits) and its input (none, optional or required; an upload, an https URL or both). The web app's form, the CLI's `--param` flags and the SDK's arguments are all made from `GET /v1/templates`, never written by hand per surface. The control plane checks every booking and quote against it with one validator.

How a job gets them:
- each parameter is the environment variable `SESSION_PARAM_<NAME>`, and nothing else is put in the environment; never put a secret in a parameter;
- an uploaded input is staged by the agent at `/work/input/<name>`, named in `SESSION_INPUT_FILE`, before the job starts;
- a URL input is `SESSION_INPUT_URL`, and the job fetches it itself, inside its sandbox, where private networks are blocked. The agent never fetches a renter's URL, because it runs outside the sandbox and could reach the host's home network.

`development.yaml` is the development runner's catalog, for local runs, the tests and the end-to-end test; its `echo-input` template takes a greeting, a repeat count and a required input, and writes back what it saw.

The first entry is a small smoke test on the CUDA base image Phase 0 pins. More batch templates follow, starting with QLoRA (`phase0/qlora-test/`) once its image is published. Notebook and endpoint templates (Jupyter, vLLM) start in Phase 2. cosign signatures are verified once the registry exists (technical design line 83).
