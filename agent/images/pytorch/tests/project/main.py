"""A small project for "Run your code" (docs/site "Writing a job for IdleO", the job image's tests and the end-to-end test):
it counts to a number of steps, logs each, writes a checkpoint every few, saves one more and stops on SIGTERM, resumes from
the checkpoint it's given, and leaves what it did in the outputs. Standard library only.
"""
import json
import os
import signal
import time
from pathlib import Path

STEPS = int(os.environ.get("SESSION_PARAM_STEPS", "40"))
EVERY = int(os.environ.get("SESSION_PARAM_CHECKPOINT_EVERY", "5"))
PAUSE = float(os.environ.get("SESSION_PARAM_PAUSE", "0.5"))
OUTPUTS = Path(os.environ["SESSION_OUTPUT_DIR"])
CHECKPOINTS = Path(os.environ["SESSION_CHECKPOINT_DIR"])
RESUME = os.environ.get("SESSION_RESUME_DIR")

stop = False


def on_term(_signal, _frame):
    global stop
    stop = True


signal.signal(signal.SIGTERM, on_term)
step = 0
if RESUME:
    step = json.loads((Path(RESUME) / "state.json").read_text())["step"]
    print(f"resumed from step {step}", flush=True)
saved = 0
resumed_from = step


def checkpoint():
    global saved
    saved += 1
    folder = CHECKPOINTS / str(saved)
    folder.mkdir(parents=True)
    (folder / "state.json").write_text(json.dumps({"step": step}))
    (folder / "_DONE").write_text("")
    print(f"checkpoint {saved} at step {step}", flush=True)


for step in range(step + 1, STEPS + 1):
    time.sleep(PAUSE)
    print(f"step {step}/{STEPS} loss {1.0 / step:.4f}", flush=True)
    if step % EVERY == 0:
        checkpoint()
    if stop:
        checkpoint()
        print("stopped after a checkpoint", flush=True)
        raise SystemExit(0)

OUTPUTS.mkdir(parents=True, exist_ok=True)
(OUTPUTS / "result.json").write_text(json.dumps({"steps": STEPS, "resumed_from": resumed_from, "command": os.environ.get("SESSION_PARAM_COMMAND")}))
print("done", flush=True)
