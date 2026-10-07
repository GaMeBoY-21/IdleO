# Streaming checklist: is a game playable over the internet?

Two people, two machines, about two hours. Follow the steps in order. Write results in `phase0/RESULTS.md`, not here.

- **Host:** the Asus (RTX 4080 Laptop GPU, Windows 11), running Sunshine.
- **Client:** the Mac, running Moonlight.
- Both join one free Tailscale network (tailnet).

**Never write down or commit:** IP addresses (including Tailscale `100.x` addresses), Wi-Fi names, Tailscale auth keys, or the Sunshine username and password. Screenshots of `tailscale` output show IPs, so don't commit them. Record only the numbers asked for below.

## Pass line

On the **hotspot run** (Run B):

| Measure | Pass | Source |
| --- | --- | --- |
| Median round trip | under 30 ms | technical design line 767 |
| Resolution and frame rate | 1080p at 60 FPS, held | our Phase 0 bar |
| Dropped frames | under 1% (network plus jitter drops) | our Phase 0 bar |

Run A (same Wi-Fi) is the baseline. It doesn't decide the gate.

## 1. One-time setup

### On the Asus

1. **Plug in the charger.** Turn on the laptop's charge limit if Armoury Crate offers one (execution plan line 103).
2. **Network:** connect the Asus by **Ethernet** if you can. Wi-Fi adds jitter, which is a different problem from the internet path. Write down which one you used; the results table asks.
3. **Install Tailscale** from tailscale.com/download. Sign in with the account you both agreed on.
4. **Install Sunshine** from the LizardByte/Sunshine releases page on GitHub, using the latest stable Windows installer.
5. **Set up Sunshine.** Open `https://localhost:47990` in a browser *on the Asus*. Your browser will warn about the certificate; that's expected, so continue. Create a Sunshine username and password; type them yourself and keep them out of the repo.
6. **Check the encoder.** In Sunshine, open **Troubleshooting → Logs** and look for a line naming `nvenc` (for example `h264_nvenc` or `hevc_nvenc`). If you only see a software encoder, stop: the stream isn't using the RTX GPU. Fix that before going on (see section 4).
7. **Install one game** you own that has fast motion (a racing game or shooter). Sunshine lists "Desktop" by default. Add the game under **Applications** if it isn't detected.

### On the Mac

1. **Install Tailscale** from the Mac App Store or tailscale.com/download, and sign in with the **same** account.
2. **Check both machines are in the tailnet.** Open Terminal and run `tailscale status`. You should see the Asus listed by its machine name.
   - If `tailscale` isn't found, use `/Applications/Tailscale.app/Contents/MacOS/Tailscale status`, or turn on the CLI in Tailscale's settings.
   - Use the **machine name** (for example `asus-rog`) everywhere below, not the IP.
3. **Install Moonlight** from moonlight-stream.org (or `brew install --cask moonlight`).

### Pair Moonlight with Sunshine (needs both people)

1. **In Moonlight on the Mac**, click **+** (Add PC manually) and type the Asus's Tailscale machine name.
2. **Moonlight shows a 4-digit PIN.** Read it out.
3. **On the Asus**, open `https://localhost:47990`, go to the **PIN** tab, type the PIN, give the device a name such as `mac`, and click **Send**.

   The PIN is typed on the Asus, in Sunshine's web page, not in Moonlight.
4. **Back in Moonlight**, the Asus now shows as unlocked.

### Moonlight settings (on the Mac)

In **Settings**, set:

- **Resolution:** 1080p
- **Frame rate:** 60 FPS
- **Video bitrate:** 20 Mbps (within the 15–30 Mbps range in execution plan line 104)
- **Show performance stats:** on (you can also toggle stats during a stream with Ctrl+Option+Shift+S)
- **V-Sync:** off, frame pacing off (this keeps the numbers about the network, not the display)

Leave everything else at its default. Write down the bitrate actually used.

## 2. Before each run

1. **Measure the Asus's upload speed.** On the Asus, run a speed test (fast.com, then "Show more info") and record the **upload** in Mbps. Execution plan line 104 says gaming needs 15–30 Mbps of steady upload.
2. **Start the GPU logger on the Asus** in a separate window, from the `phase0` folder:
   ```
   python telemetry\gpu_logger.py --out results\stream-runA-gpu.csv
   ```
   Use `runB` for the hotspot run. Leave it running for the whole run.
3. **Check the path, from the Mac:**
   ```
   tailscale ping -c 10 <asus-machine-name>
   ```
   Each reply ends with `via DERP(<region>)` (relayed) or `via <address>` (direct).
   - Record **direct** or **DERP (region)** and the median of the 10 times in ms.
   - Don't record the address.
   - The first replies often go via DERP while a direct path is found; record what the **last** replies say.

## 3. The runs

### Run A: same Wi-Fi (baseline)

