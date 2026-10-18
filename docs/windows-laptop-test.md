# Trying the Windows app on a laptop

A checklist for the first real install, on a Windows RTX laptop on the same Wi-Fi as the Mac. Everything the laptop
reports lands in the Mac's database, so there's nothing to copy off the laptop.

## Before you start (on the Mac)

1. `./start.sh --lan`. The first time it builds the Windows app and the sealed Linux image, which takes several
   minutes. When it's ready it prints the Mac's address, for example `http://192.168.1.20:3002`.
2. If macOS asks to allow incoming connections (Java, node), click **Allow**.
3. On the Mac, sign in at http://localhost:3000 as nikki@example.com. Leave that browser open: approvals happen here,
   because the laptop's browser can't sign in over plain `http`.

The laptop needs Windows 10 22H2 or 11, an NVIDIA RTX GPU with at least 8 GB, a driver of 536.25 or newer, and 60 GB
free.

## Install (target: under 5 minutes, 3 clicks)

| Do | You should see |
| --- | --- |
| On the laptop, open the address `start.sh` printed, then **Download for Windows** (click 1) | The button shows the version and size, for example "v0.1.0 · 6.8 MB". The browser saves `IdleO-Setup.exe`. |
| Open `IdleO-Setup.exe` | SmartScreen warns, because development builds aren't signed. Choose **More info**, then **Run anyway**. A small window titled "IdleO setup — Development build". |
| **Set up** (click 2) | "Setting up", with each step ticking: checking your laptop, turning on Linux support, downloading, installing, the safe space, testing it, linking. |
| If it asks to turn on Linux support | Windows asks for admin rights once (this is Windows' own prompt). If it says a restart is needed, choose **Restart now**; setup carries on by itself after you sign in again. |
| If a check fails | One screen with one button, for example "Update your NVIDIA driver". Fix it, and setup carries on by itself within 10 seconds. |
| "We opened your browser. Approve this laptop there." | The laptop's browser opens a page it can't sign in on: ignore it. On the Mac, the approval page has opened by itself (`start.sh` prints "a laptop asks to be linked"). Check the name and price, then **Approve and start earning** (click 3). |
| Back on the laptop | "You're set." and "We check every new laptop before it goes live." Close it. The tray icon is there (apricot: waiting for our checks). |
| On the Mac, activate it | Admin console → Hosts → the laptop → **Approve**, or `activate-host` (docs/runbooks/hosts.md). The self-test's checks are on that page. The tray turns green: "Ready for renters" (or, until the real job runner arrives, "Linked · renters' jobs start when the job runner arrives in an update"). |

## Auto-pause

- Touch the mouse or a key. Within 2 seconds the tray goes grey: "Paused while you use your laptop · starts again 10
  min after you stop".
- Leave the laptop alone for 10 minutes. The tray goes green again.
- Open a game or a full-screen video: grey, "Paused while a game is open". Unplug the charger: "Paused while
  unplugged".
- The tray's **Auto-pause** menu turns each of these off, and changes the 10 minutes.
- If a renter's job is running when you come back, it saves its progress and stops; the renter's session carries on
  on another laptop.

## Pausing, both ways

- In the tray: **Pause** ▸ **For 1 hour**. Within a few seconds the machine on the web (Hosting, on the Mac) says
  paused, with the time. **Resume now** in the tray: the web says idle.
- On the web: pause the machine. The tray goes grey, "Paused until …". Resume it on the web: green again.
- The tray's **Hosting** switch off: "Hosting is off" in the tray and paused on the web.

## An update

1. On the Mac: `scripts/release/publish.sh --dev --version 0.1.1 --rollout 100` (one more than the version
   `start.sh` published; it says which). It reuses the laptop's addresses.
2. Within about a minute the machine page on the web says "Updating when idle", and the tray's menu says "Downloading
   an update", then "Update ready · installs when idle".
3. Leave the laptop idle. It installs between jobs: the tray disappears for a moment and comes back.
4. The machine page shows the new version, and the admin console's host page shows "Last update: installed".

## A rollback

1. On the Mac: `IDLEO_TEST_FAIL_TRIAL=1 scripts/release/publish.sh --dev --version 0.1.2 --rollout 100`. That build
   never confirms its trial (development builds only).
2. The laptop installs it when idle. About 5 minutes later the launcher puts the previous version back.
3. The admin console's host page shows "Last update: rolled back", and the machine page shows 0.1.1 again. The
   control plane never offers 0.1.2 to that laptop again; publish 0.1.3 to carry on.

## Uninstall, and check nothing is left

1. Settings → Apps → IdleO → **Uninstall** (or the Start menu: IdleO, right-click, Uninstall).
2. If a renter's job is running, it asks first: "A renter's job is running. It'll save its progress and stop within
   2 minutes."
3. It ends with "IdleO is removed." and "Nothing is left on your laptop. (WSL stays: it's part of Windows.)"
4. Check:
   - `%LOCALAPPDATA%\Programs\IdleO` and `%LOCALAPPDATA%\IdleO` are gone (paste each into Explorer's address bar);
   - `wsl -l -v` in a terminal lists no `IdleO-…` distribution;
   - Settings → Apps has no IdleO;
   - the machine on the web shows as removed.

## Where the results are

- **The number:** `scripts/install-report.sh` on the Mac: for each install, the minutes from opening setup to listed,
  the clicks, each step's duration and where it stopped.
- **The admin console** (http://localhost:3000/admin/): the Installs card on the dashboard (installs, listed, median
  minutes, failures by step and reason), and the host page (agent version, self-test checks, last update).
- **Logs on the laptop**, if something goes wrong: `%LOCALAPPDATA%\IdleO\logs\`. On the Mac: `logs/control-plane.log`
  and `logs/release.log`.
