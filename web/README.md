# web

The renter and host web app: sign-in, credit, sessions and, in the next rounds, booking and the host side. The admin console comes in Phase 2.

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

A production build needs Firebase's settings (`VITE_AUTH=firebase`, `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN`, `VITE_FIREBASE_PROJECT_ID`) and `IDLEO_DEPLOYMENT=production`. The build refuses the local sign-in adapter outside `IDLEO_DEPLOYMENT=local`, and refuses an API that isn't https.

## Scripts

| Command | What it does |
| --- | --- |
| `pnpm dev` | The development server on :3000 |
| `pnpm build` | Type check, build `dist/`, pre-render each screen's first frame, write the CSP into every page and `dist/headers.json` |
| `pnpm serve` | Serve `dist/` as a host should: its headers, compression, caching (`IDLEO_DIST`, `PORT`) |
| `pnpm typecheck` | `tsc` in strict mode, after regenerating the API types from `../api/openapi.yaml` and the theme from the tokens |
| `pnpm lint` | ESLint (jsx-a11y strict, the design-token rule, sign-in SDKs only in `src/auth/`) and Prettier |
| `pnpm test` | Vitest: units and components |
| `pnpm size` | The first-load JavaScript budget, on `dist/` (or `IDLEO_DIST`) |
| `pnpm e2e` | Playwright: every screen and state, and the journey; needs the stack and `pnpm serve` |
| `pnpm lighthouse` | The performance and accessibility budgets, then `lhci assert` |
| `pnpm gallery` | The review gallery (below) |

CI runs all of them: `.github/workflows/web.yml`, and `scripts/e2e/agent-online.sh --web` for the browser checks against the real stack. The end-to-end workflow runs the web journey too.

## The budgets

Measured on Lighthouse's mobile profile (a mid-range phone on 4G), and failed in CI when broken. Never loosen one without Nikki's say. If one truly can't be met, stop and report it with numbers.

| Budget | Limit | Where it's checked |
| --- | --- | --- |
| Largest contentful paint | at most 2.0 s | `lighthouserc.json` |
| Interaction to next paint | at most 200 ms | `e2e/lighthouse.spec.ts` (web-vitals, CPU slowed 4×) |
| Layout shift | 0 | `lighthouserc.json`, and every screen in `e2e/layout-shift.spec.ts` |
| Total blocking time | at most 150 ms | `lighthouserc.json` |
| First-load JavaScript, signed-in home | at most 150 KB gzipped | `scripts/first-load.ts`, on the production build, sign-in adapter included |
| Accessibility | Lighthouse 100, zero axe violations | `lighthouserc.json`, and every screen and state in `e2e/screens.spec.ts` |

What keeps them, learnt by measuring (ADR 0018):
- each page's first frame is pre-rendered and adopted, not redrawn;
- each page preloads only its own screen's code;
- anything later screens need loads two seconds in, or on hover or tap;
- skeletons are exactly the height of what replaces them;
- the font never swaps.

## Screenshots

Every screen and state is compared with a baseline picture in light and dark, at 390 and 1280 px. The baselines are a CI artifact (`visual-baseline`), not files in git, and they're compared on every run:
- a picture that differs by more than 0.2% of its pixels fails;
- a new screen's picture is added automatically.

**To accept an intended change:**
1. Run the **web** workflow by hand (Actions → web → Run workflow) with **update screenshots** ticked.
2. Look at the new pictures in that run's `visual-baseline` artifact.
3. Merge. Later runs compare against them.

Locally, pictures are rendered differently from CI's Linux, so compare against your own: `VISUAL_BASELINE_DIR=visual-baseline pnpm e2e -- --update-snapshots=missing` once, then `pnpm e2e`. Or skip comparing with `SKIP_VISUAL=1`.

## The review gallery

`pnpm gallery`, with the stack running and both builds served, writes every screen and state in light and dark, at 390 and 1280 px, plus a recording of sign-in to home on a phone, into `../private/review/web-foundations/` with an `index.html` that lays them out side by side. The builds:
- `pnpm serve` for the local build;
- `IDLEO_DIST=dist-firebase PORT=3001 pnpm serve` for a Firebase build with placeholder settings, with `FIREBASE_WEB_URL=http://localhost:3001`.

It's under the gitignored `private/`, and the data in it is invented.

## Layout

| Path | What's there |
| --- | --- |
| `src/design/` | The tokens (the one place for colours, sizes and durations), the contrast check, the theme |
| `src/brand/` | The working name, the accent, the wordmark; `public/icon.svg` is the favicon |
| `src/auth/` | Sign-in: the adapters, and the one place the Firebase SDK is imported |
| `src/api/` | The typed client, the 401 retry, the queries and their cache rules, the home's kept figures |
| `src/components/` | The shell (bars, menu) and the UI components |
| `src/features/` | The screens |
| `src/lib/money.ts` | `formatPaise`, the only way money is shown |
| `e2e/` | Playwright: screens, layout shift, Firebase sign-in, the journey, Lighthouse, the gallery recording |
| `scripts/` | The theme generator, post-build (CSP, screens, pre-render), the server, the size check, the font subset, the wordmark, the gallery |
