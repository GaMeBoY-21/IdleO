# cli

The renter's command line, `idleo`: a single binary for macOS, Windows and Linux (docs/plan/product-surface.md section 4, ADR 0025). The name is one constant (`src/brand.rs`), so the brand stays one change.

It books GPU sessions, uploads what they need, follows them live, and brings back their outputs. It keeps no state the API doesn't have, except your key, the uploads and bookings it hasn't finished, and nothing else.

## Install from source

You need the Rust toolchain pinned in `rust-toolchain.toml` (`rustup` installs it on the first `cargo` command in this folder).

```
cd cli
cargo build --release
./target/release/idleo --help
```

Put `target/release/idleo` on your `PATH`. Packaging (Homebrew, a Windows package, signed releases) comes with deployment.

## Where it talks to

The API's address is `--api URL`, else `IDLEO_API_URL`, else the local stack (`http://localhost:8080`, docs/runbooks/local-stack.md). Production sets `IDLEO_API_URL` at deployment.

## Sign in

```
$ idleo login
To approve this sign-in, open:

  http://localhost:3000/cli-login/?code=WDJB-MJHT

and check that the code is WDJB-MJHT
Waiting for you to approve it (it expires in 10 minutes)…
Signed in as asha@example.com. The key "CLI on asha-laptop" is kept in your keychain.
```

It opens the link in your browser (`--no-browser` only prints it), you sign in to the web app if you aren't, and you approve the same code the terminal shows. The terminal, which has been waiting, is handed a new API key, once. It works over SSH, since only the link needs a browser.

- **Where the key is kept:** your operating system's keychain, else a file in the config folder only you can read (`~/.config/idleo/credentials.json` on Linux, `~/Library/Application Support/idleo/` on macOS, `%APPDATA%\idleo\` on Windows; `IDLEO_CONFIG_DIR` moves it). A key is kept for one API address, so the local stack's never goes anywhere else.
- **`IDLEO_API_KEY`** in the environment wins over a stored key, for CI. `IDLEO_NO_KEYCHAIN=1` skips the keychain, for machines that have none.
- **`idleo logout`** revokes this computer's key on the server, then deletes it here.
- **`idleo whoami`** shows who the key belongs to.

## Commands

Every command takes `--json` (JSON and nothing else on stdout, for scripts; progress goes to stderr) and `--api URL`.

### Your account

```
$ idleo credit
Available  ₹499.64
On hold    ₹0.00
Total      ₹499.64

$ idleo credit history --limit 3
WHEN       WHAT            AMOUNT    SESSION
just now   session charge  -₹0.36    01a12ffa-824c-7565-8c16-74ed14ef1c3d
3 min ago  free credit     +₹500.00
```

### What you can run, and what's free

```
$ idleo templates
TEMPLATE             NAME                                 TYPICAL RUN  USUAL BUDGET
fine-tune-simulated  Fine-tune your model (simulated)     2m 00s       ₹100.00

$ idleo templates show fine-tune-simulated
Fine-tune your model (simulated)  (fine-tune-simulated)
…
Inputs (--input NAME=PATH or NAME=https://…):
  model        a folder, upload or link, required. Your model
  data         a file, upload or link, required. Your training data

Questions (--preset QUESTION=OPTION):
  thoroughness How thorough?: quick | standard | thorough (usually standard)

Parameters (--param NAME=VALUE):
  epochs         integer, Epochs

$ idleo gpus
GPU MEMORY  FREE NOW  ONLINE  PRICE
8 GB        1         1       ₹7.20/h
12 GB       1         1       ₹10.80/h
16 GB       0         0       —
24 GB       0         0       —
```

### Run something

```
$ idleo run fine-tune-simulated --input model=./my-model --input data=./train.jsonl
model: 3 file(s), 12.0 MB
model: 100%  12.0 MB of 12.0 MB  76.9 MB/s
…
Booked 01a12ffa-824c-7565-8c16-74ed14ef1c3d. It can cost at most ₹100.00.
· provisioning on RTX 4060 Laptop  (₹0.00 so far)
· running on RTX 4060 Laptop  (₹0.00 so far)
epoch 1/3 step 1/15 loss 2.0045 …
…
Finished: completed.
Ran for 2m 00s.
Charged ₹0.36.

OUTPUT                     SIZE
adapter_model.safetensors  33.1 KB

Download them: idleo outputs 01a12ffa-… --download ./out
```

