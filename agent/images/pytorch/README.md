# The job environment image

What a renter's job runs in (ADR 0035). **We provide the GPU; renters bring their own model, data and code (ADR 0022):** nothing
in this image is a model, and no job script here downloads one.

| What | Why |
| --- | --- |
| `nvidia/cuda:12.8.1-cudnn-runtime-ubuntu24.04`, pinned by digest | CUDA 12.8 is the first with Blackwell (RTX 50) support, and every CUDA 12.x application runs on drivers from 527.41 (Windows) by minor version compatibility: `min_nvidia_driver()` in `agent/release/src/drivers.rs`, from `api/cuda-drivers.json`, says where this is sourced. cuDNN comes with the base. |
| Python 3.12, PyTorch 2.11.0 for CUDA 12.8 (`+cu128`), transformers, peft, trl, accelerate, datasets, bitsandbytes, safetensors, sentencepiece, tokenizers, numpy, scipy, pandas, scikit-learn, tqdm, huggingface_hub | The common AI libraries. `requirements.lock` pins every package with the hash of every wheel; the image installs with `pip install --require-hashes --only-binary=:all:`, so it is the same bytes every build. PyPI's newest torch is built for CUDA 13 (needing a newer driver than we ask for) and the cu128 index stops at 2.11.0, so that is the pin. |
| No compiler, no `sudo`, no SSH | Wheels only, installed in a build stage; nothing a job could build with is left. |
| User 10001, home and caches under `/work` | The container's root is read-only; `/work` (the session's scratch disk) is the one writable place besides `/tmp`. |
| `/opt/idleo/finetune.py`, `run.sh`, `failure.sh` | The two jobs below. |

## The jobs

`finetune.py` (the **fine-tune** service) is QLoRA with transformers, peft and bitsandbytes on the renter's model folder (a
Hugging Face folder: `config.json` and safetensors weights) and data file (JSONL, a `text` field or chat `messages` on each
line). It checks the inputs before loading anything and says what's wrong in one plain line (exit 2; the runner ends the
session `invalid_input` with that line). It saves a checkpoint every `SESSION_CHECKPOINT_INTERVAL` seconds and on SIGTERM,
resumes from `SESSION_RESUME_DIR` with the trainer's own resume (epochs and optimizer carry on), logs
`epoch 2/3 step 40/72 loss 1.2345 lr 0.0002 grad_norm 0.412` (the line the live screen draws its chart from) and leaves
`adapter_model.safetensors`, `adapter_config.json`, `training_log.jsonl` and, if it changed it, the tokenizer.

`run.sh` (the **run-code** service) copies the renter's folder from `SESSION_INPUT_CODE_DIR` (read-only) to `/work/code`,
installs its `requirements.txt` into `/work/.venv` if it has one (the venv also sees everything this image carries), and runs
the `Command` parameter (`python main.py`) there. It is the container's first process, so it passes SIGTERM on to the command.

Both follow the job contract, written for renters in `docs/site/` ("Writing a job for IdleO"): inputs, parameters, outputs,
checkpoints, SIGTERM, exit codes.

## Building and testing

```sh
agent/images/pytorch/lock.sh          # resolve requirements.in into requirements.lock (and the development variant's)
agent/images/pytorch/test.sh          # build for linux/amd64 and test it: imports, user, read-only root, no compiler,
                                      # the tiny CPU fine-tune stopped by SIGTERM, resumed and finished, Run your code
agent/images/pytorch/test.sh --dev    # the same for the development variant
scripts/images/publish.sh --dev       # push the development variant to the local registry, write the stack's catalog
scripts/images/publish.sh --release --registry ghcr.io/OWNER/idleo-pytorch   # push the image that ships, pin the catalog
```

**The development variant** (`requirements.dev.lock`, `--build-arg BASE=ubuntu:24.04@… LOCK=requirements.dev.lock
TORCH_INDEX=…/cpu`) is the same recipe for linux/arm64 with CPU-only PyTorch on a plain Ubuntu base. A Mac runs containers
natively that way (the development distribution is arm64 too: `agent/distro/README.md` says why); it is never shipped.

**The tiny CPU fine-tune** needs no download: `tests/make_tiny_model.py` writes a few-layer random Llama and a tokenizer
trained on five lines. `finetune.py` trains on the CPU only with `--test-cpu` (or `IDLEO_TEST_CPU=1`) and only when no GPU is
visible, so a real laptop always trains for real.
