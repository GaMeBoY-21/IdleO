# qlora-test: the Phase 0 QLoRA job, and our first batch template

This job fine-tunes `Qwen/Qwen2.5-1.5B-Instruct` with 4-bit QLoRA (nf4, LoRA rank 16 on all projection layers). It trains on the first 1,000 examples of `yahma/alpaca-cleaned`, inside a container run with the renter sandbox flags from technical design lines 476–486.

| File | What it does |
| --- | --- |
| `train.py` | The job. Checkpoints every `--save-steps` (default 10) to `/work/checkpoints`. `--resume` continues from the newest complete checkpoint. Writes `/work/results.json` at the end. |
| `Dockerfile` | Pinned base `pytorch/pytorch:2.8.0-cuda12.8-cudnn9-runtime` (by digest), with libraries pinned in `requirements.txt`. |
| `run.sh` | Builds the image and runs it with the sandbox flags. Arguments go to `train.py`. |
| `resume-test.sh` | Starts a run, kills it hard once a checkpoint at or past halfway is saved, resumes, and checks the step count continued. |

The model, tokenizer and dataset are pinned to exact Hugging Face revisions in `train.py`.

## On the Asus, inside gpu-host-dev

```
cd phase0/qlora-test        # or /root/phase0/qlora-test once wsl.conf.proposed is applied
./run.sh                    # full run: 100 steps, batch 4 x 4 accumulation, 512 tokens
./resume-test.sh            # kill-and-resume test on the GPU
```

Start the GPU logger on Windows first (see `../README.md`). Copy `work/results.json` to `../results/` after each run, under a new name.

- **`MEMORY`, `CPUS`, `PIDS` and `GPUS`** override the defaults (`12g`, `6`, `512`, `device=0`). The WSL VM gets half of Windows' RAM by default, so check `free -g` inside the distribution and record what you used.
- **The first run downloads about 3 GB** (the model) into `work/hf-cache`. A resumed run reuses it.

## What results.json records

- `wall_time_s`, `train_time_s`
- `final_step`, `max_steps`, `steps_this_run`
- `tokens_this_run` and `tokens_per_second`: real tokens, not padding
- `peak_vram_mb`, from `torch.cuda.max_memory_allocated`
- `gpu`
- `resumed`, `resumed_from_step`, `first_step_this_run`

## Smoke test on a Mac (no GPU, no Docker)

`--smoke` swaps in a tiny Qwen2 model (`trl-internal-testing/tiny-Qwen2ForCausalLM-2.5`, same tokenizer), skips 4-bit and runs on CPU: 200 steps, a checkpoint every 25. It goes through the same training, checkpoint and resume code as the real run.

```
cd phase0/qlora-test
uv venv .venv --python 3.12        # or: python3.12 -m venv .venv
uv pip install --python .venv/bin/python torch==2.8.0 -r requirements.txt
./resume-test.sh --local --smoke
```

`.venv/` and `work/` are git-ignored.

## The sandbox, and where it differs from the design

The container runs with:
- `--user 10001:10001`
- `--read-only --tmpfs /tmp:size=1g`
- `--cap-drop ALL --security-opt no-new-privileges`
- `--pids-limit 512 --memory 12g --cpus 6`
- `--gpus device=0`
- its own bridge network
- `/work` as the only writable bind mount

With a read-only root, every cache must point into `/work` or `/tmp`, or the job dies on its first download:
- **Hugging Face cache:** `HF_HOME=/work/hf-cache`, set in `train.py`.
- **Triton cache:** `/work/.triton`, because Docker mounts `--tmpfs` as `noexec` by default and Triton loads the kernels it compiles from its cache.
- **Home and user name:** `HOME=/tmp` and `USER=renter` (in the `Dockerfile`), because uid 10001 has no home or passwd entry.

On Linux, `run.sh` gives the `work/` folder to uid 10001 (`chown`), since the container can't write to it otherwise.

Differences from technical design lines 474–488, all expected for Phase 0:

- **Scratch disk:** `/work` is a plain bind-mounted folder. The design uses a fixed-size disk image per session, which the agent creates in Phase 1.
- **Firewall:** the DOCKER-USER rules that block private ranges aren't applied. The network is a plain bridge from the 172.30.0.0/16 pool.
- **Image source:** the image is built locally, not pulled by digest from a signed registry.

### Flags relaxed

None so far. `./resume-test.sh --smoke` passed inside this container with every flag above as designed. That was on a Mac under amd64 emulation, without `--gpus`, so the GPU path is still unproven.

If the GPU run needs a flag relaxed, record which flag, how far and why in `../RESULTS.md`. That's a finding, not a failure.
