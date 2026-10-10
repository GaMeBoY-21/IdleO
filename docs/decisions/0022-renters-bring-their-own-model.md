# 0022. Renters bring their own model: folders in My files, named inputs and plain questions, GPU memory from the model, and hosts with room

- **Status:** Accepted
- **Date:** 2026-10-10

Sources:
- docs/plan/product-surface.md section 3.0, "The rule" (Nikki, Oct 9)
- docs/design/redesign/book-finetune-laptop.html, the target for a service's booking screen
- ADRs 0015 (scheduling), 0016 (sessions and storage), 0019 (the booking contract), 0021 (requesting a GPU like a ride)

## Context

On Oct 9 Nikki set the rule the product follows: **we provide the GPU; renters bring their own model, data and code, usually from their own laptop; we never supply a model.** We give them a ready environment (CUDA, PyTorch, the training or serving tools), and nothing more.

Two services broke the rule. "Generate images" ran a model we chose and shipped, and "Transcribe audio or video" ran a speech model we chose and shipped. Each would have made us the supplier of a model, with its licence, its outputs and its misuse ours to answer for, and neither had a version where the renter brings the model.

The rest of booking wasn't built for the rule either:
- An upload was one file of up to 20 GiB. A model is a folder (`config.json`, a tokenizer, several `.safetensors` shards) and can be 15 GB or more, sent over home internet, where a dropped connection meant starting again.
- A template took one input. A fine-tune needs at least two: the model and the data.
- An upload was used once and expired 30 days after it was made, so the same model went up again for every session.
- Booking showed a Settings drawer listing raw parameters (epochs, learning rate, LoRA rank). Nikki found it meaningless: renters think "how thorough", not "how many epochs".
- The GPU memory a session asked for was the template's minimum, whatever the model's size.
- Nothing stopped the scheduler from offering a session to a host whose disk couldn't hold the renter's files.

## Decision

**The rule is in the catalog.** `templates/catalog.yaml` says it at the top of its inputs section, and `CLAUDE.md` makes it a line every future template must follow. The image and transcription services, their simulated templates, the development runner's `generate-images` and `transcribe` kinds, their icons, tests and copy are gone.

**An upload is a folder of files, or one file, kept in My files.**
- `POST /v1/uploads` takes a name and each file's relative path and size: at most 1,000 files and 40 GiB together, each path relative, without `..`, each segment stored the way a name always was. Stored at `uploads/{id}/files/{path}`.
- Each file is a multipart upload of its own. Its part links are asked for as it's sent, a hundred at a time, so a 4,000-part model never gets 4,000 links at once. Completion takes up to a hundred files a call and checks each file's size; the upload is ready once every file is.
- A renter holds at most 60 GiB of pending and ready uploads (from 25).
- **My files:** `GET /v1/uploads` lists the pending and ready uploads; any ready one can be a session's input, again and again. An upload now expires 30 days after a session **last used** it (or after it was made, if none has), never while a live session uses it. Deleting one a live session uses is `upload-in-use`. The web app has a My files screen (name, size, files, last used, Delete, asked once more in the row), reached from the account menu and from each input's "From My files".

**An interrupted upload resumes.** The web app remembers each upload in IndexedDB (src/features/book/upload-resume.ts), keyed by the files' fingerprint (names, paths, sizes and dates, never their contents): the upload's id, its Idempotency-Key and every part storage took, with its ETag. Choosing the same files again, after a reload, a dropped connection or "Try again", opens the upload with the same key; the server answers with the same upload as it stands, and only the parts storage doesn't have are sent. Remembering is a convenience: if IndexedDB is missing, full or blocked, the upload starts over. Folders are chosen with "Choose folder" (`webkitdirectory`) or dropped on the input.

**Templates take named inputs and plain questions.** In the catalog (described once, served by `GET /v1/templates`, checked by one validator for booking and the quote):
- `inputs` replaces the single `input`: each has a name, label, help, `kind` (folder or file), `accepts` (upload, link or both), `required` and `max_size_gb`.
- `questions` are the plain questions, in order: a `preset` (options such as Quick, Standard and Thorough, each with typical minutes and the advanced parameters it `sets`), a basic `param`, or `gpu_memory` (a choice of the GPU classes).
- Every parameter has a `group`: `basic` is asked as a question; `advanced` sits behind one collapsed "Advanced" row with its default set.
- `budget_chips` and `default_budget` give the spending caps offered; the screen adds "Other amount".
- `vram_from_input` sizes GPU memory from the model (below); `you_get` says, in a few words, what comes back.
- A preset must name real advanced parameters with values that fit them, or the control plane refuses to start. The booking keeps the chosen option's id with the session, so Run again asks the same way. A parameter given in the request wins over what its preset sets.
- Errors name the input or question: `inputs.model`, `questions.thoroughness`.
- An upload reaches the job staged into `/work/input/<name>/`, paths kept, named by `SESSION_INPUT_<NAME>_DIR`; a link reaches it as `SESSION_INPUT_<NAME>_URL`, which the job fetches inside its sandbox, never the agent (ADR 0019).
- In the protocol, additions only: `InputRef.slot` (4), the input's name, and `StartSession.inputs` (11). The control plane fills `inputs`; field 10 (`input`) is deprecated, kept, and no longer sent.

