# The local stack: the real agent online against the real control plane

`scripts/e2e/agent-online.sh` runs the whole platform on one machine and checks the agent coming online end to end. It's also CI's compatibility test (`.github/workflows/e2e.yml`, ADR 0013): every change to the control plane, the agent or the protocol is proven against the other side.

## Run everything

`./start.sh` at the repository root starts the whole system for development and keeps it running until Ctrl-C; the README's "Run everything" says what it starts, where, and as whom. It shares its steps with the end-to-end script below (`scripts/lib/stack.sh`: the toolchains, the port check, the control plane, the sign-in server and its tokens, operator commands, hosts and agents), so the two start the platform the same way. Its own choices:

- **Its own compose project, `idleo-local`,** whose data is kept from one start to the next; `./start.sh --fresh` wipes it (and the agents' enrolments), after asking. The end-to-end script uses `idleo-e2e` and throws its data away; the two use the same ports, so run one at a time.
- **Seeds, idempotently:** the three `nikki` accounts, created by signing in once, given their credit with `grant-credits` only when their wallet is empty; the two laptops registered, enrolled (their keys in `private/local/agent-N/`), priced at 12 and 18 paise a minute and activated with `activate-host`, each only once. Operator commands take about 20 s each, so the first run is slow and later ones skip them.
- **Settings:** invites off (`IDENTITY_INVITE_ONLY=false`), the development catalog (`templates/development.yaml`, whose simulated services the fake GPUs' development runner plays), production's limits and lease.
- **The web app** runs as `pnpm dev` with the local sign-in adapter, whatever `web/.env.local` says (the environment wins over `.env` files); with `--real-sign-in`, `web/.env.local`'s Firebase settings are used, and the control plane trusts your project as a second issuer beside the local one, so the seeds still work.
- **The website** (the public pages, ADR 0029) is its own build, `web/dist-website-local/`, made again only when its sources changed since the last build, and served at http://localhost:3002/. Its "Log in" and "Get started" lead into the web app at http://localhost:3000/ (`IDLEO_APP_URL`, and `IDLEO_SITE_URL` the other way). `./start.sh` opens the website first and prints both addresses, the website first; `/about/` redirects to `/`.
- **`--lan`, for a Windows laptop on the same Wi-Fi** (ADR 0033, docs/windows-laptop-test.md): the website, the web app and storage are opened to the network (`0.0.0.0`; the API and gRPC listen there already), the gRPC certificate also names the Mac's LAN address (`dev-ca.sh --server-ip`), storage's address and the approval links become the LAN address, and the website calls the API there. A development release of the Windows app is built for that address and published to local storage (`scripts/release/publish.sh --dev`, its log `logs/release.log`), once per address and version of the code (a fingerprint of `agent/`, `proto/`, the image and the addresses is kept beside the version in `private/release-dev/published`, so a start after a code change builds and publishes a newer one). The laptop's browser can't sign in over plain `http` (the sign-in library needs a secure page), so each laptop's approval page opens on the Mac by itself, read from `hosts.host_links`. `IDLEO_LAN_IP` overrides the address it finds.
- **Payments go to the fake Razorpay** (ADR 0039, docs/runbooks/payments.md) on port 8091, started from the control plane's test classpath (`FakeRazorpayServer`, its log `logs/fake-razorpay.log`) before the control plane, which it sends Razorpay's webhooks to. The web app's Credit page loads its fake Checkout (`VITE_CHECKOUT_URL`), which pays through it: no key, no network, no real money.
- **The release feed** trusts the development release key whenever `private/release-dev/release.pub` exists, and reads the feed every 10 s.
- **Logs** in `logs/` (control-plane, web, site, agent-1, agent-2, ops, compose); pids in `private/local/`. `./start.sh --stop` stops what another start started; `--status` shows it.

`scripts/e2e/start-check.sh` runs it without a screen, books one simulated session, stops it and checks nothing is left; CI runs it after the end-to-end test.

## Every check, locally

`scripts/check-all.sh` runs every workflow's checks on this machine, the same steps CI runs (`scripts/checks/<suite>.sh` and the end-to-end script): agent, cli, control-plane, proto, sdk, web, web-browser (every screen, axe, the journeys, Lighthouse and the performance spec) and e2e (with `start-check.sh`). `scripts/check-all.sh web e2e` runs just those. The output is quiet: one line per suite (name, PASS or FAIL, time), the whole output in `private/logs/<date-time>/<suite>.log`, and on a FAIL the last 40 lines and the log's path; `--verbose` prints everything as it runs (the e2e script, the drill and the hostile tests are quiet the same way when run alone; CI, where `CI` is set, stays verbose). While working, `scripts/check-changed.sh` runs only the suites the files changed since `origin/main` touch (`--list` shows which; the map is `scripts/lib/suites.sh`). Run `check-all.sh` once, before every push; while CI minutes are short, it is the gate.

## What it does

From the repository root, it:

1. Creates the development CA under `private/dev-ca/` if it doesn't exist yet (`control-plane/scripts/dev-ca.sh`), including the gRPC port's certificate for `localhost`.
2. Starts the control plane on the `local` profile (`./mvnw spring-boot:run`).
   - Spring Boot starts `control-plane/compose.yaml`: PostgreSQL, Valkey, MinIO (object storage), the development sign-in server and Mailpit (every email the stack sends, kept and shown at http://localhost:8025, none delivered; docs/runbooks/email.md), under the compose project name `idleo-e2e`.
   - It uses the development template catalog (`templates/development.yaml`: the two services simulated, 90 to 120 s each; the smoke test runs 20 s, `charged-run` 150 s and `long-run` 10 minutes; each checkpointing every 5 s) and a 10-second lease renewed every 3 s (`E2E_LEASE_TTL`; production uses 2 minutes and 30 s).
   - Storage grants last 60 s, so every run outlives its first and the agents swap in fresh ones. Metering ticks every second and closes a session 20 s after it ends rather than 5 minutes.
   - The script waits for readiness to be UP.
3. Gets an ID token for an invented user (`e2e-…@example.com`) from the development sign-in server.
4. Registers seven hosts (`POST /v1/hosts` with an `Idempotency-Key`) and gets their enrolment codes (`POST /v1/hosts/{id}/enrolment-code`). Codes are never printed or logged.
5. Builds the agent (`cargo build --release`), writes `private/e2e/agent-1.toml` to `agent-7.toml`, and enrols each into its own `private/e2e/agent-N/`.
6. Runs seven `idleo-agent run --fake-gpu --dev-runner`, and polls `GET /v1/hosts/{id}` every 500 ms until every host is `online` (30 s at most). It also checks that the fake GPU is listed.
7. **Sets up money and hosts:**
   - two renters get ₹500 each with the `grant-credits` operator command: A for the normal run alone, so its wallet shows exactly that charge, and B for the other three;
   - every host lists at 2 paise a minute, and is activated with `activate-host`.
8. **Runs four charged sessions at once, one per host** (ADRs 0015, 0016, 0017), each in its own subshell:
   - **Normal run** (`charged-run`, renter A): runs 150 s and completes. The output (`private/e2e/output-normal.json`) is attempt 1 with no resume. It's charged exactly 3 minutes, 6 paise, in one balanced ledger transaction (renter −6, host +4, platform +2), which the summary prints. The wallet goes from ₹500.00 to ₹499.94 with nothing held, and `GET /v1/hosts/{id}/earnings` shows +4 paise for the run.
   - **Stop mid-run** (`long-run`, renter B): about 70 s after RUNNING, `POST /v1/sessions/{id}/stop`. The agent writes a last checkpoint and stops, and the session ends `COMPLETED` (`renter_stopped`, then `stopped`), charged exactly the minutes it ran.
   - **Host loss** (`charged-run`, renter B): once minute 0 is reported and a checkpoint is past it, `kill -9` the agent. The lease lapses, the session goes `HOST_LOST` and back to `REQUESTED` as attempt 2, and resumes on a free host from the newest complete checkpoint (`private/e2e/output-resumed.json` names it); the killed agent is restarted. Attempt 2 only ran after attempt 1 was declared lost. Attempt 1's minutes after its checkpoint aren't charged and attempt 2's are; the summary prints the waiver and the ledger.
   - **Spending cap** (`long-run` with `maxSpendPaise` 6, renter B): stopped with `max_spend_reached` after a last checkpoint, `COMPLETED`, charged no more than 6 paise and exactly the minutes it ran.

   - **Web journeys** (its own renter, ₹500, `web/e2e/journey.spec.ts`): in a real browser, Home following a booked session to its charge; on a laptop, a whole booking (form, a 12 MiB upload, the quote, the session live to settled, the output's SHA-256) and Run again; not enough credit; a cancelled upload; the event stream dropping and coming back; stopping a session on a phone. The summary prints seconds from Book to RUNNING, upload throughput, the quoted and held hold, and the charge.

   - **Booking contract** (its own renter): a 12 MiB file uploaded in parts straight to storage, a quote, `echo-input` booked with parameters and that file, followed on its event stream to settled, its logs and output, and three errors by their types.
   - **Simulated fine-tune** (the booking contract's renter): a JSONL of 40 examples uploaded, `fine-tune-simulated` booked with 3 epochs and rank 8, followed to `COMPLETED`, and `adapter_model.safetensors` downloaded and read as a safetensors file (8 F32 tensors of rank 8 filling the file), with `training_log.json`'s steps and falling loss, and the log showing every step.

   Each scenario's emails are read from Mailpit's API (ADR 0026): the normal run's and the simulated fine-tune's "finished" with what they cost (exactly one each), the spending cap's "Stopped: it reached your spending cap", the host loss's "went offline during a session" to the machine's owner with no renter and no session in it, and nothing for the session its renter stopped.

   Then it checks renter B's wallet is not negative and holds nothing, that an agent resumed from a checkpoint and swapped in fresh credentials, and that no storage credential appears in any log.
9. **Runs the wallet check** (`reconcile-wallets`) and requires 0 mismatches.
10. Stops the agents with SIGTERM, and polls until every host is offline (30 s at most). Under 5 seconds means the clean close removed presence; anything longer would mean only the 15-second presence expiry did, and the script says which.
11. Prints a summary, with each timing and PASS or FAIL, and exits non-zero on any failure.
12. On exit, even after a failure or Ctrl-C, it stops the agents and the control plane and removes the compose services. It then checks that no container, control plane or agent is still running.

Logs go to `private/e2e/`: `control-plane.log`, `agent-1.log` to `agent-7.log`, `agent-build.log`, `ops.log` (the operator commands) and `compose.log`, with the downloaded outputs beside them. Like everything else under `private/`, they're gitignored. The script never reads `control-plane/.env`, so a local override can't change the result.

## What you need

- **Docker**, running.
- **Java 25.** On a Mac: `brew install openjdk@25`, then `export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`.
- **Rust**, through rustup. It installs the toolchain pinned in `agent/rust-toolchain.toml` on first use. Homebrew's rustup (`/opt/homebrew/opt/rustup/bin`) is found even when it isn't on `PATH`.
- **OpenSSL 3**, for the development CA. macOS ships LibreSSL: `brew install openssl@3`.
- `curl`, `perl` and `python3`, which macOS and Ubuntu both have.

## Run the check

```
scripts/e2e/agent-online.sh
```

The first run downloads the images, the Maven dependencies and the crates, and builds both sides. After that, a run takes about a minute and a half on a Mac: starting the control plane, two operator commands, two 20-second jobs, and the lost host's 10-second lease.

## Keep the stack up and try things by hand

```
scripts/e2e/agent-online.sh --keep-running
```

This starts the stack to look at and try by hand (steps 1 to 7, without the scenarios):
- two hosts, each an agent with the development runner and a laptop's fake GPU: **RTX 4060 Laptop**, 8 GB, listed at 12 paise a minute (₹7.20 an hour), and **RTX 4080 Laptop**, 12 GB, at 18 paise (₹10.80 an hour), so Book shows a real price range and a session a real GPU's name;
- the web app, built against the stack and served at http://localhost:3000 (with `E2E_SERVE_WEB=0` it isn't, so `pnpm dev` can have the port);
- an account with ₹500 of credit: sign in as `nikki@example.com` (another with `E2E_ACCOUNT=<name>`). Home shows the two services, simulated, and "Test jobs".

It prints the address and the account, and a ready `curl` with a fresh token for it, for example:

```
curl -s -H 'Authorization: Bearer eyJ…' http://localhost:8080/v1/me
```

It also prints the command for a new token; tokens last an hour. Any `client_id` signs in as a different invented user, `<client_id>@example.com`. To run another agent against the stack by hand, follow `agent/README.md`, with `ca_root` set to `private/dev-ca/root.pem` and the port set to 9443.

**Ctrl-C stops everything.** If the terminal was closed instead, run `docker compose -p idleo-e2e -f control-plane/compose.yaml down` and stop any leftover `spring-boot:run` process.

## Ports

| Port | What | Moved with |
| --- | --- | --- |
| 8080 | The control plane's HTTP API | `E2E_HTTP_PORT` |
| 9443 | The agents' gRPC port (TLS) | `E2E_GRPC_PORT` |
| 8099 | The development sign-in server. The issuer URL in every token includes it, so it's fixed rather than random | `LOCAL_SIGN_IN_PORT` (compose and the `local` profile read the same variable) |
| 1025, 8025 | Mailpit's SMTP port and its web view and API (ADR 0026) | `LOCAL_MAIL_SMTP_PORT`, `LOCAL_MAIL_WEB_PORT` (compose and the `local` profile read the same variables) |
| 8091 | The fake Razorpay (ADR 0039): its API, its webhooks' sender and the fake Checkout the Credit page loads | `E2E_RAZORPAY_PORT` |
| 8092 | The fake RazorpayX (ADR 0040): UPI ID checks and payouts, moved on by themselves, and their webhooks' sender (docs/runbooks/payouts.md, "The local stack") | `E2E_RAZORPAYX_PORT` |
| 9100 | MinIO's S3 API. Agents' grants and renters' download links carry this endpoint, so it's fixed | `LOCAL_STORAGE_PORT` (compose and the `local` profile read the same variable) |

PostgreSQL and Valkey get random ports. If a port is busy, the script stops before starting anything and names the port. A stack already started by `control-plane/scripts/run-local.sh` uses the same fixed ports, so stop it first.

## When it fails

- **"the control plane exited during startup":** read `private/e2e/control-plane.log`. Docker not running, or a port taken inside Docker, are the usual causes.
- **"the host didn't come online":** read `private/e2e/agent.log`. A TLS error there usually means a stale development CA: run `control-plane/scripts/dev-ca.sh --force`, then run the script again.
- **A session doesn't reach `RUNNING` or `COMPLETED`:** read `private/e2e/agent.log` for the agent's view (a refused StartSession, a storage failure) and `control-plane.log` for "Couldn't mint storage credentials". MinIO is reached at `http://localhost:9100`. Object storage is described in docs/runbooks/object-storage.md.
- **"the host didn't go offline within 30 s":** the agent didn't close its stream, and presence also didn't expire. Compare the agent's and the control plane's logs around the SIGTERM.

## Nothing here can reach production

The development sign-in server is a local issuer, and only the `local` and `test` profiles trust it. Fake GPUs and Macs are accepted only with `agentgateway.allow-development-hosts`. The `prod` profile refuses to start with either (ADR 0013).
