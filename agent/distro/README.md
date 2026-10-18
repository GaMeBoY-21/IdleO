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
| `/usr/local/bin/idleo-runner`, a placeholder | The real runner comes with the GPU round. Until then it says "not installed yet" and fails, so nothing can mistake the image for one that runs jobs. |
| **Not in it:** an SSH server, a compiler, `sudo`, package caches, documentation, man pages | Nothing a job could use to get out or build something, and a smaller download. |

## Building it

```sh
scripts/build-distro.sh             # dist/distro/idleo-distro-<version>.tar.gz, its .sha256, .manifest.json and .packages.txt
scripts/build-distro.sh --no-cache  # everything again
```

- **Always for `linux/amd64`** (`docker buildx build --platform linux/amd64`): the laptops are x86-64, and the Mac that builds
  this is Apple Silicon, so on a Mac the build runs under emulation and takes a few minutes.
- The version is `agent/distro/VERSION`, the one place it is written.
- The root filesystem is `docker export`ed and gzipped without a timestamp. The manifest has the version, the base
  digest, every package with its version, the size, the SHA-256 and the build date.
- **Signing** comes with the code-signing certificate: the one place is `sign_artifact` in `scripts/build-distro.sh`
  (`TODO(signing)`). Until then the installer can only check the SHA-256 and the manifest.
- To move a pin forward (a base, containerd, the toolkit), change it in the `Dockerfile`, build, and run the test.

## Testing it

`agent/distro/test.sh` (`--quick` skips the second build) checks that the image builds; its `wsl.conf` is the repository's;
there is no `sshd`, compiler or `sudo`; the `idleo` user exists, is in no other group and can't log in; containerd and the
NVIDIA container toolkit are the pinned versions and `nvidia-ctk` runs; the runner is the placeholder; the compressed
tarball is under 400 MB; and two builds from the same inputs have the same package list. `scripts/check-all.sh` runs it
as the `distro` suite, which downloads a few hundred MB, so there is no CI job for it yet and `--no-distro` leaves it out.

**Reproducibility:** the package list is reproducible, because everything is pinned. The tarball is not yet
byte-for-byte reproducible (file times and the order of some metadata differ between builds); that isn't asked for yet.

## How the installer will use it

```
wsl --import idleo <path> idleo-distro-<version>.tar.gz --version 2
```

then starts the distribution so systemd and containerd come up, and runs `verify-lockdown.sh` as part of its self-test.

## Still to come

- **The real runner** (`idleo-runner`): starts each job as a container with the GPU, the sandbox flags, no network except our
  storage, and the scratch disk. Today's `--dev-runner` only simulates jobs.
- **Signing**, and a release feed the installer downloads the tarball from.
- The Windows installer, the tray app, and the first run of this on the Asus.
