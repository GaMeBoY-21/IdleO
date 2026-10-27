# wsl: the GPU sandbox distribution

Sets up a dedicated WSL2 distribution, `gpu-host-dev`, on the Asus with Docker Engine and the NVIDIA Container Toolkit. Containers on hosts will run in a distribution like this one.

## Order

1. **In an admin PowerShell**, from this folder:
   ```
   powershell -ExecutionPolicy Bypass -File .\setup-wsl.ps1
   ```
   - It checks the Windows build and that the NVIDIA driver supports WSL and CUDA 12.8, installs or updates WSL, and creates `gpu-host-dev` from Canonical's Ubuntu 24.04 WSL image (checksum verified).
   - If WSL was missing, it asks for a Windows restart; run it again afterwards.
   - It never touches any other distribution.
   - Run it from the Windows account you normally use, since WSL distributions are registered per user.
2. **Inside the distribution**, use the command that `setup-wsl.ps1` prints:
   ```
   wsl -d gpu-host-dev -u root -- bash '/mnt/c/.../phase0/wsl/setup-docker-nvidia.sh'
   ```
   - The first run turns on systemd and exits, asking you to run `wsl --terminate gpu-host-dev`. Then run the same command again.
   - It installs Docker Engine and the NVIDIA Container Toolkit from their official apt repositories, then runs `nvidia-smi` in a pinned CUDA 12.8 container.
   - Seeing the Asus's GPU in that output is the pass. The pinned image and the driver check live in `gpu-common.sh`, shared with `verify-lockdown.sh`.

**Never install a Linux NVIDIA driver inside the distribution.** The GPU driver comes from Windows and appears under `/usr/lib/wsl/lib`, and a Linux driver installed inside breaks GPU access. The script checks for one and refuses to continue if it finds one.

The distribution runs as `root` by default, because imported distributions do. That's fine for Phase 0; on real hosts the agent will manage the distribution.

## The shipped lockdown (`agent/distro/wsl.conf`), applied by hand

`agent/distro/wsl.conf` is copied from the design. It turns off drive mounting and Windows interop, and keeps systemd on. **It isn't applied automatically.**

Once it's applied, the distribution can no longer see `C:` (no `/mnt/c`), and it can't launch Windows programs. So:

1. **Copy the test files, this folder and the lockdown files in first.** `verify-lockdown.sh` (now in `agent/distro/`, shared with the sealed image) must be inside before `/mnt/c` disappears:
   ```
   mkdir -p /root/phase0 /root/agent
   cp -r /mnt/c/<path-to-repo>/phase0/qlora-test /mnt/c/<path-to-repo>/phase0/wsl /root/phase0/
   cp -r /mnt/c/<path-to-repo>/agent/distro /root/agent/
   ```
2. **Apply the lockdown:**
   ```
   cp /mnt/c/<path-to-repo>/agent/distro/wsl.conf /etc/wsl.conf
   ```
3. **From Windows**, run `wsl --terminate gpu-host-dev`, then start it again with `wsl -d gpu-host-dev -u root`.
4. **Prove it took effect**, as root (not through `sudo`, which resets PATH):
   ```
   bash /root/agent/distro/verify-lockdown.sh
   ```
   - Every check must say PASS, and the script must exit 0.
   - It checks: no Windows drives, interop off, no Windows PATH, systemd running, and the GPU still working in the pinned CUDA container.
   - Paste its full output into `../RESULTS.md`. If anything says FAIL, stop: the QLoRA test doesn't count until this passes.
   - It allows exactly one Windows mount: the read-only GPU driver store under `/usr/lib/wsl`, which the GPU needs. Any other 9p or drvfs mount fails the check; if one shows up, paste the evidence lines so we can tell what it is.
5. **Run the QLoRA test** from `/root/phase0/qlora-test` (see `../qlora-test/README.md`).
6. **Get the results out from Windows**, since `\\wsl$` still works. In File Explorer, open:
   ```
   \\wsl$\gpu-host-dev\root\phase0\qlora-test\work\results.json
   ```
   Copy `results.json` into `phase0\results\` in the repo on Windows.

To undo the lockdown, edit `/etc/wsl.conf` inside the distribution (set both `enabled` values back to `true`), then run `wsl --terminate gpu-host-dev`.

## Not in Phase 0

- **The firewall rules** in the design (blocking private ranges from containers) aren't applied yet.
- **The idle shutdown** after 5 minutes is the agent's job, in Phase 1.
