# 0018. Web app foundations: a static React app, a design system, sign-in behind one adapter, enforced budgets

- **Status:** Accepted
- **Date:** 2026-10-07

Sources:
- ADRs 0006 (the quality bar), 0009 (sign-in), 0013 (the local stack), 0014 and 0017 (money)
- web/DESIGN.md (the design language this app is checked against)

## Context

Phase 1's backend runs batch jobs end to end. The web app is what renters and hosts touch, and it should be the best-made part of the project. This round builds its foundations and one complete screen, the renter's home, for the product owner to review before the booking flow and the host side are built on them.

The constraints:
- Everything is behind sign-in and comes from our API, so the app can be static files.
- Hosting is decided with deployment, so nothing may tie the app to one host.
- The budgets are measured on Lighthouse's mobile profile, a mid-range phone on 4G, and enforced in CI:
  - largest contentful paint (LCP) at most 2.0 s;
  - interaction to next paint (INP) at most 200 ms;
  - layout shift (CLS) 0;
  - total blocking time (TBT) at most 150 ms;
  - at most 150 KB gzipped of first-load JavaScript for the signed-in home;
  - accessibility 100.

  A budget that truly can't be met is reported with numbers, never loosened.

## Decision

### The framework: Vite and React, not Next.js

1. **The prompt named Next.js; measured, it can't meet the first-load budget.** A static-export Turbopack build of Next.js 16.4.0 with React 19.3.0:

   | Part | Gzipped |
   | --- | --- |
   | Next.js's app-router runtime, with React DOM (React DOM alone: 62 KB) | 135.7 KB |
   | TanStack Query | 9.9 KB |
   | Our code | about 18 KB |
   | **In the page, before sign-in** | **163.9 KB** |
   | Plus Firebase Auth, needed for a token before the first API call | 29.9 KB |
   | **The home's real first load** | **about 194 KB** |

   Moving the Radix user menu out of the first load (it saved 29 KB) didn't close the gap. A webpack build was larger (243 KB). **The product owner chose to switch to Vite and React** rather than raise the budget.
