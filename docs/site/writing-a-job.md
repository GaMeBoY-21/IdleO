---
title: Writing a job
description: What your code sees when {{product}} runs it, and what to leave behind, from inputs and parameters to checkpoints, stopping and failing.
order: 8
path: /docs/writing-a-job/
---

A job is your code, running on someone else's GPU, inside a sealed container. **We provide the GPU. You bring your own model, data and code.** This page is the whole contract between your code and {{product}}, for [Run your Python project](/docs/services/) and for anything you write yourself.

## What your code runs in

- Python 3.12 with PyTorch (built for CUDA 12.8), transformers, peft, trl, accelerate, datasets, bitsandbytes, safetensors, numpy, pandas, scikit-learn and the other common libraries. If your folder has a `requirements.txt`, it is installed before your command runs, so add what you need there. Packages come from the public internet over HTTPS.
- You are a normal user, not root. The system is read-only. The one place you can write, other than a 1 GiB `/tmp`, is `/work`, a scratch disk of the size the service shows. Everything in it is deleted when the session ends.
- There is no compiler and no `sudo`.
- The network is **HTTPS (port 443), HTTP (port 80) and DNS, to the public internet, and nothing else**. You can't reach the host's home network, the host's computer, or other renters. Nothing can connect in to you. There is no IPv6.
- One GPU, and the memory you chose. Memory, CPU and process counts have limits; going over memory ends the session with "out of memory".

## What your code is given

Everything comes as environment variables and folders, so your code reads them like any other setting.

| You get | Where |
| --- | --- |
| A parameter you set in the form | `SESSION_PARAM_<NAME>` (upper case): `SESSION_PARAM_COMMAND`, `SESSION_PARAM_EPOCHS`. Always a string. Never put secrets in a parameter. |
| An input folder or file (upload or link) | The folder `SESSION_INPUT_<NAME>_DIR` (upper case name), read-only, with the paths you uploaded kept. For a service with a `code` input, it is `SESSION_INPUT_CODE_DIR`; the Run-your-code service copies it to `/work/code` and runs your command there. |
| Where to put what you want back | `SESSION_OUTPUT_DIR` (`/work/outputs`). When your code ends, everything in it is uploaded and listed under the session's outputs. |
| Where to save progress | `SESSION_CHECKPOINT_DIR` (`/work/checkpoints`). See below. |
| The progress to carry on from, if this is a later attempt | `SESSION_RESUME_DIR`, unset on a first attempt. |
| How often to save | `SESSION_CHECKPOINT_INTERVAL`, in seconds. |

Your logs are what your code prints, standard output and standard error. They show live and stay for 30 days. A line that is only a progress bar is fine.

## Checkpoints, stopping and moving

Your session can stop for reasons you didn't choose: you press Stop, the host's owner comes back to their laptop, the laptop goes offline, or your budget runs out. Then {{product}} carries on from your last checkpoint on another GPU if it can, and doesn't charge for minutes after it.

To use that:

1. Every few minutes (and at least every `SESSION_CHECKPOINT_INTERVAL` seconds), write a folder `SESSION_CHECKPOINT_DIR/<n>/` with whatever you need to carry on, with `n` going up (1, 2, 3 …). When it is complete, create an empty file `_DONE` inside it. A checkpoint without `_DONE` is ignored, so a crash halfway through writing one never counts.
2. At the start, if `SESSION_RESUME_DIR` is set, load from it instead of starting again. It holds the newest complete checkpoint of the last attempt, as you wrote it.
3. When you are asked to stop, your code gets **SIGTERM** and a short time (about a minute) to finish. Save a last checkpoint and exit with 0. A job that ignores it is stopped hard after that time, and loses what it hadn't saved.

A checkpoint counts only once it has been copied to our storage. The folders stay for 7 days after the session so you can reuse them. A checkpoint can be as large as you need, within the scratch disk, but a very large one takes time to copy, so keep the interval sensible.

[Fine-tune your model](/docs/services/) does all of this for you: it saves on its schedule and on SIGTERM, and resumes by itself.

A small example that does all three (a counting loop that saves every few steps and carries on from where it was):

