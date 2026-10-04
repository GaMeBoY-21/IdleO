# phase0

Feasibility work, done before any product code. Phase 0 runs in October 2026, for 2 weeks, on a borrowed Windows RTX laptop and Nikki's Mac.

## The two gates (execution plan line 528)

Phase 1 starts only after both pass.

1. **A game is playable over the internet.** On the phone-hotspot run, the median round trip is under 30 ms (technical design line 735), 1080p60 holds, and fewer than 1% of frames drop.
2. **A QLoRA job finishes inside the sandbox we'll ship.** It runs in the locked-down distribution (`verify-lockdown.sh` passes), under the same sandbox flags renters will get, and survives being killed and resumed.

## Who runs what

| Machine | Who | Runs |
| --- | --- | --- |
| Asus: RTX 4080 Laptop GPU (12 GB), Windows 11 | Nikki, on a borrowed laptop | `wsl/setup-wsl.ps1`, `wsl/setup-docker-nvidia.sh`, the QLoRA run and resume test, Sunshine, `telemetry/gpu_logger.py` |
| Mac | Nikki | Moonlight, and the CPU smoke test of the QLoRA resume logic |

The scripts aren't tied to the Asus: any Windows 11 laptop or desktop with an NVIDIA RTX GPU works.

## Order

1. **`wsl/setup-wsl.ps1`** in an admin PowerShell on the Asus. Creates the `gpu-host-dev` distribution. See `wsl/README.md`.
2. **`wsl/setup-docker-nvidia.sh`** inside `gpu-host-dev`. Installs Docker Engine and the NVIDIA Container Toolkit, and checks `nvidia-smi` in a container.
3. **Apply the lockdown and prove it (required).** Follow `wsl/README.md`:
   - Copy `qlora-test/` and `wsl/` into the distribution.
   - Copy `wsl/wsl.conf.proposed` to `/etc/wsl.conf`.
   - Run `wsl --terminate gpu-host-dev`.
   - Run `bash /root/phase0/wsl/verify-lockdown.sh` as root.

   Every check must say PASS before going on. If any says FAIL, stop and report the output: a QLoRA run outside the lockdown doesn't count toward the gate.
4. **The QLoRA run and resume test, with the logger running.** See `qlora-test/README.md`.
   - **Logger:** on Windows, run `python telemetry\gpu_logger.py --out results\qlora-gpu.csv`.
   - **Full run:** inside `gpu-host-dev`, run `./run.sh` in `/root/phase0/qlora-test/`.
   - **Resume test:** run `./resume-test.sh`, with the logger writing to `results\qlora-resume-gpu.csv`.
5. **The streaming checklist, with the logger running:** `streaming/CHECKLIST.md`. Two people, both machines.

Record everything in `RESULTS.md`. Raw CSVs and `results.json` files go in `results/` and are committed; they hold measurements only.

The logger needs Python 3 on Windows (python.org or the Microsoft Store) and `nvidia-smi` on PATH, which the NVIDIA driver installs. It also works inside WSL.

## Folders

- `streaming/`: the manual game-streaming test.
- `wsl/`: the WSL2 distribution and Docker GPU setup.
- `qlora-test/`: the QLoRA job, which is also our first batch template.
- `telemetry/`: the GPU temperature and power logger.
- `results/`: raw evidence.
