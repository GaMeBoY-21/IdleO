# 0061. The Windows app: a window, host settings the agent enforces, and a setup that can't be left half done

- **Status:** Accepted.
- **Date:** 2026-11-17

Sources: ADRs 0033 (per-user install), 0034 (signed releases), 0035 (the runner), 0056 (staged rollouts), 0060 (the design system); `docs/windows-laptop-test.md`.

## Context

Hosts only had a right-click menu, a setup that showed one screen per failure with words that sometimes needed the support team, and no way to say when the laptop may be used, how hot it may get or how much disk it may take. The aim: install in about 3 clicks, see at a glance what the laptop is doing and earning, control it without a browser, and never think about it again, through sleep, network changes, games, updates, low disk and crashes.

## Decision

- **One window, in the IdleO design.** The tray's left click opens a 400 × 600 WebView2 window (the setup window's runtime) with Now, Schedule, Limits, Earnings and Settings tabs, in the tray process. The shared tokens, the Inter font and the page builder are in `agent/brand`; setup and the window are two pages on them. Windows' own light or dark is followed unless the host chose a theme; "Show animations" off reaches the page as reduced motion; the title bar is native; every control has a name for Narrator.
- **The agent owns the settings.** `HostSettings` (`agent/release/src/settings.rs`) is stored in `settings.toml`, travels on the local channel (additive fields, protocol version 1), and is enforced by the agent's availability rules like owner activity is: outside the schedule's hours, a metered connection the host refused, a GPU over the host's temperature (resuming five degrees cooler, with no quiet wait) and the Linux space's disk limit each pause hosting within seconds. The host's heat limit also lowers the control plane's cap on offers, and the memory limit declines jobs that ask for more than the share. Settings from an older file read with the new choices at their defaults.
- **Earnings come from the control plane.** `HostStatus` gains this week, this month, the last five jobs (length and earnings only), the next payout date and the latest payout (additive). The agent keeps the last figures on disk, so the window shows them with their age while offline.
- **Seven tray states, each its own shape and colour**, drawn in code at the size the taskbar asks for. Toasts (live, first job, a job paused because the host came back once a day, a payout, an update, a problem the host must fix) are decided by one pure function of the status stream (`agent/tray/src/notify.rs`), obey a switch each, and open the right tab through the per-user `idleo:` address.
- **Setup is idempotent and resumable.** Every way it can go wrong is a `Problem` with plain words, one button and a reason code (`reasons.rs`, the committed `api/install-reasons.json`). A dropped connection is retried by itself (2, 4, 8 s) before the host is asked. Any failure can save a scrubbed diagnostics zip (`idleo_release::diagnostics`: logs, settings, a few lines about the laptop; never a key, a certificate or a renter's file). `IdleO-Setup-Demo.exe` (or `--demo`) walks every screen with pretend progress and is handed no machine, so it cannot change the PC; `--smoke` opens the window, waits for its page to load and exits, which is what CI runs.
- **Quit and crashes.** Quit IdleO stops a running job after its checkpoint and exits with a code that tells the launcher to stop everything. Three crashes of one program in ten minutes write `crash-loop.json`; the tray shows a problem with Send diagnostics until the program has stayed up ten minutes.
- **What is kept safe as it was:** the per-user install, no admin rights beyond Windows' own WSL prompt, signed releases, the pipe's and key file's access lists, the sandbox.

## Consequences

The window, the schedule and the limits are one implementation each, tested on any system. What only a real laptop proves is listed step by step in `docs/windows-laptop-test.md`. A paused job from the schedule or the heat ends as `owner_returned`, like a host coming back: the renter's session carries on elsewhere. A job image clean-up removes images the Welcome no longer names when the Linux space is at 80% of its limit.