2. **Vite 8.3.3** ([vite.dev](https://vite.dev)), with **React 19.3.0** and TypeScript in strict mode. The app is a static build: any host or CDN can serve it.
3. **Routing: wouter 3.13.0** ([github.com/molefrog/wouter](https://github.com/molefrog/wouter)), about 2 KB.
   - TanStack Router was the first choice for its typed routes, but it measured 19.6 KB, and with it the production first load was 149.1 KB: under the budget by 0.9 KB, with nothing left for the screens to come.
   - wouter does what this app needs: paths, links, the query string and lazy screens.
4. **What the static build can't do, and how it's covered:**
   - **No page per session id.** The session page takes its id in the query, `/session/?id=…`.
   - **No server to send headers.** The build writes the Content Security Policy into every page as a `<meta>` tag. The headers a meta tag can't carry go to `dist/headers.json` for the host: `frame-ancestors`, `Permissions-Policy`, `X-Content-Type-Options`, `Cross-Origin-Opener-Policy`.
   - **No rewrite rules.** Every screen's URL ends in a slash, and the build writes an `index.html` into each screen's folder, so any host serves a deep link.
   - `web/scripts/serve.ts` serves the build the way a host or CDN should: the headers, pre-compressed Brotli and gzip, and long caching only for hashed files. The end-to-end test and Lighthouse use it.
5. **Each page's first frame is pre-rendered at build time** (`src/prerender.tsx`, React's `renderToString`).
   - **Sign-in is complete in the HTML**, but for its form, which loads just after (React Hook Form and Zod), behind a placeholder of exactly its height.
   - **The signed-in screens show their frame** until sign-in has said who's there.
   - **On load, the app adopts the HTML** (`hydrateRoot`), so what painted first stays. A URL with a query (`?expired=1`, `?id=…`) may differ from the pre-rendered frame, so it's drawn afresh instead.

### The design system

6. **Tailwind CSS 4.3.3** ([tailwindcss.com](https://tailwindcss.com)), with its theme generated from `src/design/tokens.ts` by `scripts/tokens-css.ts`.
   - Tailwind's own colours, spacing steps, sizes, radii, shadows and breakpoints are cleared. Only the tokens exist as classes, so `bg-red-500` or `p-7` draws nothing.
   - A repo lint rule (`eslint-rules/design-tokens.js`) refuses raw colours and arbitrary values (`p-[13px]`) outside the tokens, the brand and Google's mark.
   - A test checks every contrast pair in both themes against WCAG 2.2 AA, and against `DESIGN.md`'s table.
7. **Radix primitives** (`radix-ui` 1.7.0, [radix-ui.com](https://www.radix-ui.com)) for anything with focus management or ARIA patterns (the menu), and components written in the repo in the shadcn/ui manner, so nothing is a black box.
8. **Icons from Lucide only** (`lucide-react` 1.52.0, [lucide.dev](https://lucide.dev)), one icon per import.
9. **The typeface is Inter 4.1** (SIL OFL 1.1, [rsms.me/inter](https://rsms.me/inter)): one self-hosted file of 25.5 KB, `public/fonts/inter-4.1-subset.woff2`, cut by `scripts/subset-font.sh`.
   - **Why Inter:** it has tabular figures and the rupee sign. The reasons are in `DESIGN.md`.
   - **No font swap:** it's preloaded with `font-display: optional`, over an Arial fallback scaled to Inter's metrics. A swap would move text sideways, because individual glyph widths differ even when the metrics match.

### Data and forms

10. **TanStack Query 5.104.1** ([tanstack.com/query](https://tanstack.com/query)) for server state. The cache rules are in `src/api/queries.ts`:
    - everything is fresh for 30 s;
    - the session list and the wallet poll every 5 s while a session is live, and an open session every 3 s;
    - a 4xx is never retried;
    - money is never updated optimistically.
11. **Typed API calls from the contract.** `openapi-typescript` 7.13.0 generates `src/api/schema.gen.ts` from `api/openapi.yaml` on every build, type check and test, and `openapi-fetch` 0.17.0 calls it. An API change that breaks the web app fails its type check.
12. **The contract is generated by springdoc 3.1.1**, which is built on Spring Boot 4.1.0; we run 4.1.1 ([springdoc.org](https://springdoc.org)).
    - It's committed as `api/openapi.yaml`, and `ApiContractTests` fails `./mvnw verify` when the generated document differs from the file.
    - It covers `/v1` only, never the actuator.
    - The document is served only in tests; the production guard refuses `springdoc.api-docs.enabled`.
    - Customised in `ApiContract`:
      - stable operation names (`sessionCreate`), tagged by controller;
      - a bearer scheme;
      - every error as a default `application/problem+json` response;
      - `Idempotency-Key` and `If-Match` required, and `ETag` and `Location` response headers declared;
      - record fields required unless `@Nullable`, and nullable ones typed `[x, "null"]` (`RecordFields`).
13. **React Hook Form 7.89.0 with Zod 4.6.5** for forms, using `zod/mini`, Zod's tree-shakeable form. For the sign-in form's one field it's 6 KB instead of 23 KB.
14. **Home keeps the renter's last figures in IndexedDB** (`src/api/persisted-cache.ts`, `@tanstack/query-persist-client-core`):
    - only the user, the wallet, the session list and template names;
    - restored only once sign-in has said who's there;
    - another user's cache is discarded;
    - dropped after 24 hours;
    - removed on sign-out.

    Restored figures say when they're from until fresh ones arrive. This carries `DESIGN.md` principle 5 ("data a screen has already seen is shown at once from the cache, then refreshed") across visits, which is what a renter checking a running job from their phone needs.

### Sign-in behind one adapter

15. **`src/auth/` exposes `signIn`, `signOut`, `getIdToken` and `useAuth`**, and nothing outside it knows the provider. The build writes in `VITE_AUTH`, so the other adapter's code isn't in the bundle. A lint rule allows `firebase` and `oidc-client-ts` imports only inside `src/auth/`.
16. **Firebase** (`firebase` 12.19.0, Google Identity Platform, ADR 0009) in staging and production: Google, and the passwordless email link.
    - `initializeAuth` keeps the session in IndexedDB. The SDK refreshes tokens, and our code never stores one.
    - The popup and email-link code loads only when someone signs in.
    - The address an email link was sent to is remembered on that device so it needn't be asked again. It's not a credential. Opened on another device, the link asks for it.
17. **OIDC** (`oidc-client-ts` 3.5.0) against the local sign-in server, for local runs and the end-to-end test.
    - The Firebase emulator issues unsigned tokens, which the control plane rightly refuses; this server signs real ones.
    - It signs in only invented `@example.com` addresses, without sending anything. The address's local part becomes the user through the server's `login_hint` mapping (`control-plane/local-sign-in.json`), so the browser and the scripts' `client_credentials` tokens are the same user (`LocalSignInTests`).
    - It keeps the user in IndexedDB, like Firebase.
    - **A build for staging or production refuses it** (`src/build-settings.ts`, checked when `vite.config.ts` loads, and tested by running a real build).
18. **A refused token is retried once.** A 401 refreshes the token and retries the request once; refused again, the person is signed out with the reason "expired", which the sign-in screen shows (`src/api/authed-fetch.ts`).

### Security

19. **A strict Content Security Policy.**
    - **Scripts:** only `'self'` and the SHA-256 hashes of the page's inline scripts, computed at build: there's one, the theme script that runs before first paint. Never `'unsafe-inline'`.
    - **Connections:** only to the API and the build's sign-in service.
    - Styles allow `'unsafe-inline'`, because Radix positions menus with style attributes, and styles can't run code.
    - Also `Referrer-Policy: strict-origin-when-cross-origin`, a `Permissions-Policy` that turns off the camera, microphone, location, payment, USB and topics, and `X-Content-Type-Options: nosniff`.
    - All of it is tested in `src/security/headers.test.ts`, and the browser tests run under it. Lighthouse's INP probe has to go through DevTools, because a script tag is refused.
20. **CORS for the web origin only.** `web.allowed-origins` lists the origins that may call `/v1` from a browser: bearer tokens, never cookies, with `ETag` and `Location` exposed. Only `localhost` is listed, and only in the `local` profile. The production guard refuses `*`, plain HTTP and `localhost`.

### Measuring the budgets

21. **Lighthouse 13.5.0 runs from Playwright** (`e2e/lighthouse.spec.ts`) in a browser profile that has signed in, so the home is measured signed in.
    - Storage reset is off for the home, and the HTTP cache is cleared first, so it's a cold load.
    - `lhci assert` (`@lhci/cli` 0.15.1, which bundles an older Lighthouse) checks the mobile reports against `lighthouserc.json`.
    - INP isn't part of a page load, so it's measured with `web-vitals` 6.2.3 during real interactions (the menu, the theme switch, moving between screens) on a CPU slowed four times.
    - First-load JavaScript is measured by `scripts/first-load.ts` on the production (Firebase) build: the entry and its static imports, plus the sign-in adapter.
    - Every screen is checked for zero layout shift with the API slowed down (`e2e/layout-shift.spec.ts`).
    - **Each screen gets one uncounted warm-up run (Oct 29, the product owner's yes).** On CI's first full run, Hosting's three runs were 2,103, 2,026 and 1,953 ms, a median of 2,026 against the 2,000 budget, with the others on a 1,953 ms plateau: a screen's first load after the browser was on another one reads slow. The budget (2,000 ms) and the median of three are unchanged; only a run that no one reads comes first.
    - **The simulated budget is the gate, everywhere.** `pnpm lighthouse` (`scripts/lighthouse.ts`) always runs `lhci assert` after the browser run, so the budgets have a result even when something report-only is over; it fails if either failed.
    - **Chrome's own throttling is report-only, and held two ways (Oct 29, the product owner).** On the machine the figures were set on, a ceiling of 2,500 ms. On CI, whose hosted runner reads about 0.4 s slower (Book 2,509 / 2,571 / 2,502 ms against about 2,150 ms on the Mac, with the simulated figure at 1,953 to 1,957 ms on both), the ceiling is reported but never fails the run; the run is held instead to the medians of the last green run on main, and fails if a screen is more than 10% slower (`LIGHTHOUSE_BASELINE`, `scripts/lighthouse-baseline.ts`). Credit with no kept figures is held the same way. Every run writes its medians to `.lighthouseci-devtools/baseline.json`, and only a green run on main keeps them (the `lighthouse-baseline` artifact, 90 days), so a slow run that failed never becomes the reference. With no baseline (the first run, or after it expired) the run only reports.
22. **Two things the measurements taught, both kept as rules:**
    - **What would compete with the first screen waits.** Code for later screens and the user menu loads two seconds in, or at once on hover or tap.
    - **What the first screen needs is preloaded.** Each page preloads its own screen's code and, on the signed-in screens, the sign-in adapter. Each page also preconnects to the API and the token service.

    Lighthouse counts every request started before the LCP paint.
23. **Visual baselines aren't in git.** CI keeps them as an artifact and compares each run with them; a picture that changes fails until a run is started with the update input (web/README.md).

### Tooling

24. Also pinned:
    - **Node 24.21.0 LTS** (`web/.node-version`, `engines`, `engine-strict`);
    - **pnpm 12.9.1** with a committed lockfile and `pnpm audit` in CI;
    - **TypeScript 6.0.3**: TypeScript 7.0 is current, but typescript-eslint supports TypeScript below 6.1 and openapi-typescript 5.x, so 7 can't be linted or generated against yet;
    - **ESLint 10.12.0** with typescript-eslint's strict type-checked rules, jsx-a11y strict, react-hooks, and the two repo rules;
    - **Prettier 3.9.9**;
    - **Vitest 5.0.3** with Testing Library;
    - **Playwright 1.63.0** with `@axe-core/playwright` 4.13.0.

    Install scripts are denied unless approved (`pnpm-workspace.yaml`).

## Alternatives considered

- **Next.js with a looser budget (200 KB, counting the sign-in SDK; or 175 KB, not counting it).** Offered to the product owner with the numbers. It keeps the named framework, but spends the budget the booking flow will need before it exists.
- **TanStack Router.** Typed routes are worth having, but at 19.6 KB they left 0.9 KB of headroom.
- **Next.js's server rendering instead of a static build.** It needs a Node server, which is a hosting choice, and its runtime is the weight problem.
- **The Firebase emulator for local sign-in.** Its tokens are unsigned, and a control plane that accepted them would be a control plane that accepts forged tokens.
- **Keeping tokens in `localStorage` ourselves.** Never: the SDKs keep their sessions in IndexedDB, as they're designed to, and our code never touches a token.
- **`font-display: swap`.** It shows the brand font sooner on a slow first visit, but the swap moves text. That's a layout shift on every first visit, against a budget of 0.
- **Raising the Chrome-throttled ceiling for CI (to about 2,700 ms).** It would pass today and say nothing about a drift of 200 ms below it; a baseline from main catches the drift on the machine that will run the check.
- **Committing visual baselines.** Screenshots differ between a Mac and CI's Linux, and binary churn in git makes every design change noisy. CI's artifact is the reference.

## Consequences

- The signed-in home's production first load is about 124 KB gzipped, with about 26 KB of room under the budget for what the next rounds add to it. Later screens load on their own.
- Measured on Lighthouse's mobile profile: sign-in's LCP is 1.80 s and the home's 1.95 s. The home is close to its budget, so the next screens must keep the rules in 22, and CI's numbers are the ones that count. The report gives the current values.
- A deployment must send `dist/headers.json`'s headers and serve the screens' folders. Any host or CDN can.
- A creeping slowdown of under 10% a run passes each time, since the baseline is only the last green run. The simulated 2.0 s budget, held on every run, is what stops it.
- Serving the web app and the API from one origin would remove the CORS preflight and a connection, and is worth considering with deployment.
- Open question: which host serves the web app, with deployment.