```python
import json, os, signal, time
from pathlib import Path

out = Path(os.environ["SESSION_OUTPUT_DIR"])
checkpoints = Path(os.environ["SESSION_CHECKPOINT_DIR"])
resume = os.environ.get("SESSION_RESUME_DIR")
step = json.loads((Path(resume) / "state.json").read_text())["step"] if resume else 0
stop = False
signal.signal(signal.SIGTERM, lambda *_: globals().update(stop=True))

def save(n):
    folder = checkpoints / str(n)
    folder.mkdir(parents=True)
    (folder / "state.json").write_text(json.dumps({"step": step}))
    (folder / "_DONE").write_text("")   # last: now it counts

saved = 0
while step < 100:
    step += 1
    time.sleep(1)
    print(f"step {step}", flush=True)
    if step % 10 == 0 or stop:
        saved += 1
        save(saved)
    if stop:
        raise SystemExit(0)
out.mkdir(parents=True, exist_ok=True)
(out / "result.json").write_text(json.dumps({"steps": step}))
```

## How a job ends

| Your code | The session |
| --- | --- |
| Exits 0 | Completed. Your outputs are uploaded, then you are charged for the minutes it ran. |
| Exits with an error, or crashes | Ends with "Your code stopped with an error", and the last lines of your log. Outputs written so far are still uploaded. |
| Exits with 2 after writing `/work/idleo-failure.json` = `{"code":"invalid_input","message":"…"}` | Ends with your message, in one plain line, instead of a bare exit code. Use it when what you were given is wrong ("the data file has no `text` field"). It is the only code your file can set. |
| Uses more memory than you chose | Ends with "Ran out of memory". Ask for a larger GPU class, or less memory per step. |
| Fills the scratch disk | Ends with "Ran out of disk space in /work". |
| Runs past its maximum time | Ends with "Reached the longest time you allowed". |

You are only charged for minutes your job ran on a GPU while it was held for you, by the minute, up to the spending cap you set. Nothing runs, and nothing is charged, after it ends.

## Bring your own image

If your code already lives in a container image with its libraries and CUDA baked in, you can run that image instead of ours: Book, Run your code, **Use my own image**. You need a verified PAN first (Hosting, Payout details); until then the page says so.

1. **Give its reference**, a tag or a digest: `pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime`, `ghcr.io/you/train:v3`, `nvcr.io/nvidia/pytorch:24.10-py3`. From a private registry, give a user name and a **read-only** token that can read only that repository. We keep the token sealed, use it only to download your image, and delete it after 30 days unused. The host your session runs on receives it for that one download.
2. **We check it** on our server before it can reach anyone's machine, usually in a few minutes. It must be `linux/amd64`, at most 15 GB compressed and 127 layers. It's blocked if it can't be downloaded or read, or if any program in any layer is on our list of software that isn't allowed (the acceptable use policy). You also see, without being blocked: its known vulnerabilities (with the full list), anything that looks like a secret baked into it ("Your image seems to contain an AWS key in /app/.env. Anyone with this image can read it."), and whether it runs as root.
3. **Book it**, with a command or without one. A command runs with `/bin/sh -c` in your image's own working directory; left empty, your image's own entrypoint and command run.

What it runs on, and how:

- **The image you checked, exactly.** We resolve your tag to a digest when we check it, and every session runs that digest. Pushing to the tag again changes nothing until you check it again.
- **A host whose driver runs your CUDA.** We read the CUDA version your image needs (`NVIDIA_REQUIRE_CUDA`, else `CUDA_VERSION`) and only offer your session to GPUs whose driver supports it. An image that names no CUDA version runs anywhere.
- **The same sandbox as every job:** user 10001 (not root, whatever your `USER` says), a read-only root file system with a writable `/tmp` and `/work`, no capabilities, and only the internet. Write to `/work` (and your outputs to `$SESSION_OUTPUT_DIR`), not into your image's own folders. Everything under "What your code is given" and "Checkpoints" above holds: `SESSION_*` variables, your files in `/work/input/files/`, checkpoints and outputs.
- **Downloaded at the start, never charged.** The session shows "Downloading your image · 3.1 of 8.4 GB" first. The download isn't charged and doesn't count against your maximum time. If it doesn't finish within 20 minutes the session ends, uncharged. Bring models and data as inputs rather than baking them in: a smaller image starts sooner.
- **Deleted from the host afterwards.** Your image may hold your code, so it's removed from the host's machine when the session ends. A run again downloads it again.

If a newer check of our list blocks an image you've already checked, it can't start new sessions, sessions of it that hadn't started are cancelled without charge, and we tell you. Account, My images lists your images, what each check found, and lets you remove one.

## Seeing what happened

`idleo logs <session>` (or the Logs on the session's page) shows the log; `idleo outputs <session> --download <folder>` fetches the outputs. The [command line](/docs/cli/) and the [Python SDK](/docs/sdk/) do the same from your scripts.
