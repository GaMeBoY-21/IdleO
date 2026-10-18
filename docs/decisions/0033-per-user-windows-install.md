# 0033. Per-user Windows install, no service

- **Status:** Accepted
- **Date:** 2026-10-18

## Context

docs/plan/technical-design.md and docs/plan/host-experience.md §6 row 3 described the Windows host agent as "a Windows
service". Building the Windows app showed three reasons that doesn't work for us:

- A WSL2 distribution belongs to a Windows user account. Our sealed Linux space (agent/distro/) is registered for the
  person who set it up and runs under their account.
- Running WSL from the SYSTEM account isn't a path Microsoft documents or that we can rely on.
- A service runs in session 0, where `GetLastInputInfo` and the full-screen check never see the owner's keyboard,
  mouse or games. Auto-pause (host-experience §5), the thing that makes hosting safe for the owner, would silently
  never fire.

The installers of VS Code, Ollama and many others solve the same problem by installing for one user, with no admin
rights.

## Decision

Everything runs as the signed-in owner:

- **Programs** in `%LOCALAPPDATA%\Programs\IdleO\`: `idleo-launcher.exe`, `current` (the version that runs), and an
  `app-<version>\` folder per installed version, two at most (`idleo_release::paths`).
- **Data** in `%LOCALAPPDATA%\IdleO\`: `agent\` (config, certificate, key, settings), `updates\`, `setup\`,
  `wsl\<distro version>\` (the distribution's disk), `update-state.json` and `logs\`. The agent's Windows data folder
  moves from `%ProgramData%\IdleO\agent` to `%LOCALAPPDATA%\IdleO\agent`.
- **Start-up:** `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\IdleO` runs `idleo-launcher.exe`, which starts and
  watches `idleo-hostd.exe` (the agent's `run`, with no console window) and `idleo-tray.exe`. Setup's resume after a
  restart is a `RunOnce` entry, "IdleO Setup".
- **Uninstall:** `HKCU\...\Uninstall\IdleO` (so it shows in Settings → Apps) runs `idleo-launcher.exe uninstall`.
- **Admin rights** are never needed, except when Windows itself must turn WSL on: `wsl --install --no-distribution`
  raises its own Windows prompt. The programs' manifests say `asInvoker`, so nothing called "Setup" asks for admin.
- **`host.key`'s access list** is SYSTEM plus the owner's own SID, read at run time
  (`D:P(A;;FA;;;SY)(A;;FA;;;<SID>)`, `agent_windows::private_file_sddl`), applied at creation. Administrators is no
  longer listed (ADR 0012's note).
- **The tray talks to the agent** over a named pipe, `\\.\pipe\idleo-<SID>`, whose access list is the owner and SYSTEM
  only (`agent_windows::pipe_sddl`): no port is opened, so nothing at home listens on the network (architecture rule 1).
- **Hosting happens while the owner is signed in**, including while the screen is locked. Signed out, the laptop is
  offline, which the control plane already handles as any offline host.

## Alternatives considered

- **A Windows service running as LocalSystem, with WSL under it.** WSL isn't supported there, and auto-pause can't see
  the owner from session 0. Lost on both.
- **A service plus a per-user helper that reports input and runs WSL.** Two processes doing one job, a privileged
  service to secure, and the same per-user WSL in the end. Lost on complexity, with no benefit while hosts earn only
  when signed in.
- **A machine-wide install in `Program Files` with a per-user part.** Needs admin for every update and for removal.
  Lost: every update would ask the owner for admin rights.

## Consequences

- Updates and removal never ask for admin rights, and nothing is left in machine-wide places to clean up.
- A laptop earns only while its owner is signed in. Most hosts leave their laptops signed in and locked, so this costs
  little now. Open question: how many hosts sign out at night; the self-test and presence numbers will tell us.
- Two Windows users on one laptop would each need their own install and link; that's two hosts.
- **When we'd revisit:** if hosts ask to earn while signed out, and once WSL under a service account is proven (by
  Microsoft's documentation or by our own drills), a service can take over `idleo-hostd`'s job. The launcher, the tray
  and the pipe's shape would stay.
