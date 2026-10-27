# web

The renter and host web app: sign-in, Home, booking a GPU, a session live, all sessions and credit, the host side, and the admin console at `/admin/` (ADR 0027).

## The screens

| Path | Screen |
| --- | --- |
| `/` | Home: "What do you want to run?", a black bar for a running session, the services as tiles, "Book again" |
| `/book/` | Book: the services, then a service's own screen made from its template (`?service=…&template=…`): its numbered inputs and questions, the budget, Advanced, and "Your request" with the best free GPU, its price and "Request a GPU" (web/DESIGN.md "Book"); Book again and Run again add `&from=<session id>` |
| `/files/` | My files: the renter's uploads, to use again or delete |
| `/session/?id=…` | One session, following its state like a ride: finding its GPU, GPU found, running (timer, cost against the cap, the GPU's use and temperature, checkpoint, logs), then done with its outputs, or why it ended |
| `/sessions/` | All sessions, active or ended (`?show=ended`), a page at a time |
| `/credit/` | Credit and its history |
| `/sessions/` and the Hosting, Account and Credit tabs | On a laptop, from 1200 px, full width on a 12-column grid with the one header (web/DESIGN.md "Laptop layout") |
| The website: `/`, `/pricing/`, `/gpus/`, `/earn/`, `/docs/…`, `/terms/`, `/privacy/`, `/contact/` | Not the app: the website (ADR 0029) is its own build at its own address, loading none of the app's code; see "The public site" below |
| `/welcome/`, `/sign-in/` | A new account's first screen; sign-in |
| `/admin/…` | The admin console (ADR 0027, docs/runbooks/admin.md): its own lazily loaded chunk, which renters never download. Setting up the authenticator and the code check, then Dashboard, Users, Hosts, Sessions, Review queue, Money, Invites and the Audit log (`/admin/users/?id=…` and the like open one record). Every action asks why in one sheet. Anyone who isn't an admin sees "We can't find that page" |

How they're built, in short:
- **A service's booking screen is generated** from its template in the catalog (`src/features/book/steps.ts`, `params.ts`), never written per service.
- **Session state lives only in the query cache.** The session screen's event stream (`src/api/useLiveSession.ts`) and the lists' polling write it there (`writeSession`); it polls only while the stream is down.
- **Uploads go straight to storage** in parts (`src/features/book/upload.ts`), a folder of files or one, each part's ETag remembered in IndexedDB so the same files chosen again carry on where they stopped (`upload-resume.ts`); the build needs storage's origin for its Content Security Policy (`VITE_STORAGE_URL`).
- **Every problem type and every way a session can end has words:** `src/lib/problems.ts` (checked against `../api/problems.json`) and `src/lib/labels.ts` (checked against the control plane's and the agent's sources).

- **The design language:** [DESIGN.md](DESIGN.md). Every screen is checked against it.
- **The stack and why:** [ADR 0018](../docs/decisions/0018-web-app-foundations.md).

In short: Vite, React and TypeScript, built to static files that any host or CDN serves. Tailwind sits over our design tokens, with Radix primitives for components. TanStack Query and typed calls from [api/openapi.yaml](../api/openapi.yaml) handle data. Sign-in goes through one adapter: Firebase in production, the local sign-in server for local runs and tests.

## Run it against the local stack

You need Node 24.21.0 (`.node-version`) and pnpm 12.9.1 (corepack, or `npm i -g pnpm@12.9.1`).

1. **Start the stack** from the repository root:
   ```
   scripts/e2e/agent-online.sh --keep-running
   ```
   That's PostgreSQL, Valkey, MinIO, the development sign-in server and the control plane. Its `local` profile lets `http://localhost:3000` call the API.
2. **Set up the web app** in `web/`:
   ```
   cp env.local.example .env.local
   pnpm install
   pnpm dev
   ```
3. **Open http://localhost:3000** and sign in with any `@example.com` address; nothing is sent.
   - The address's local part is the user, the same user a `client_credentials` token with that `client_id` gets. That's how to give yourself credit with the `grant-credits` operator command (docs/runbooks/granting-credits.md).
   - "Continue with Google" signs in `local-google@example.com`.

A production build needs Firebase's settings (`VITE_AUTH=firebase`, `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN`, `VITE_FIREBASE_PROJECT_ID`), storage's origin for uploads (`VITE_STORAGE_URL`, R2's endpoint) and `IDLEO_DEPLOYMENT=production`. The build refuses the local sign-in adapter outside `IDLEO_DEPLOYMENT=local`, and refuses an API or storage that isn't https.

## Scripts

| Command | What it does |
| --- | --- |
| `pnpm dev` | The development server on :3000 |
| `pnpm build` | Type check, build `dist/`, pre-render each screen's first frame, write the CSP into every page and `dist/headers.json` |
| `pnpm serve` | Serve `dist/` as a host should: its headers, compression, caching (`IDLEO_DIST`, `PORT`) |
| `pnpm typecheck` | `tsc` in strict mode, after regenerating the API types from `../api/openapi.yaml` and the theme from the tokens |
| `pnpm lint` | ESLint (jsx-a11y strict, the design-token rule, sign-in SDKs only in `src/auth/`) and Prettier |
| `pnpm test` | Vitest: units and components |
| `pnpm build:site` | The public pages alone: their style sheet and scripts (`vite.site.config.ts`), the module that draws them, then `scripts/build-site.ts` writes them, `sitemap.xml` and `robots.txt` into `dist/` (`pnpm build` runs it last) |
| `pnpm size` | The first-load JavaScript budget, on `dist/` (or `IDLEO_DIST`), then the public pages' first-load budget |
| `pnpm e2e` | Playwright: every screen and state, and the journeys; needs the stack and `pnpm serve` |
| `pnpm lighthouse` | The performance and accessibility budgets on sign-in, Home, Book, the session screen, the Hosting tab and one machine, the 10 MiB log test, then `lhci assert` |
| `pnpm gallery` | The review gallery (below) |
| `pnpm screenshots` | Every screen of the redrawn parts as a picture, at 1440 and 390 px in both themes, into `../private/screens/<date>/` (the website's pages today, from `dist-website/`; build it first with `pnpm build:site`) |

CI runs all of them: `.github/workflows/web.yml`, and `scripts/e2e/agent-online.sh --web` for the browser checks against the real stack. The end-to-end workflow runs the web journeys too.

## The journeys

`e2e/journey.spec.ts` runs against the real local stack, with a real agent running sessions on its development runner. The script (`scripts/e2e/agent-online.sh`) creates `E2E_WEB_USER` and grants it ₹500; the journeys share one host, so they run one after another:
1. Home follows a session booked through the API to its charge.
2. On a laptop, the whole booking: `echo-input` with a greeting, a generated 12 MiB file uploaded in parts, the quote's numbers on the review, the session live (pulse, logs, checkpoints) to settled, the charge as the API has it, and `result.json` carrying the file's SHA-256. Its numbers go to `E2E_JOURNEY_REPORT`.
3. Run again fills the form from that session, with the same upload.
4. Not enough credit: the review gives both numbers and the button stays disabled.
5. Cancelling an upload deletes it.
6. The event stream drops (through a proxy the test cuts): the stale notice appears, polling keeps the figures moving, and the stream comes back.
7. On a phone, a running `long-run` session is stopped, shows its charge, and Run again works.

The admin journeys are `e2e/admin-journey.spec.ts` (the `admin-journey` project), run by `scripts/e2e/agent-online.sh` last: it makes a new person an admin with `grant-admin`, leaves one more host enrolled but not activated, and books a long run. In one page, because the admin session lives only in its memory, the journey sets up a second factor from the secret on the page and reaches the dashboard, activates the pending host, grants ₹1.25 and finds it in the audit log, and force-stops the run; the script then checks each action's one audit row in the database and the run's settlement.

The owner's journeys are `e2e/hosting-journey.spec.ts` (the `hosting-journey` project), run by `scripts/e2e/agent-online.sh` once every other scenario is over, so the machine they add changes nothing for them. They enrol a real agent with a fake RTX 4060 Laptop from the code on the page (`E2E_AGENT`, `E2E_AGENT_CA`, `E2E_GRPC_PORT`), and call `scripts/e2e/activate-host.sh` for the step an operator does by hand:
1. Add a machine from its name to connected, set its price, and have it activated.
2. Pause it and resume it; one host fewer fits a quote while it's paused.
3. Change its price; the quote's top price moves with it.
4. Earn from a session; the Hosting tab, Earnings and Sessions it ran agree, and equal the ledger.
5. Remove it; its agent is signed out and its earnings stay.

`scripts/e2e/agent-online.sh` (and `--web`) runs them. By hand against `--keep-running`: enrol and activate an agent and grant a user credit as the script's steps 4 to 7 do (docs/runbooks/local-stack.md), then `E2E_WEB_USER=<user> pnpm exec playwright test --project=journey`, with a fresh user each run.

## The budgets

Measured on Lighthouse's mobile profile (a mid-range phone on 4G), and failed in CI when broken. Never loosen one without the product owner's say. If one truly can't be met, stop and report it with numbers.

| Budget | Limit | Where it's checked |
| --- | --- | --- |
| Largest contentful paint | at most 2.0 s | `lighthouserc.json` |
| Interaction to next paint | at most 200 ms | `e2e/lighthouse.spec.ts` (web-vitals, CPU slowed 4×: Home's menu and moving between screens, typing in the booking form, the session screen while its log streams) |
| Layout shift | 0 | `lighthouserc.json`, and every screen in `e2e/layout-shift.spec.ts` |
| Total blocking time | at most 150 ms | `lighthouserc.json` |
| First-load JavaScript, signed-in home | at most 150 KB gzipped, and none of the admin console's code | `scripts/first-load.ts`, on the production build, sign-in adapter included |
| Accessibility | Lighthouse 100, zero axe violations | `lighthouserc.json`, and every screen and state in `e2e/*screens.spec.ts` |
| A 10 MiB log | at most 2,000 lines in the page, every interaction within the app's 200 ms on a CPU slowed 4× | `e2e/performance.spec.ts` |

The 10 MiB log test held its interactions to 100 ms until Oct 9, stricter than the app's own budget; it's now held to the app's 200 ms (the product owner's yes), and still to 2,000 lines in the page.

Lighthouse measures sign-in, Home, Book (at the fine-tune's screen, where the questions are) and the session screen (a long run, live) three times each on the mobile profile, each from a cold cache, and `lhci` asserts the median of the three: one run alone carries the machine's noise. The desktop profile runs once per screen, for the record. It also records, without asserting, a session the app has never listed opened by a link (`session-link`): its heading needs an API call on a new connection with a CORS preflight (ADR 0020). Every run's report, JSON and HTML, is in `.lighthouseci/` (`reports/` for the HTML) and `.lighthouseci-desktop/`, and CI keeps them as the `lighthouse` artifact of every run, passed or failed.

### Measuring Lighthouse

Every full Lighthouse run (`pnpm lighthouse`, the browser suite) also records Chrome's own throttling on each asserted mobile screen, the median of three runs, next to the simulated figure: report-only (the budget is the simulated 2.0 s), but it must stay under 2.5 s, so a drift shows before it is a problem. Those reports are in `.lighthouseci-devtools/`, not `.lighthouseci/`, which `lhci assert` holds to the budget.

Credit is measured like every other screen, with the figures an earlier visit kept (src/api/persisted-cache.ts). Beside that, three runs with none kept are report-only, with the same 2.5 s ceiling, in `.lighthouseci-first-visit/`. With nothing kept, its largest paint, the available credit, waits for `/v1/wallet`, which can't be asked for before the app's own script has run, and Credit's own page load costs one more file than Home (ADR 0039, "The Credit page's speed"). That puts it at about 2.4 s.

Lighthouse's default, `throttlingMethod: "simulate"`, loads the page unthrottled and works out what a slow phone would have seen. `devtools` loads it through Chrome's own throttling (a 150 ms round trip, 1.6 Mbit/s, the CPU slowed 4×), so the numbers are what the browser really painted. `E2E_LIGHTHOUSE_MEASURE=simulate|devtools scripts/e2e/agent-online.sh --web` measures instead of asserting: only Lighthouse runs, five times on each of Home, Hosting, Add a machine, Link, Book and the live session (`E2E_LIGHTHOUSE_PAGES`, `E2E_LIGHTHOUSE_RUNS` change that), and every run's LCP is in `measure-<method>.json`. Whatever Lighthouse ran, its log and every report are kept in `private/logs/lighthouse-<date-time>/` (`mobile/`, `desktop/`, `web-lighthouse.log`), whether the budgets held or not.

What the two say, on the same build and stack (Oct 20, five runs each, mobile, LCP in ms):

| Screen | simulate: runs | median | devtools: runs | median |
| --- | --- | --- | --- | --- |
| Home | 2258 1956 1951 1805 1951 | 1951 | 2834 2147 2128 2128 2125 | 2128 |
| Book | 1951 1954 1952 1953 1953 | 1953 | 2161 2147 2154 2144 2239 | 2154 |
| Hosting | 2261 1955 1955 1955 1955 | 1955 | 2792 2150 2154 2143 2149 | 2150 |
| Add a machine | 1952 1802 1954 1955 1952 | 1952 | 2140 2150 2143 2172 2141 | 2143 |
| Link | 1955 1951 1957 1952 1951 | 1952 | 2283 2166 2147 2168 2154 | 2166 |
| Session | 2406 2101 2101 2101 2101 | 2101 (a file too many, see below) | 2798 2187 2183 2173 2180 | 2183 |

The session screen's 2101 was a regression of this round's own, found by this measurement: showing every input's size had made the session route load `useQueries` (react-query's hook, used only by Book's draft) as a file of its own, one more round trip on a slow connection. One `useQuery` over all of a session's uploads (`features/session/useInputFacts.ts`) puts it back at 1951 to 1952 on all five runs. A new hook or helper on a route is a file to check for (`ls dist/assets` and which chunks the route preloads), whatever it does.

Both are steady apart from a first, slower run on a page (a cold browser); the median of three, as `lhci` asserts, hides it. Real throttling puts every screen at about 2.15 s, 150 to 200 ms above what the simulation says and above the 2.0 s budget. Which of the two is the real phone isn't settled by this table: the budget was set, and the screens tuned, against the simulation (a 1.95 s plateau), so `simulate` stays the method the budget is held to, and the devtools numbers are the open question in the report.

The wait for the largest paint (`afterPaint`'s `largestPaintSettled`, used by Add a machine and the link approval, to keep their one request off the way to the page's largest paint) is gone: on the same build, five runs each, LCP with it was 1951 1955 1952 1959 1954 (Add a machine, median 1954) and 1803 1803 1952 1956 1954 (Link, 1952); without it 1954 1806 1955 1956 1952 (1954) and 1955 1957 1952 1956 1802 (1955). The same, so real people no longer wait for it (about 150 ms of settling after the largest paint, up to a second).

- each page's first frame is pre-rendered and adopted, not redrawn;
- each page preloads only its own screen's code, and the frame's shared modules come in one file (`vite.config.ts`), since every extra file is another request the first screen waits on;
- the screen a page opens on is loaded before the first render, so React's Suspense never holds its paint back;
- anything later screens need loads two seconds in, or on hover or tap; the stop dialog's code only when it's needed;
- requests a screen doesn't need for its main content (Book's form and price hints, a session's stream and logs and their code, the header's credit, the refresh of restored figures) start after that content has painted, which means after its text is drawn in the app's font and the browser has reported the page's first contentful paint as presented (`afterPaint`, `useAfterPaint`): two frames alone can come before the text, or before a slow machine has put the frame on screen, and a request started then counts against the paint (Book went from 2.41 s to 1.95 s; on CI, a run whose form code started two frames after its paint but before it was presented took 2.14 s, against 1.89 s for one that waited);
- Book's loading frame already holds what to run (the title and the template choice), so its largest text doesn't wait on the form's code;
- the words for problems and react-query's mutation code are in the shell, as is the tick icon, rather than files of their own that Book and the session screen would each wait on (the session screen went from 2.10 s to 1.95 s);
- skeletons are exactly the height of what replaces them;
- a font swapping in moves nothing: each face has an Arial fallback scaled to its metrics (web/DESIGN.md "Typography"), and only Inter and Archivo at 125% / 900 are preloaded.

## Looking at the screens by hand

The pictures aren't compared this round (the product owner, Oct 8): look at the screens yourself, in light and dark, at a phone's width and a laptop's.
- **Every screen and state, from invented data:** with the stack up and `pnpm dev` (or `pnpm build && pnpm serve`) on :3000, `GALLERY_DIR=/tmp/screens pnpm exec playwright test e2e/screens.spec.ts e2e/session-screens.spec.ts e2e/book-screens.spec.ts e2e/files-screens.spec.ts` writes a full-page picture of each into that folder, named `<state>--<phone|laptop>-<light|dark>.png`.
- **The real thing:** sign in at http://localhost:3000 as a user with credit, with an agent online (docs/runbooks/local-stack.md), and use the browser's device toolbar at 390 px and a window of 1280 px. The theme is in the account menu. How to reach each state:
  - **Finding and found:** they last well under a second on the local stack; `/session/?id=…` of a session the fixtures hold shows them, as above.
  - **Running:** request "Long run, 10 minutes" from Book.
  - **Done:** request "Fine-tune a model" with any folder as the model ("Choose folder") and any file as the data, or stop a long run. Both stay in My files (the account menu) for Run again.
  - **A resumed upload:** choose a large file, reload while it's going, and choose the same file again: it carries on from its last part.
  - **No GPU free:** Book's "Needs a big GPU" (24 GB): the card says "No GPU is free right now" and the button waits.
  - **Failed:** stop the agent (Ctrl-C) while a long run is going; the session moves on, then fails once no other host is free.
  - **Low credit:** a user whose credit covers only a few minutes (grant a small amount), on a long run.
  - **Stale:** stop the control plane while a session screen is open: "Live updates stopped", then "Couldn't refresh".

## Screenshots

Comparing pictures is off for now (`VISUAL_COMPARE=1` turns it back on). When it's on, every screen and state is compared with a baseline picture in light and dark, at 390 and 1280 px. The baselines are a CI artifact (`visual-baseline`), not files in git, and they're compared on every run:
- a picture that differs by more than 0.2% of its pixels fails;
- a new screen's picture is added automatically.

**To accept an intended change:**
1. Run the **web** workflow by hand (Actions → web → Run workflow) with **update screenshots** ticked.
2. Look at the new pictures in that run's `visual-baseline` artifact.
3. Merge. Later runs compare against them.

Locally, pictures are rendered differently from CI's Linux, so compare against your own: `VISUAL_BASELINE_DIR=visual-baseline pnpm e2e -- --update-snapshots=missing` once, then `pnpm e2e`. Or skip comparing with `SKIP_VISUAL=1`.

## The review gallery

`pnpm gallery`, with the stack running and both builds served, writes every screen and state in light and dark, at 390 and 1280 px, plus recordings (sign-in to Home and stopping a session on a phone; a booking from template to outputs on a laptop), into `../private/review/web-booking/` (or `GALLERY_DIR`) with an `index.html` that lays them out side by side. With `BEFORE_DIR` (by default `../private/review/web-foundations/`), the fixes from the last review are shown beside how they looked before. The builds:
- `pnpm serve` for the local build;
- `IDLEO_DIST=dist-firebase PORT=3001 pnpm serve` for a Firebase build with placeholder settings, with `FIREBASE_WEB_URL=http://localhost:3001`.

It's under the gitignored `private/`, and the data in it is invented.

## Layout

| Path | What's there |
| --- | --- |
| `src/design/` | The tokens (the one place for colours, sizes and durations), the contrast check, the theme |
| `src/brand/` | The working name, the accent, the wordmark; `public/icon.svg` is the favicon |
| `src/auth/` | Sign-in: the adapters, and the one place the Firebase SDK is imported |
| `src/api/` | The typed client, the 401 retry, the queries and their cache rules, the home's kept figures, the event stream (`stream.ts`, `session-follower.ts`, `useLiveSession.ts`) and logs |
| `src/components/` | The shell (bars, menu) and the UI components |
| `src/features/` | The screens: `home`, `book` (the screen generator, the upload manager, the quote), `files` (My files), `session` (live: figures, logs, checkpoints), `sessions`, `credit`, `sign-in`, and `admin` (the console, its client with the admin session held in memory, the action sheet) |
| `src/lib/money.ts` | `formatPaise`, the only way money is shown |
| `e2e/` | Playwright: screens (`*screens.spec.ts`), layout shift, Firebase sign-in, the journeys, Lighthouse, the 10 MiB log, the gallery recordings; `fixtures.ts` is the one builder of invented data, and its figures add up (`src/test/fixtures.test.ts`) |
| `scripts/` | The theme generator, post-build (CSP, screens, pre-render), the server, the size check, the font subset, the gallery |

## The public site

The website (ADR 0029), at its own address (`IDLEO_SITE_URL`; the app has `IDLEO_APP_URL`): the landing page (`/`; `/about/` redirects to it), pricing, what each GPU runs, the host pitch with an earnings calculator, the docs, and placeholders for terms, privacy and contact. They are static HTML written at build time from `src/site/` into `dist-website/` by `pnpm build:site`, apart from the app's `dist/`, so nothing of the app can load on them and none of their pages are in the app. `pnpm serve:site` serves them on :3002; the app is `pnpm serve` on :3000.

- **Live figures** come from `GET /v1/public/availability`, asked after the page has painted by `src/site/islands/pricing.ts` and `earn.ts`. Every row they fill is already on the page at its final size, so nothing moves.
- **The docs** are `../docs/site/*.md` plus three pages made from the code: services from `../templates/catalog.yaml`, the command line from `../docs/site/generated/cli.md` (the CLI keeps it current), and the API from `../api/openapi.yaml`.
- **Settings:** a staging or production build, of the app or the website, needs `IDLEO_SITE_URL` (the website's https address, for the sitemap and canonical links) and `IDLEO_APP_URL` (the app's, where "Log in", "Get started" and "Start hosting" lead, built by `entryLinks` in `src/site/paths.ts`); a local build has :3002 and :3000. A deployed build without either is refused.
- **Budgets for public pages** (Lighthouse's mobile profile, the median of 3): LCP 1.5 s, TBT 100 ms, CLS 0, accessibility 100, SEO 100, and a first load of 80 KB (`pnpm size`). `lighthouserc.json` holds them beside the app's; `e2e/public-lighthouse.spec.ts` measures them.
- **Tests:** `src/site/*.test.ts(x)` (the calculator, the pricing figures, the pages), `scripts/site/*.test.ts` (the docs generators, the bundle) and `e2e/public-screens.spec.ts` (axe, the pricing states, no layout shift, no app code).
