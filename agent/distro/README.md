# The sealed Linux image

The Windows installer will import this into WSL2 as its own distribution, `idleo`, so that renters' jobs run in a Linux
space that is ours, locked down, and never the Store's or downloaded at install time (docs/plan/host-experience.md §2).
This folder builds it, now, with tests; the installer that imports it comes with the Windows app.

## What is in it, and why

| What | Why |
| --- | --- |
| Ubuntu 24.04, minimal, pinned by digest (`Dockerfile`) | A base we can name exactly and rebuild. |
| systemd | `wsl.conf` turns on WSL's systemd, which starts containerd; the image has nothing else that starts itself. |
| containerd and runc, at pinned versions | The container runtime the runner starts each job with. No Docker Desktop, no Docker daemon. |
| The NVIDIA container toolkit, pinned, from NVIDIA's repository, whose key is checked by fingerprint | Lets a container see the GPU through the Windows driver (`/usr/lib/wsl`). **No NVIDIA driver is installed**: a Linux driver inside WSL breaks GPU access (`phase0/wsl/gpu-common.sh` checks). |
| `wsl.conf` (`agent/distro/wsl.conf`) | The lockdown: no Windows drives mounted, no way to launch Windows programs, no Windows PATH. The same file Phase 0 applies by hand on the Asus. |
| `verify-lockdown.sh` and `gpu-common.sh` in `/usr/local/share/idleo/` | The proof that the lockdown took effect, runnable inside the image on a real laptop. One file, `agent/distro/verify-lockdown.sh`, used here and by Phase 0. |
| The `idleo` system user, no login shell, in no other group | What jobs run as. |
| The GPU-check image, `/opt/idleo/images/gpu-check.tar` (`gpu-check.Dockerfile`), imported into containerd on first boot by `idleo-images.service` (`import-images.sh`) as `docker.io/idleo/gpu-check:local` | The self-test's "tiny CUDA job" until the real runner brings a CUDA kernel: run with the GPU, it prints the GPU's name and memory through the `nvidia-smi` and driver libraries the toolkit injects, which proves a container sees the GPU. It holds nothing of its own, from the same pinned Ubuntu base. Open question: replace it with a real CUDA kernel (a vector add) once the runner's image exists. |
| nerdctl and the three CNI plugins a session network needs (bridge, host-local, loopback), each download checked against a SHA-256 in the `Dockerfile`; nftables | What the runner starts containers with, and the firewall that keeps a job off the home network. No Docker. |
| `/usr/local/bin/idleo-runner`, built from `agent/runner/`, with `idleo-sandbox.service` (loads `sandbox.nft`, the one rules file) and `idleo-runner.service` | The runner (ADR 0035): a root-only socket the agent reaches over `wsl.exe`, which runs, limits and cleans up each job, and refuses to start one unless the rules are loaded. |
| **Not in it:** an SSH server, a compiler, `sudo`, package caches, documentation, man pages | Nothing a job could use to get out or build something, and a smaller download. |

## Building it

```sh
scripts/build-distro.sh             # dist/distro/idleo-distro-<version>.tar.gz, its .sha256, .manifest.json and .packages.txt
scripts/build-distro.sh --no-cache  # everything again
```

- **Always for `linux/amd64`** (`docker buildx build --platform linux/amd64`): the laptops are x86-64, and the Mac that builds
  this is Apple Silicon, so on a Mac the build runs under emulation and takes a few minutes.
- The version is `agent/distro/VERSION`, the one place it is written.
- The GPU-check image is built first (also `linux/amd64`), `docker save`d and copied in.
- The root filesystem is `docker export`ed and gzipped without a timestamp. The manifest has the version, the base
  digest, every package with its version, the size, the SHA-256 and the build date.
- **Signing** comes with the code-signing certificate: the one place is `sign_artifact` in `scripts/build-distro.sh`
  (`TODO(signing)`). Until then the installer can only check the SHA-256 and the manifest.
- To move a pin forward (a base, containerd, the toolkit), change it in the `Dockerfile`, build, and run the test.

## Testing it

`agent/distro/test.sh` (`--quick` skips the second build) checks that the image builds; its `wsl.conf` is the repository's;
there is no `sshd`, compiler or `sudo`; the `idleo` user exists, is in no other group and can't log in; containerd and the
NVIDIA container toolkit are the pinned versions and `nvidia-ctk` runs; the runner is the real one, with nerdctl, the CNI plugins and nftables at their pins and the units and rules the repository's; the GPU-check image is baked in for `linux/amd64` with its `nvidia-smi` entry point and the
service that imports it; the compressed
tarball is under 400 MB; and two builds from the same inputs have the same package list. `scripts/check-all.sh` runs it
as the `distro` suite, which downloads a few hundred MB, so there is no CI job for it yet and `--no-distro` leaves it out.

**Reproducibility:** the package list is reproducible, because everything is pinned. The tarball is not yet
byte-for-byte reproducible (file times and the order of some metadata differ between builds); that isn't asked for yet.

## How the Windows app uses it (ADR 0034)

The tarball is the release feed's `distro`, with its own version, size and SHA-256 in the signed manifest. Setup (and an
update that changes it) runs

```
wsl --import IdleO-<version> %LOCALAPPDATA%\IdleO\wsl\<version> idleo-distro-<version>.tar.gz --version 2
```

then starts the distribution so systemd, containerd and the image import come up, and the agent's self-test checks it
is registered, its `wsl.conf` is ours, the GPU is visible, containerd answers, the GPU-check image sees the GPU and
`verify-lockdown.sh` passes. An update imports the new version beside the old and removes the old one only once the new
app's trial is confirmed. Uninstalling unregisters every `IdleO-…` distribution.

## Still to come

- **The development distribution** (`scripts/build-distro.sh --dev`, `scripts/dev-distro.sh`): the same image with a marker that lets a job run without a GPU, built for the Mac's own architecture (arm64), because containers can't nest under Rosetta; it is how the runner is tested without Windows. Never shipped.
- **Signing** the tarball itself (the release manifest's signature covers its SHA-256 already).
- The first run of this on the Asus (`docs/windows-laptop-test.md`).
