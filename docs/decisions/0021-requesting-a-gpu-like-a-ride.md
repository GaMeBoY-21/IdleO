# 0021. Requesting a GPU like a ride: the look, no GPU or price to choose, the server-set ceiling, and services in the catalog

- **Status:** Accepted. Its look is superseded by ADR 0031 (Bento, Oct 16); its behaviour stays.
- **Date:** 2026-10-08

Sources:
- docs/design/redesign/*.html, the six reference screens
- web/DESIGN.md; ADRs 0015 (scheduling), 0018 (the web stack), 0019 (the booking contract), 0020 (booking and the live session)

## Context

Booking and the live session worked after round 2 (ADR 0020), but the product owner's review on Oct 8 found they looked like an admin tool: bordered boxes, small type, and a form of up to 15 fields, including a GPU to pick and a most-per-hour price to type, before anything happened. The renters we're building for (students, small teams) don't know what a fair price per minute is for an RTX 4080 Laptop, and shouldn't have to. A ride app solved the same problem: say where you're going, press one button, get the best car free now, see the price range before you press.

Two things in the API stood in the way. `maxPricePaisePerMin` was required on `POST /v1/sessions` and on the quote, so the web app had to ask for it; and the quote needed the booking's input, so no price could show until the upload had finished. And the web app had no way to group templates into the things a renter says they want to do ("Fine-tune a model"), so Home could only list templates.

## Decision

**The look** (web/DESIGN.md, rewritten): black and white, grey fills instead of bordered cards, big bold type (a nine-step scale from 13 to 72 px, 28 and up at weight 700 and -0.03em), one question per screen, one black primary button. The brand's indigo is only in the wordmark's ring and the focus ring. Every pair of colours is still checked for WCAG 2.2 AA contrast by `tokens.test.ts`, in both themes. The live session follows the state like a ride: "Finding your GPU" with the wordmark's ring breathing, "GPU found", then running with a big timer, the cost against the cap, the GPU's use and temperature, and the last checkpoint; then "Done" with the outputs, or why it ended with "Try again".

**Requesting without choosing a GPU or a price.** Book asks what to run, the data, and optional settings, then "Request a GPU". The scheduler matches the best free GPU that fits, as it always did (ADR 0015). Before the button, the renter sees the memory needed, the GPUs free now, the price range per hour and an estimate ("≈ ₹5–8": the template's typical runtime times the range, the one place money is rounded). A most-per-hour price can still be set in Settings; empty means the server picks.

**The server sets the ceiling.**
- `maxPricePaisePerMin` is optional on `POST /v1/sessions` and `POST /v1/session-quotes`. Left out, the ceiling is the highest price among the GPUs that fit right now, from the scheduler's own `CandidateFinder.free` with no price limit (`FittingPrices`, behind the `PriceCeilings` port that sessions declares and scheduling implements, so the dependency still points from scheduling to sessions).
- The session stores that ceiling, and the hold follows from it as before: 30 minutes at the ceiling, never past the spending cap. A ceiling chosen this way always lets at least one host take the booking, since matching uses the same filters.
- When nothing fits, a booking or quote without a ceiling is refused at once with a new problem type, `no-gpu-free` (409), and nothing is created. A booking that gives its own ceiling still waits for a host as before.

**A quote may leave out the input.** It checks everything else through the same validator (`SessionParams`, with a flag for the quote), so the price shows while the upload is still going. Booking still requires the input as the template says.

**Services in the catalog.** Each catalog file starts with a `services` list (`id`, `name`, `tagline`, `running_label`, `icon` from a fixed list, `hidden`), and every template names its `service`. The control plane refuses to start on a template whose service doesn't exist. `GET /v1/templates` returns `services[]`, and `service` on each template; a service without a template isn't offered. `catalog.yaml` has the production services from product surface 3.0 and a hidden `diagnostics` for the smoke test; `development.yaml` has `developer-tests`. Home's tiles, Book's first question and the live headline ("Fine-tuning on an RTX 4080 Laptop") all come from it.

**The GPU's use and temperature** are on the session view (`gpu.utilPct`, `gpu.tempC`), read from presence for the current attempt's GPU while it runs, null when unknown or stale, and never with the host's identity.

## Alternatives considered

- **Keep the GPU and price choice, restyled.** Lost: the review's complaint was the decisions, not only the look. Choosing between three GPUs at three prices is the work we want to take off the renter.
- **The median price of the GPUs that fit as the ceiling.** Lost: only half the free hosts would fit, so a request could wait or fail while a GPU it could use sat idle. The highest fitting price means the best free GPU can always take it; the renter still pays the matched GPU's own price, by the minute.
- **No ceiling at all, and a hold from the matched price.** Lost: the hold has to be known when the renter presses the button (ADR 0017: never a negative wallet), and the match happens after. A ceiling set at request time gives a hold the quote can show exactly.
- **Waiting for a host when nothing fits, without a ceiling.** Lost: there's no price to set a ceiling from, and a ride app says "no cars nearby" at once rather than taking the request. `no-gpu-free` lets Book say "No GPU is free right now" before anything is created.
- **Services written in the web app.** Lost: the CLI and any later client would need their own copy, and a service shown with no template behind it would be a dead tile. The catalog already describes every template once (ADR 0019).

## Consequences

- One button from Home to a running session; nothing to understand about prices first.
- A renter booked without a ceiling may pay up to the highest fitting price a minute, though the matched GPU's own price is what's charged. The hold is larger than with a low ceiling of their own, and the quote shows it before they press.
- A new problem type, `no-gpu-free`, which every client must have words for (`web/src/lib/problems.ts` is checked against `api/problems.json`).
- Adding a service is a catalog change; its tile appears once a template names it.
- The look is now set in web/DESIGN.md; a screen that breaks it is wrong, or that file changes first.
- Open question: whether a renter should ever be able to ask for a specific GPU model; nothing in the plan says yet.
