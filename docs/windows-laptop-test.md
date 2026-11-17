# Trying the Windows app on a laptop

A checklist for the first real install, on a Windows RTX laptop on the same Wi-Fi as the Mac. Everything the laptop
reports lands in the Mac's database, so there's nothing to copy off the laptop.

## Before you start (on the Mac)

1. `./start.sh --lan`. The first time it builds the Windows app and the sealed Linux image, which takes several
   minutes. When it's ready it prints the Mac's address, for example `http://192.168.1.20:3002`.
2. If macOS asks to allow incoming connections (Java, node), click **Allow**.
3. On the Mac, sign in at http://localhost:3000 as nikki@example.com. Leave that browser open: approvals happen here,
   because the laptop's browser can't sign in over plain `http`.

The laptop needs Windows 10 22H2 or 11, an NVIDIA RTX GPU with at least 8 GB, a driver of 527.41 or newer, and 60 GB
free.

## Install (target: under 5 minutes, 3 clicks)

| Do | You should see |
| --- | --- |
| On the laptop, open the address `start.sh` printed, then **Download for Windows** (click 1) | The button shows the version and size, for example "v0.1.0 · 6.8 MB". The browser saves `IdleO-Setup.exe`. |
| Open `IdleO-Setup.exe` | SmartScreen warns, because development builds aren't signed. Choose **More info**, then **Run anyway**. A small window titled "IdleO setup — Development build". |
| **Set up** (click 2) | "Setting up", with each step ticking: checking your laptop, turning on Linux support, downloading, installing, the safe space, testing it, linking. |
| If it asks to turn on Linux support | Windows asks for admin rights once (this is Windows' own prompt). If it says a restart is needed, choose **Restart now**; setup carries on by itself after you sign in again. |
| If a check fails | One screen with one button, for example "Update your NVIDIA driver". Fix it, and setup carries on by itself within 10 seconds. |
| If "Something didn't pass" | A development build also has a small **Keep it for troubleshooting** button. See "If something didn't pass" below. |
| "We opened your browser. Approve this laptop there." | The laptop's browser opens a page it can't sign in on: ignore it. On the Mac, the approval page has opened by itself (`start.sh` prints "a laptop asks to be linked"). Check the name and price, then **Approve and start earning** (click 3). |
| Back on the laptop | "You're set." and "We check every new laptop before it goes live." Close it. The tray icon is there (apricot: waiting for our checks). |
| On the Mac, activate it | Admin console → Hosts → the laptop → **Approve**, or `activate-host` (docs/runbooks/hosts.md). The self-test's checks are on that page. The tray turns green: "Ready for renters" once the job runner in the Linux space answers (until then it says "Linked · renters' jobs start when the job runner arrives in an update"). |

## Run a real job

This needs the job image on the Mac's registry, so start the stack with `./start.sh --lan --real-runner` instead (the first time it
builds the CUDA image, about 6 GB, which takes a while; the fake laptops take no jobs). The Windows app's setup then points
the laptop's Linux space at the Mac's registry (a development build only), and its self-test also does the following. When
the laptop is idle and not on a metered connection it downloads the job image by itself (the tray says "Getting ready for
renters · N GB downloaded").

| Do | You should see |
| --- | --- |
| On the Mac, `scripts/install-report.sh`, after the laptop's setup | The self-test lists `runner` passed, `network_lockdown` passed ("the home network is unreachable and the internet is reachable"), and, once the image has downloaded, `gpu_in_container` and `cuda_matmul` passed. |
| Sign in at http://localhost:3000 as nikki@example.com, **Run your code**, upload `agent/images/pytorch/tests/project/`, Command `SESSION_PARAM_STEPS=120 SESSION_PARAM_PAUSE=1 python main.py`, GPU memory 8 GB | The session starts on the laptop. The first time, if the image isn't there yet, the session waits for the download (minutes); after that the first log line comes within a few seconds. `step 1/120 …`, a `checkpoint` every 5 steps. |
| While it runs, on the laptop, touch the mouse | Within 2 seconds the tray goes grey. The job saves a checkpoint and stops, "owner returned", and the session carries on on another laptop if there is one. |
| Run it again and, on the laptop, open Task Manager | `vmmem` (the Linux space's memory) is there while the job runs. 5 minutes after the job ends it's gone: the Linux space shuts itself down. |
| **Fine-tune your model** with a small model folder you have (a few hundred MB) and a JSONL of examples | Logs show the model loading on the GPU, the loss falling and the adapter saved; "You get" lists `adapter_model.safetensors`. |
| In the job, run `curl -m 5 http://192.168.1.1` (use your router's address) or `ping` it, from a Run-your-code command | It times out. `curl -m 5 https://example.com` works. This is the home-network block; the report counts any other answer as a failure. |
| On the Mac, `scripts/install-report.sh`: the self-test's `scratch_encryption` check (ADR 0038) | **Passed**, with a detail like "opened in 100 ms, written, read, closed and erased in 200 ms; its image holds nothing in the clear" (write both times down: they are the encryption's cost on this laptop). This is the one proof of dm-crypt under the real WSL kernel (the source says `CONFIG_DM_CRYPT=m`; nothing has run it on a laptop yet). If it **fails**, write down its words and the output of `wsl -d IdleO-<version> -u root -- uname -r` and `modprobe dm_crypt`, and stop: the runner refuses jobs on this laptop on purpose (`sandbox_not_ready`). |
| Run any job (the **Run your code** one above), and while it runs on the laptop, in a Windows terminal: `wsl -d IdleO-<version> -u root -- sh -c 'lsblk; ls /dev/mapper; mount | grep idleo'` | The scratch disk is a `crypt` device under `/dev/mapper/idleo-…`. After the job ends, `ls /dev/mapper` shows none and no `idleo` mounts remain. Note how long the job took to start (the first log line), and write it with and without a comparable earlier run: the cost of encryption on this laptop's CPU and disk is unmeasured. |
| Run a job that uploads as fast as it can (**Run your code**, Command `python -c "import urllib.request,os;urllib.request.urlopen(urllib.request.Request('https://speed.cloudflare.com/__up',data=os.urandom(200_000_000)))"`) while you watch the laptop's network use in Task Manager, and open the machine page in the web app | The machine page says "Jobs use at most N Mbit/s of your upload" (half of what the host app measured, never under 5). The job's upload stays near that number, not above it, and the laptop's other internet use (a video, a page) stays usable. Write down N and the line's real upload speed. |
| **The 16 hostile tests on the laptop (do this first: a single FAIL stops the laptop day).** In the web app make an API key (Account, API keys, full), find the laptop's host id (its address on the Hosting screen, `/hosting/machine/?id=…`), and on the Mac: `IDLEO_API_KEY=idleo_… IDLEO_API_URL=http://<the Mac's LAN address>:8080 agent/runner/tests/hostile/run.sh --host <host id>` | A 16-row table, each row PASS (or N/A with its reason): the job tried to write to the root, become root, open raw sockets, reach the home network (every private and reserved range, and the other sessions' networks; the router, the Windows host and the Linux space's own addresses are probed by the laptop's self-test, `network_lockdown`, and row 4 says so), use any port but 80, 443 and DNS, use IPv6, fork-bomb, use too much memory (`out_of_memory`), fill its disk (`scratch_full`), find the storage keys, write to its read-only input, and run too long (`timed_out`). Your other machines are paused while it runs and resumed after. The same check, `leftovers`, is in the laptop's self-test (the `runner` check fails with its words if a finished session left anything). Rows 11, 15 and 16 say **N/A** with their reasons (11 needs two sessions at once; 15 searches the Linux space's disk for what a job wrote, and 16 tries to open a running session's disk from inside the Linux space, which the API can't reach; the Mac's own run, `run.sh` without `--host`, is where they are proved, and the first two rows below prove the encryption on the laptop itself): an N/A is not a PASS. Rows 4 and 5 also try the DNS forwarder on the session's own gateway: only port 53 there answers. Row 12 (nothing left behind) is **read through the runner**: after the attack starts a background process and ends, the next session's log, in a development build, carries the runner's own line, "idleo-runner (development): nothing is left from finished sessions", or what is left (containers, processes, mounts, loop devices, scratch files, session network interfaces and address leases); the row is PASS only on the first. If any row says FAIL, stop: write down the row's words and don't run any job on this laptop. |

If a job fails to start, the session's ending says why in plain words (for instance "Couldn't download the job files"), and
`scripts/install-report.sh` and the admin console's host page show the runner's self-test checks. The runner's own log is
`wsl -d IdleO-<version> -u root -- journalctl -u idleo-runner` (a development build only).

## Auto-pause

- Touch the mouse or a key. Within 2 seconds the tray goes grey: "Paused while you use your laptop · starts again 10
  min after you stop".
- Leave the laptop alone for 10 minutes. The tray goes green again.
- Open a game or a full-screen video: grey, "Paused while a game is open". Unplug the charger: "Paused while
  unplugged".
- The IdleO window's **Schedule** tab turns each of these off, and changes the 10 minutes ("Start again after").
- If a renter's job is running when you come back, it saves its progress and stops; the renter's session carries on
  on another laptop.

## Pausing, both ways

- In the tray's menu: **Pause for 1 hour**. Within a few seconds the machine on the web (Hosting, on the Mac) says
  paused, with the time. **Resume** in the tray: the web says idle.
- On the web: pause the machine. The tray goes grey, "Paused until …". Resume it on the web: green again.
- The tray's **Pause** (until you resume): "Paused · until you resume" in the tray and paused on the web.

## The IdleO window, the tray and notifications (ADR 0061)

Each row needs the real laptop; "you should see" is what proves it. Set Windows to dark, then light, and the display to 100%, 150% and 200% (Settings → System → Display) for the first rows.

| Do | You should see |
| --- | --- |
| Look at the tray icon on a dark and then a light taskbar, at 100%, 150% and 200% | A crisp round icon at each scale, never blurry. Its shape says the state: a ring when ready, a triangle running a job, two bars paused, a clock waiting for checks, a cross offline, an exclamation mark a problem, an arrow up when an update is ready. |
| Hover the icon | A tooltip with the state in words, for example "Ready for renters". |
| Left-click the icon | A 400 × 600 window with a normal title bar opens above the icon, on the Now tab. Click anywhere else: it closes. Click the icon again: it opens again (not a flicker). |
| Right-click the icon | The menu: the state, **Pause** and **Pause for 1 hour** (or **Resume**), **Open IdleO**, **Host site**, **Help**, **Quit IdleO**. |
| Start menu → IdleO | The same window opens. With IdleO not running, it starts it first. |
| Press Tab and Shift+Tab through the window, and use the arrow keys on the tabs; turn on Narrator (Win+Ctrl+Enter) | A visible focus ring everywhere; every control is read with its name and state ("Pause hosting, switch, off"); the tabs are read as tabs. |
| Windows Settings → Accessibility → Visual effects → Animations off | The window's spinner and switch stop moving. |
| Now tab: the pause switch, then **For 1 hour**, **Until tomorrow, 9 am**, **Until I resume** | Each pauses (the web shows it, with the time); the switch turns on; turning it off resumes. |
| Run a real job (above) and watch Now | "Running a job · N min", the GPU's temperature and use, today's and this week's earnings, the next payout date (a Monday), and never anything of the renter's. |
| Schedule tab: **Set hours**, choose hours that don't include now | Within seconds the tray goes grey: "Paused · outside your hours", and a running job saves its progress and stops. Choose hours that include now: it carries on by itself, with no 10-minute wait. |
| Schedule tab: **Only on mains power**, then unplug | Within seconds: "Paused · on battery". Plug in: it waits the "start again after" time. |
| Schedule tab: **Not on metered connections**, then set the Wi-Fi to metered (Settings → Network → the connection → Metered) | "Paused · on a metered connection", and no job image downloads. Switch it back: it carries on at once. |
| Limits tab: set the temperature to 60 °C while a game or a stress test heats the GPU | Within seconds: "Paused · the GPU is too hot"; once five degrees cooler, it carries on by itself. After 2 minutes hot, a toast says so. |
| Limits tab: set the disk limit under what the Linux space uses now (check `%LOCALAPPDATA%\IdleO\wsl`) | "Paused · the disk limit is reached". Old job images are removed by themselves before that. |
| Earnings tab | Today, this week, this month, the last jobs (length and earnings only), and **See everything on the host site**. Turn off Wi-Fi: it still shows them, with "Last updated N min ago". |
| Settings tab: switch **Start with Windows** off, sign out and in; then on, sign out and in | Off: IdleO doesn't start. On: it starts by itself within seconds of signing in. |
| Settings tab: switch a notification off, then cause it | No toast for that kind; the others still come. Windows' Focus assist on: toasts wait in the Action Center. |
| Settings tab: **Theme** Light, Dark, Follow Windows | The window changes at once. |
| Toasts: first link and approve ("You're live"); the first job; touch the mouse during a job ("IdleO paused a job", once a day at most); an update installing ("IdleO updated"); a payout (after Monday 10:00 India time, with payouts on); unplug the charger for a driver or disk problem | Each toast appears; clicking it opens the window on the right tab (Now, Now, Now, Settings, Earnings, and the problem's tab). |
| Close the lid (sleep), open it 2 minutes later, on the same Wi-Fi | The tray shows Offline for a moment and "Ready for renters" again within seconds. A job that was running was saved and handed over (the owner-returned path). |
| Switch Wi-Fi, connect a VPN, turn on a phone hotspot | It reconnects each time within a few seconds, with a longer wait only if the network stays down (check `%LOCALAPPDATA%\IdleO\logs\hostd.log` for the retries: never one a second). |
| Restart Windows for an update | After signing in, the tray icon and the agent are back by themselves. |
| End `idleo-hostd.exe` in Task Manager three times within 10 minutes | The launcher restarts it each time (waiting longer each time); after the third the tray shows "IdleO keeps stopping" with **Send diagnostics** in the window (Now and Settings), and a toast. After 10 quiet minutes the problem goes. |
| **Send diagnostics** (the window's Settings tab, or a failure screen in setup) | A zip in Downloads, shown in Explorer: logs, settings and a few lines about the laptop; no name, no computer name, no keys. Open it and search for your Windows user name: nothing. |
| `%LOCALAPPDATA%\IdleO\logs` after a few days | Each log a few MB at most (`hostd.log`, `hostd.log.1`, `.2`; the same for `tray.log`). |
| Right-click the tray icon → **Quit IdleO**, with a job running | "A renter's job is running. It'll save its progress and stop within 2 minutes." Stop it and quit: the tray, the agent and the launcher all end, and nothing comes back until the next sign-in. |
| Window → Settings → **Remove IdleO**, or Settings → Apps → IdleO → Uninstall | It asks whether to keep the earnings history on this laptop. Afterwards `%LOCALAPPDATA%\Programs\IdleO` and `%LOCALAPPDATA%\IdleO` are gone (or hold only the earnings history, if you kept it), the Start menu item, the Run entry, `HKCU\Software\Classes\idleo` and the `IdleO-…` WSL distribution are gone, and the machine on the web shows as removed. |

### Setup's screens and every way it can fail

`IdleO-Setup-Demo.exe` (made by `scripts/build-windows.sh`) walks through every screen with pretend progress and changes nothing: its title bar says "Demo". The same screens are on the Mac in the browser: `cd agent && cargo run -p setup --example preview`, then open `agent/target/preview/index.html` (Alt+arrows step; the theme and scale are at the top).

| Do | You should see |
| --- | --- |
| A laptop with virtualization off in the BIOS | "Virtualization is off", the steps, and **Check again**; **Show me how for my laptop** opens the right page. Turn it on, come back, **Check again**: it carries on. |
| Less than 60 GB free on C: | "Not enough free space", naming the drive and how much to free; **Open Storage settings**; it carries on by itself when there's room. |
| On battery | "Plug in your laptop"; it carries on by itself when plugged in. |
| Wi-Fi off during the download | "Connection lost · trying again in 2 s", 4 s, 8 s, then "Check your connection" with **Try again**; turning Wi-Fi on resumes the download from where it stopped. With a proxy set in Windows, the screen says so. |
| The download's speed | MB, a bar, and "about N min left". |
| An antivirus that blocks `idleo-distro.tar.gz` or a program | "Your antivirus blocked a file", naming it; allow it, **Try again**. |
| Restart Windows when setup asks | After you sign in, setup opens by itself and carries on from where it was, without starting over. |
| Kill `IdleO-Setup.exe` at each step (Task Manager) and open it again | It offers **Repair** and carries on from the step that didn't finish, never from the start. Run it twice more: the same good install. |
| Open `IdleO-Setup.exe` on an installed laptop | **Open IdleO** and **Repair**. |
| Windows in S mode, an ARM laptop, a GTX card, an old driver, no NVIDIA driver | Each has its own plain screen; only the old driver and no driver carry on by themselves, with a link to NVIDIA's driver page. |
| **Send diagnostics** on any failure screen | A scrubbed zip in Downloads, and "Show in folder". |

## An update

1. On the Mac: `scripts/release/publish.sh --dev --version 0.1.1 --rollout 100` (one more than the version
   `start.sh` published; it says which). It reuses the laptop's addresses.
2. Within about a minute the machine page on the web says "Updating when idle", and the tray's menu says "Downloading
   an update", then "Update ready · installs when idle".
3. Leave the laptop idle. It installs between jobs: the tray disappears for a moment and comes back.
4. The machine page shows the new version, the admin console's host page shows "Last update: installed", the IdleO window's Settings tab says "Updated to v0.1.1" for a week, and a toast says "IdleO updated".

## A rollback

1. On the Mac: `IDLEO_TEST_FAIL_TRIAL=1 scripts/release/publish.sh --dev --version 0.1.2 --rollout 100`. That build
   never confirms its trial (development builds only).
2. The laptop installs it when idle. About 5 minutes later the launcher puts the previous version back.
3. The admin console's host page shows "Last update: rolled back", and the machine page shows 0.1.1 again. The
   control plane never offers 0.1.2 to that laptop again; publish 0.1.3 to carry on.

## If something didn't pass

A development build tells the Mac why, and can leave the install on the laptop to look at. A release build never
does either: one calm screen, and nothing left behind.

1. **On the Mac,** run `scripts/install-report.sh`. The failed step or self-test check is under its install, with a
   `why:` line, the words the laptop found (scrubbed of the Windows user name, the profile path and the computer's
   name). Under `facts:` it also shows what a development build found out about the laptop, on a pass as well as a
   fail: WSL's version and networking mode, the kernel, the NVIDIA driver and the CUDA version `nvidia-smi` reports,
   the laptop's RAM and what the WSL VM is using, and what the distribution can reach (the gateway, one address in
   each common LAN range, and our storage). The LAN lines are expected to say `silent` or `unreachable`; they are
   facts, not failures. Send that to Claude. If the laptop's last screen says "Couldn't report to IdleO: …", the Mac didn't get
   it: read the setup log (step 2) instead.
2. **On the laptop,** press **Keep it for troubleshooting**. The window says where the setup log is; it is
   `%LOCALAPPDATA%\IdleO\logs\setup-<date-time>.log` (UTC) and has every step with its duration, every command with its
   exit code and output, and the self-test's JSON. Then open PowerShell and run the self-test again by hand, with
   `--json`:

   ```powershell
   $version = (Get-Content "$env:LOCALAPPDATA\Programs\IdleO\current").Trim()
   $distro = (Get-Content "$env:LOCALAPPDATA\IdleO\distro").Trim()
   & "$env:LOCALAPPDATA\Programs\IdleO\app-$version\idleo-agent.exe" self-test --json --distro $distro
   ```

   (`IdleO\distro` exists once the Linux space was imported. If that step is the one that failed, `wsl -l -v` shows
   whether an `IdleO-…` distribution is there.)
3. **Fix what's needed,** then run `IdleO-Setup.exe` again. It says "Setup didn't finish on this laptop" and offers
   **Repair**, which carries on from the step that didn't pass, not from the start.
4. **When done,** Settings → Apps → IdleO → **Uninstall**, then check that nothing is left, as below. The uninstall
   removes the log folder too.

If the install is undone instead (OK, or a failure before anything was installed), a development build keeps only
the `logs` folder, and its last screen says where it is. Delete `%LOCALAPPDATA%\IdleO` by hand when you're done with it.

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
- **The admin console** (http://localhost:3004/admin/): the Installs card on the dashboard (installs, listed, median
  minutes, failures by step and reason), and the host page (agent version, self-test checks, last update).
- **Logs on the laptop**, if something goes wrong: `%LOCALAPPDATA%\IdleO\logs\` (`setup-<date-time>.log` for each run
  of setup). On the Mac: `logs/control-plane.log`
  and `logs/release.log`.