**A service's booking screen is generated from its template** (src/features/book/steps.ts): "← All services", the service's name and its template's description; one numbered step per input, with Upload, From My files and, where allowed, Link; then each plain question, a preset as option cards with their typical time and "≈ ₹" at today's prices; the budget chips; and Advanced. On a laptop, "Your request" sits beside it: each input and answer, what you get, the GPU card, "Request a GPU" and the hold; on a phone, the GPU card, the button and the hold are a sheet at the foot. The Settings drawer is gone. "Run your code" is the same screen: its code and data, a command, and the GPU memory it needs.

**GPU memory from the model's size.** When `vram_from_input` names an input and that input is an upload, the memory needed is the larger of the template's minimum and `base_mb + per_gb_mb × the upload's size in GiB`, rounded up to the next class (8, 12, 16, 24 GB; a class asks for its size in thousands of MB, which every card of the class has). A link's size isn't known, so it needs the template's minimum, and the screen says so; Advanced can always ask for more. The screen works this out while the model is still uploading ("Your 14.2 GB model needs a 12 GB GPU") with the server's rule mirrored; the server works it out again, and its quote is final. A model that needs more than 24 GB can't be requested: the GPU card says how much it needs and that the biggest GPUs here have 24 GB, never "No GPU is free", since waiting won't help.

**Hosts need room for the renter's files.** The agent reports its free disk, where scratch folders and staged inputs go, in `Hello.free_disk_mb` and every `Heartbeat.free_disk_mb` (additions; a fake GPU's is configurable). The scheduler's hard filters, in `CandidateFinder` and nowhere else, skip a host whose free disk is below the inputs' total plus the template's scratch space, with a 10% margin; one that hasn't reported it has none. Matching, quotes and availability all use the same filter, so "3 free now" never counts a host that can't hold the files. The provisioning timeout grows with the inputs' total size.

## Alternatives considered

- **Keep the image and transcription services with a model we choose.** Lost: that's supplying a model, which the rule rules out. A renter who wants either can bring the model and run it with "Run your code".
- **Uploads as zips only.** Lost: renters would have to zip 15 GB first, on a laptop, and the agent would have to unzip (a non-goal here: a zip is staged as a file, and the job unzips it if it wants to). A folder keeps each file's path as it is.
- **Resume from the server's list of parts** (S3's ListParts). Lost: it needs a call per file, and the client already has each part's ETag the moment storage returns it. The server stays the source of truth for which files are done; the browser only remembers parts.
- **Resume in localStorage.** Lost: it's synchronous and small, and a 4,000-part upload's ETags don't fit. IndexedDB is where the app already keeps its sign-in.
- **A form per service, written by hand.** Lost: every new template would need web work, and the CLI and SDK would drift from it. One description in the catalog makes every surface's questions.
- **GPU memory from the model's parameter count in `config.json`.** Lost for now: the upload's size is known without reading anything, works for any format, and is close enough at the class's granularity. Open question: whether quantised models (4-bit) need a lower `per_gb_mb` of their own.
- **Check disk on the host when the offer arrives.** Lost: a host would decline after being chosen, costing an offer round (10 seconds each, three at most) and showing GPUs free that can't take the session.

## Consequences

- A model goes up once, from home internet, and survives a dropped connection; every later session uses it from My files.
- Adding a service is a catalog change: its screen, quote and validation come from the template.
- The booking screen's words come from the catalog: a template's labels, help and preset names are now product copy and are reviewed as such.
- Storage per renter grows (60 GiB), and uploads live as long as they're used. The expiry job and quota bound it; the nightly storage cost needs watching as renters keep models.
- Hosts that don't report free disk get no sessions; every supported agent release reports it.
- `V20` and `V21` drop the columns they copy from in the same migration, which is safe only because no real data exists yet. Once it does, a migration adds first and removes in a later release (CLAUDE.md).
- Open question: secret tokens for gated Hugging Face models (later, through `Credentials`); until then a link must be public.
