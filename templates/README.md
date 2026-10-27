# templates

What renters can run. `catalog.yaml` is the only source of templates in Phase 1 (ADR 0015):
- it's reviewed in git and bundled into the control plane at build time;
- the control plane refuses to start if an entry is malformed or an image isn't pinned by digest;
- renters see it at `GET /v1/templates`.

We provide the GPU; renters bring their own model, data and code (ADR 0022). Every template follows that: it takes the renter's model as an input and never supplies one.

Each template also describes, once, what it takes (ADR 0019, ADR 0022): its named inputs (`model`, `data`: a folder or a file; an upload, an https link or both; required or not), its plain questions (presets that set several parameters at once, basic parameters, GPU memory), its parameters (name, label, help, type, group, default and limits) and its budget chips. `catalog.yaml`'s header lists every field. The web app's form, the CLI's `--param` flags and the SDK's arguments are all made from `GET /v1/templates`, never written by hand per surface. The control plane checks every booking and quote against it with one validator.

How a job gets them:
- each parameter is the environment variable `SESSION_PARAM_<NAME>`, and nothing else is put in the environment; never put a secret in a parameter;
- an uploaded input is staged by the agent in `/work/input/<name>/`, its files' paths kept, named in `SESSION_INPUT_<NAME>_DIR`, before the job starts;
- a link input is `SESSION_INPUT_<NAME>_URL`, and the job fetches it itself, inside its sandbox, where private networks are blocked. The agent never fetches a renter's link, because it runs outside the sandbox and could reach the host's home network.

`development.yaml` is the development runner's catalog, for local runs, the tests and the end-to-end test. It has the production services, word for word as `catalog.yaml` has them (a control-plane test fails if they differ), each with one template the development runner simulates (`--simulate <kind>`: the real template's inputs, questions and parameters, generated log lines and outputs, 90 to 120 seconds), so the local stack looks like the real thing. Beside them, "Test jobs": among others, `echo-input` takes a greeting, a repeat count and a required file, and writes back what it saw.

The first entry is a small smoke test on the CUDA base image Phase 0 pins. More batch templates follow, starting with QLoRA (`phase0/qlora-test/`) once its image is published. Notebook and endpoint templates (Jupyter, vLLM) start in Phase 2. cosign signatures are verified once the registry exists.
