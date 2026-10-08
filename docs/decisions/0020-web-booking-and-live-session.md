# 0020. The web app's booking and live session: one live-data layer, generated forms, direct uploads, and what kept the budgets

- **Status:** Accepted
- **Date:** 2026-10-08

Sources:
- docs/plan/product-surface.md sections 2, 3.1, 3.2 and 13 (round 2)
- web/DESIGN.md; ADR 0018 (the web stack and budgets) and ADR 0019 (the booking contract)
- docs/prompts/web-booking-and-live-session.md

## Context

The booking contract (ADR 0019) is built. This round draws the screens a renter uses most, Book, the live session and All sessions, and fixes what the first review of the foundations found. They have to keep ADR 0018's budgets (LCP 2.0 s, CLS 0, TBT 150 ms, INP 200 ms, 150 KB first load, accessibility 100) on Lighthouse's mobile profile, now for Book and the session screen as well as Home, and the session screen has to stay quick while a 10 MiB log streams in.

## Decision

**One live-data layer.**
- The event stream is read with `fetch`, a `ReadableStream` and `TextDecoderStream`, so the bearer token goes in a header; `EventSource` can't send one. The parser (`src/api/stream.ts`) follows the HTML standard's event-stream rules. No dependency was added.
- One follower (`src/api/session-follower.ts`) reopens the stream after the server's 30-minute close, at once, with a fresh token; retries a drop after 1 to 30 seconds of backoff with jitter; reports `down` when a retry fails; and stops for good once a snapshot says `settled`. A stream that closes within seconds of opening is reopened after a pause, so a misbehaving proxy can't make it spin.
- Every snapshot goes into the query cache through one function, `writeSession`: the session's own entry and its row in every list. Nothing else holds session state. While the stream is down, the screen polls `GET /v1/sessions/{id}` every 5 seconds and shows the stale notice. Home and All sessions poll, and stop once every session in view is settled.
- Logs are read from the cursor the screen holds (`src/api/logs.ts`), one read per session at a time, and never again what it has. At most 2,000 lines are in the page.

**Forms are generated.** One function turns a template's `params` into a zod schema and the fields (`src/features/book/params.ts`); nothing is written per template. The server's `validation-failed` `errors[].field` lands on the matching field.

**Uploads go straight to storage** (`src/features/book/upload.ts`): parts are `Blob.slice`s PUT with `XMLHttpRequest` (for progress), three at a time, retried three times, with fresh links on a 403; progress counts bytes storage has taken. The build now needs storage's origin, `VITE_STORAGE_URL`, for the Content Security Policy's `connect-src`; a staging or production build refuses one that isn't https.

**Words for every error.** The problem catalog is also written as `api/problems.json`, and the web app's one message map is checked against it. Every code a session can end with has a sentence and a next step, checked against the control plane's and the agent's sources.

**What kept the budgets** (each measured on Lighthouse's mobile profile):
1. **The frame's shared modules come in one chunk** (`vite.config.ts`). The new lazy screens share small modules with Home, and the bundler gave each its own file: 12 files before the first paint instead of 8, which cost every page 150 ms (sign-in went from 1.80 s to 1.95 s, Home from 1.95 s to 2.10 s) for only 5 KB more. In one chunk it's 3 files: sign-in 1.65 s, Home 1.80–1.95 s, 117.9 KB.
2. **Requests a screen doesn't need for its main content start after that content has painted** (`useAfterPaint`): the price hints, a session's stream and logs, the header's credit, and the refresh of figures restored from the last visit. Lighthouse counts every request started before the LCP paint; Book went from 2.55 s to 1.95 s.
3. **The booking form's code** (React Hook Form, zod) loads only for configure and review, and the stop dialog's (Radix) only when needed; both are fetched after the first screen.
4. **The screen a page opens on is loaded before the first render**, so React's Suspense never holds its paint back.
5. **A session opened from a list starts from the list's copy**, saying its figures are being refreshed, so its heading needs no API call to paint (principle 5). A cold deep link to a session the app has never listed still needs one call on a new connection with a CORS preflight: on the mobile profile that's 2.40 s, recorded as `session-link` in the Lighthouse summary but not asserted.
6. **Nothing changes size where it's watched:** the checkpoints are three fixed rows whose words change in place; a session's status has its own line; the laptop header's action area has a fixed width.

## Alternatives considered

- **`EventSource` with the token in the URL.** Lost: tokens in URLs end up in logs and histories, and ADR 0019 rules it out.
- **An SSE library** (`@microsoft/fetch-event-source` and similar). Lost: the parser is 80 lines, and the first-load budget has little room.
- **A Radix form kit, or a hand-written form per template.** Lost: one generator from the catalog is what makes the CLI and the SDK agree with the web app.
- **Quoting with a placeholder input while the upload is still going.** Lost: the quote is the same check as booking (ADR 0019), so it refuses an upload that isn't ready; the review waits for the upload instead, and says so.
- **Raising the LCP budget for the session screen.** Not done: the screen meets it as renters reach it; the cold deep link's number is reported instead.

## Consequences

- A deployment must set `VITE_STORAGE_URL` (R2's endpoint) for uploads to work.
- A screen that adds a request to its first paint, or a shared module outside the shell chunk, shows up in Lighthouse at once; the rules are in web/README.md "The budgets".
- **Open question:** a deep link to a session the app hasn't listed is 2.40 s on the mobile profile, over the 2.0 s budget. Serving the web app and the API from one origin (ADR 0018's consequence) would remove the preflight and the second connection; it's decided with deployment.
- **Open question:** banners that arrive while the screen is open (a host change, low credit, a failure) push the content below them down; DESIGN.md asks for reserved space or an overlay, which a banner whose need can't be known in advance can't have. Nikki to decide whether such banners should overlay instead.