- **`--input NAME=PATH|https-URL`**, repeated for each input the template names. A folder goes up as a multi-file upload; a file as one; an `https://` link is fetched by the job inside its sandbox, never by us. With a template that takes one input, `--input PATH` alone is enough.
- **`--param NAME=VALUE`**, typed as the template says (`epochs=3`, `fast=true`). **`--preset QUESTION=OPTION`** answers a question; any you leave out use its usual answer.
- **`--gpu-memory 12GB`** asks for a class (8, 12, 16 or 24 GB) when the template lets you; **`--max-spend RUPEES`** is the most it may cost (the template's usual budget if left out); **`--max-price RUPEES`** is the most an hour you'll pay (the best GPU that fits, if left out); **`--max-runtime 90m`**.
- **`--detach`** books and prints the session's id without following it.
- **Uploads carry on.** Each file goes up in parts straight to storage, three at a time; what storage has taken is remembered in a small file. If you press Ctrl-C, lose the connection or close the laptop, run the same command again: it prints `carrying on from where it stopped` and sends only what's missing.
- **A dropped connection never books twice.** The booking's Idempotency-Key is kept in a file until the API answers, so running the same command again returns the session it made.
- **Ctrl-C detaches.** It never stops the session; it prints the commands that follow or stop it.

### Your sessions

```
$ idleo ls --all
ID                                    STATE      TEMPLATE             COST   BOOKED
01a12ffa-824c-7565-8c16-74ed14ef1c3d  completed  fine-tune-simulated  ₹0.36  2 min ago

$ idleo status 01a12ffa-824c-7565-8c16-74ed14ef1c3d
$ idleo logs 01a12ffa-824c-7565-8c16-74ed14ef1c3d          # the whole log so far
$ idleo logs 01a12ffa-824c-7565-8c16-74ed14ef1c3d -f       # and carry on until it ends
$ idleo watch 01a12ffa-824c-7565-8c16-74ed14ef1c3d         # state changes, no logs
$ idleo stop 01a12ffa-824c-7565-8c16-74ed14ef1c3d          # after a last checkpoint; --no-checkpoint to stop at once
$ idleo cancel 01a12ffa-824c-7565-8c16-74ed14ef1c3d        # one that hasn't started
```

`ls` shows what's running; `--all` includes what ended, and `--state running` filters. Logs follow the session's live event stream, and a dropped connection is picked up again.

### Outputs

```
$ idleo outputs 01a12ffa-824c-7565-8c16-74ed14ef1c3d
$ idleo outputs 01a12ffa-824c-7565-8c16-74ed14ef1c3d --download ./out
```

A download that was part-way carries on from where it stopped (a `.idleo-part` file becomes the file once it's whole), and a file that's already whole isn't fetched again.

### API keys

```
$ idleo keys
NAME            KEY             SCOPE  CREATED   LAST USED  ID
CLI on ci-box   idleo_bs5lgzd4  full   just now  never      01a12fff-…

$ idleo keys create --name ci-box     # approved in the web app, like a sign-in; the key is printed once
$ idleo keys revoke 01a12fff-…
```

Making a key always goes through the browser's approval, so a stolen key can never make another. Keys with read-only access are made in the web app (Account → API keys).

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Success, or the session completed (or you detached with Ctrl-C) |
| 1 | The session failed, was cancelled or ended with its hold refunded; or the API refused something no other code names |
| 2 | Wrong usage |
| 3 | Not enough credit |
| 4 | No host free |
| 5 | Not signed in (or the key was revoked) |
| 6 | Rate limited, or too many new sessions in an hour |
| 7 | Network |

With `--json`, an error is one JSON object on stderr: `{"error": "…", "problem": "insufficient-credit", "exit": 3}`.

## Development

```
cargo fmt --all --check
cargo clippy --all-targets -- -D warnings      # pedantic is on
cargo test                                     # unit tests, and the contract test against api/openapi.yaml
cargo deny check                               # permissive licences only
```

The API's types are generated from `../api/openapi.yaml` by `build.rs` (typify) and never committed; a change to the API that breaks the CLI fails the build, and `tests/api_contract.rs` checks every call the CLI makes is still in the contract. The end-to-end script (`scripts/e2e/agent-online.sh`) builds the CLI and drives it against the local stack: sign in, run the simulated fine-tune, resume a cut-off upload, download and resume outputs, make and revoke keys, sign out.