1. **Connect the Mac** to the same home network as the Asus.
2. **Start the game** from Moonlight and play for **10 minutes** with real motion: driving or turning the camera, not menus.
3. **Read the stats overlay** at 2, 4, 6, 8 and 10 minutes, and write down each time:
   - incoming and rendered frame rate
   - **average network latency** (this is the round trip, in ms)
   - **average decoding time** (ms)
   - **frames dropped by your network connection** (%)
   - **frames dropped due to network jitter** (%)
4. **After the run**, run `tailscale ping -c 10 <asus-machine-name>` again and record the path.
5. **Stop the logger** with Ctrl+C.

### Run B: Mac on a phone hotspot (the gate)

This makes the traffic genuinely cross the internet.

1. **Turn on a phone hotspot** (4G/5G) and connect the **Mac** to it. The Asus stays on its home network, on the same Ethernet or Wi-Fi as Run A.
   - Ten minutes at 20 Mbps uses about 1.5 GB of mobile data; check the data plan.
2. **Confirm Tailscale reconnected:** run `tailscale status` on the Mac.
3. **Repeat steps 2–5 from Run A**, with `runB` in the logger file name.
4. **Note the result.** Mobile networks often block direct paths, so DERP is a real possibility.

   A DERP result with good numbers is still a pass, but write it down: relayed traffic costs edge bandwidth (execution plan line 349).

### One-hour session (heat and upload)

After Run B, play for **one hour** on whichever network works, with the logger running (`results\stream-1h-gpu.csv`). It answers execution plan line 586:
- Every 15 minutes, note the Asus's upload rate: Task Manager, **Performance** tab, then Ethernet or Wi-Fi, and read **Send**.
- Note any stutter and when it happened.

## 4. Laptop display checks (execution plan line 102)

On many gaming laptops the screen runs through the integrated GPU, and a closed lid leaves nothing to capture.

### MUX switch / dGPU-only mode

1. **Write down the Asus's exact model** (sticker underneath, or **Settings → System → About**). The model is not personal data.
2. **Open Armoury Crate** and look under **GPU Mode**, or a **MUX Switch** setting, depending on the model. If there's an **Ultimate** or **dGPU** mode, the laptop has a MUX switch.
3. **If it has one**, switch to dGPU-only mode, restart, and repeat a 5-minute stream. Then record:
   - whether Sunshine still shows `nvenc`
   - whether the stream works
   - whether the numbers change from Run A
4. **If there is no such option**, record "no MUX".

### Closed lid with a virtual display driver

1. **Install a virtual display driver**, for example the open-source Virtual Display Driver (VirtualDrivers/Virtual-Display-Driver on GitHub). Add a 1920×1080 at 60 Hz virtual monitor.
2. **Set the lid to "Do nothing"** when plugged in: Control Panel → Power Options → Choose what closing the lid does → **Plugged in: Do nothing**.
3. **Point Sunshine at the virtual monitor:** in Sunshine, **Configuration → Audio/Video**, set the display to the virtual monitor. Restart Sunshine.
4. **Start a stream, then close the lid.** Play for 5 minutes, then record:
   - whether the stream survived the lid closing
   - the resolution and FPS
   - whether Sunshine still uses `nvenc`
   - the GPU temperature from the logger (a closed lid restricts airflow)
5. **Afterwards**, set the lid action back to how you had it.

## 5. If something goes wrong

- **Moonlight can't find the Asus:** check `tailscale status` on both machines, and that Sunshine is running (system tray icon).
- **Black screen with the lid closed:** the virtual display isn't the capture target. Recheck step 3 of the closed-lid test.
- **Software encoder in Sunshine's log:** update the NVIDIA driver. In dGPU-only mode it should find NVENC; in hybrid mode, try the virtual display driver.
- **Heavy stutter on Wi-Fi only:** that's local Wi-Fi jitter, not the internet path. Try Ethernet and record both.

## 6. Results to copy into phase0/RESULTS.md

| | Run A: same Wi-Fi | Run B: hotspot |
| --- | --- | --- |
| Asus network (Ethernet / Wi-Fi) | | |
| Asus upload speed test (Mbps) | | |
| Game | | |
| Resolution and FPS held | | |
| Bitrate set (Mbps) | | |
| Network latency at 2/4/6/8/10 min (ms) | | |
| **Median network latency (ms)** | | |
| Decode time, typical (ms) | | |
| Dropped by network, worst reading (%) | | |
| Dropped by jitter, worst reading (%) | | |
| `tailscale ping`: direct or DERP (region) | | |
| `tailscale ping` median (ms) | | |
| Encoder in Sunshine log | | |
| Pass? (Run B decides) | n/a | |

| Laptop check | Result |
| --- | --- |
| Asus model | |
| MUX switch / dGPU-only mode available | |
| Stream in dGPU-only mode works | |
| Virtual display driver used | |
| Stream survives closed lid | |
| Resolution and FPS with lid closed | |
| Peak GPU temperature with lid closed (°C) | |
