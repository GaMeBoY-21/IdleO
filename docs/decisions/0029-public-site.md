# 0029. The public site: pages built apart from the app, a public availability endpoint, generated references, and no made-up numbers

- **Status:** Accepted
- **Date:** 2026-10-14

Sources:
- ADRs 0018 (the web app's foundations and budgets), 0019 and 0022 (the catalog and what a renter brings), 0023 (hosting and the host's share), 0017 (what a minute costs)

## Context

Everything the web app showed needed a sign-in. A person who had heard of the product could see only a sign-in form, and a host with a gaming laptop had nowhere to learn what hosting asks of them. The plan lists the public pages (3.4): a landing page, pricing, what each GPU can run, the host pitch and the docs.

The gate: a signed-out visitor sees a landing page that explains the product in one screen, live pricing, what each GPU class can run, the host pitch with an honest earnings calculator, and docs whose CLI and API references come from the code. Each loads fast on a phone and has one clear next step.

Constraints:
- **The app's code must never load on a public page** (ADR 0018's budgets are for the app: 150 KB for Home). A visitor reading about pricing should download a few kilobytes, not a router, a query cache and a sign-in SDK.
- **Nothing on the site may be a promise we can't keep:** we provide the GPU and renters bring their own model, data and code (ADR 0022); no made-up numbers, users, testimonials or logos.
- **The plan is silent** on where the landing page lives while `/` is the signed-in Home, and on what to say when too few hosts are online to show a price range without showing one host's price.

## Decision

### The pages are static HTML, built apart from the app

- **Their own build.** `vite.site.config.ts` builds `src/site/` on its own: a style sheet and two small scripts, under `/site-assets/`. The app's modules are not in it, because the app is not part of that build. `src/site/render.tsx` draws every page to HTML at build time (React's server renderer), and `scripts/build-site.ts` writes each into `dist/<path>/index.html`, with the head (title, description, Open Graph tags, canonical link, the Content Security Policy), `sitemap.xml` and `robots.txt`.
- **No script unless something is live.** The landing page, GPUs, docs, Terms, Privacy and Contact have none: the first paint is the page. Pricing and Earn each load one script of a kilobyte or two, which asks for the live prices after the page has painted. Every row and line those scripts fill is on the page at its final size, so nothing moves (CLS 0).
- **A bundle test** (`scripts/site/bundle.test.ts`) builds the site and fails if any of its scripts holds React, the query cache, the router, a sign-in SDK, an `Authorization` header or any call but `/v1/public/availability`, or if a file under `src/site/` imports anything from the app but the plain modules it shares: money formatting (`formatPaise`, `parseRupees`), the host's share (`COMMISSION_PERCENT`, ADR 0023: still mirrored once), the class names helper and the brand.
- **The same look.** The tokens, type and buttons of web/DESIGN.md, from the same generated tokens; the product's name only from `brand.name`, and a test fails if a page's own words name it (the hand-written Markdown in docs/site/ says it in plain text, which is the one thing a rename must search for).
- **Terms and Privacy become public pages.** They were the app's, placeholders in its first load. They are now static public pages at the same addresses, still placeholders ("Being drafted."), and sign-in links to them with plain links. A Contact page is the same: no address has been chosen, so none is invented.

### The landing page's address

`/` is the signed-in Home. *(Superseded by the addendum below: the website has its own address and the landing page is its `/`.)*

### Public availability

- **`GET /v1/public/availability`**, no sign-in, in the scheduling module beside `/v1/availability`. For each GPU memory class (8, 12, 16 and 24 GB; a card belongs to the largest class its memory covers, and a card under 8 GB belongs to none): whether any host is online, and the lowest and highest price an hour among the online hosts.
- **Never fewer than three.** The live price range is given only with at least three hosts online in the class; with one or two, a price would be that host's. The endpoint never counts hosts and never names one. Below three it gives `livePricePaisePerHour: null`, and every class always carries `typicalPricePaisePerHour`, the range the hosts' price suggestion starts from (`scheduling.price-band-defaults`, ADR 0023), which the page labels "typical".
- **Cached for a minute,** in the server (one worked-out answer, whoever asks) and in `Cache-Control: public, max-age=60`. Limited per address by the same request limit as every call without a sign-in (`limits.requests-per-minute`, ADR 0025), because it is under `/v1`.
- **The starting credit** is `public-site.starting-credit-paise`, unset until someone decides what a new account gets, and the pricing page says nothing about it while it is. Nothing grants it by itself: the credit a new account has is what an operator grants (docs/runbooks/granting-credits.md).

### The pages' words, and the no-made-up-numbers rule

- **Where a number is live, it comes from the API; otherwise the page says "typical".** The GPUs page is all typical (nothing there is measured on our hosts: the plan's table and general knowledge), and says so on every line. The worked examples on the pricing page are the catalog's own typical minutes for a service's options, priced at the live (or typical) range of the 12 GB class; they are labelled typical and say which class. The earnings calculator is arithmetic on what the person chooses: hours free a day × price × a share rented (20% to start, "Occupancy depends on demand; early on it may be low.") × 80%, in whole paise, with the 80% from `COMMISSION_PERCENT`.
- **We provide the GPU.** No page suggests we supply models, images or transcription, and a test scans every page for the phrases that would.
- **Invented figures never go in.** No user counts, testimonials or logos; a test scans for them.

### Docs, with the references generated from the code

- **Hand-written pages** are Markdown in `docs/site/` (getting started, the host guide, the FAQ), with front matter. `scripts/build-site.ts` renders them to HTML.
- **Services,** from `templates/catalog.yaml`: each offered service (not `hidden`) with the inputs it takes, its questions and what it gives. While the signed templates are still to come, a service's inputs come from the development catalog's template for it, which mirrors the real one (templates/README.md); the page says so.
- **The command line,** from the CLI's own clap definition: `idleo docs --markdown` (a hidden command) prints it. The web build doesn't need Rust: the output is committed as `docs/site/generated/cli.md`, and the CLI's tests fail when it differs from what the command prints (`IDLEO_DOCS_WRITE=1 cargo test docs_reference` rewrites it), the way `api/problems.json` is kept.
- **The API,** from `api/openapi.yaml`: one static page with every operation (path, method, parameters, body, answers) and every schema they use, linked. The admin console's operations (`/admin/v1/…`, ADR 0027) are for the people who run the service and aren't in it; a test says so and checks every other operation is. There is no heavy client library.
- **Tests** build each page set and check completeness: every operation, every CLI command, the services from the catalog, an id on every heading.

### SEO, speed and checks

- **SEO:** a title and description on each page, Open Graph tags, a canonical link, `sitemap.xml` and `robots.txt`, from `IDLEO_SITE_URL`, which a staging or production build must set (https; a local one may leave it out).
- **Budgets for public pages, on Lighthouse's mobile profile, the median of three runs:** LCP 1.5 s, TBT 100 ms, CLS 0, accessibility 100 and SEO 100, and a first load of 80 KB (HTML, style sheet and scripts, gzipped; `scripts/site-size.ts`). The app's own budgets are unchanged. Measured: every page's LCP 1.2 s or less, TBT 0, CLS 0; the largest page's first load is 16 KB.
- **Browser tests** (`e2e/public-screens.spec.ts`): zero axe violations on every page, phone and laptop, light and dark; the pricing states (loading, loaded, nobody online, error with retry, stale); no layout shift as figures arrive; the calculator; that a public page loads none of the app; and the way in (landing, Get started, sign-in, Home).

## Alternatives considered

- **Pre-render the public pages with the app's own build** (more entries in `src/screens.ts`). Rejected: its modules are one graph with the app's, so a page would load the router and query cache, or the budget would be a hope. A separate build makes it impossible, and a test says so.
- **A static-site generator (Astro and similar).** Rejected: a new toolchain for thirteen pages, when the design tokens, Tailwind and React's renderer are already here and the docs only need Markdown to HTML.
- **A docs framework or an API-reference viewer** (Docusaurus, Redoc, Swagger UI). Rejected: heavy client code on a page the budgets cap at 80 KB, and "a static page, no heavy client library" is the brief.
- **Put the live prices in the page at build time.** Rejected: they would be a day or a deploy old, and the page promises today's.
- **Show a price range from the first host.** Rejected: with fewer than three hosts online a range is one host's price.
- **Show counts of online hosts.** Rejected: nobody asked for them, and a count under three identifies hosts.
- **Move Home off `/` so the landing page can have it.** Rejected for now: the signed-in screens, their tests and their baselines all assume it; the decision belongs to the round that decides addresses.

## Consequences

- A visitor can learn what this is, what it costs and what it asks of a host without signing in, on a few kilobytes.
- The references can't drift: the CLI's tests, the API contract test and the build tests fail first.
- Two things must be kept honest by people: the hand-written Markdown, and the GPUs page's "typical" lines.
- **Open question:** where the landing page lives at `/`, and whether the app moves; the contact address; the starting credit; the lawyer's Terms and Privacy; analytics, which the product owner decides (a privacy-friendly choice, not yet taken).
- A rename of the product needs `brand.name`, and a search of docs/site/ for the name in the hand-written pages.

## Addendum (2026-10-14)

- **Hosting's protections are promises, not facts, until the hardware round:** Earn, the host guide and the FAQ say hosting opens after our safety tests on real Windows laptops, and describe the sandbox and the rest as what hosting will have. A test refuses the present tense ("runs in a sandbox") on those pages.
- **The GPUs page names sizes, not models:** no image, speech or named model, only parameter counts and "your own models", so nothing reads as if we supply them. "What you can run today" is "What you can run".
- **The hand-written docs name the product only as `{{product}}`,** which the build fills from `brand.name`; a test refuses the name in plain text there.

## Addendum (Oct 17): the website first, the app at its own address

People arrive at the **website**, at the root address, and "Log in" and "Get started" take them to the **app**, which lives at an address of its own, as Shopify's website and store admin do. This settles the open question above.

- **Addresses.** In production `https://idleo.in/` is the website and `https://app.idleo.in/` the app. Locally `./start.sh` serves the website at http://localhost:3002/ and the app at http://localhost:3000/, and opens the website first. The names are build settings, never written in code: `IDLEO_SITE_URL` and the new `IDLEO_APP_URL`. A staging or production build needs both (https, not on the machine), and both builds refuse to start without them (`siteSettingsProblems` in `src/build-settings.ts`, checked by the app's `vite.config.ts` and by `scripts/build-site.ts`); a local build has the local stack's addresses.
- **Two builds, two outputs.** `pnpm build` makes the app into `dist/` and `pnpm build:site` the website into `dist-website/` (its intermediate files in `dist-site/`). Neither holds the other's pages: the website has the landing page (`/`), pricing, GPUs, earn, the docs, terms, privacy, contact and its own 404; the app has sign-in and everything after it. Only `/` is a path of both, each on its own address (`scripts/site/split.test.ts`). `scripts/serve.ts` serves either folder on its own port (`IDLEO_DIST`, `PORT`; `pnpm serve:site` for the website), the website's with its own `headers.json`, whose Content Security Policy allows only the theme script's hash and the API for the live prices.
- **The landing page moves to `/`.** `LANDING` in `src/site/paths.ts` is `/`; the sitemap, `robots.txt` and the header's wordmark point at it. `/about/` answers 301 to `/` (the website's `redirects.json`, which `scripts/serve.ts` honours, and a page with a refresh and a canonical link where a host can't send one), so old links keep working.
- **One place builds the links into the app.** `entryLinks(appUrl)` in `src/site/paths.ts` gives "Log in" (`<app>/sign-in/`), "Get started" (`<app>/sign-in/?start=1`, which the sign-in screen answers with "Create your account") and "Start hosting" (`<app>/hosting/`, through sign-in if needed). The website's header shows "Log in" as a plain link and "Get started" as the dark button; the docs write a link into the app as `{{app}}`, replaced at build time. No page spells an app path.
- **The app's side.** A signed-out visitor to any app address is sent to sign-in and brought back to the page they wanted (unchanged). The app's sign-in has a small "← idleo" back to the website, and its Terms and Privacy links lead to the website (`siteLink` in `src/lib/site-links.ts`, from `IDLEO_SITE_URL` written into the bundle). The app's `/` is still the signed-in Home.
- **Signed-in people on the website** still see the website. The header's "Log in" does not become "Open app": that needs the app's sign-in state, which means a request on page load, and the website's speed comes first.
- **CORS and sign-in.** The API's allowed origins (`web.allowed-origins`) must include the website's address, which the pricing page needs for the live prices, and the identity provider's authorised domains must include the app's address. Both are in docs/runbooks/deployment.md.
- **Why.** Speed (the website is static HTML with no app code, and now no app files beside it), search (the root address is the product's front door, in a sitemap of only public pages), and a clean "Log in", the way a visitor expects it.
- **Budgets unchanged,** measured on the website's `/` (LCP 1.5 s, TBT 100 ms, CLS 0, accessibility 100, SEO 100, first load 80 KB).
- **The API reference is two pages.** One page of every operation and every schema was 237 KB of HTML, and its largest contentful paint sat on the 1.5 s budget (1,351 to 1,501 ms, by the run). `/docs/api/` now holds the operations, grouped by what they act on, and `/docs/api/schemas/` the schemas (`apiReference` and `apiSchemas` in `scripts/site/openapi.ts`); each is about half the size, both are in the docs' navigation and the sitemap, and each links to the other. Every anchor still works: an operation keeps `#op-…` and `#tag-…` on `/docs/api/`, and an old `#schema-Name` link there is sent on to `/docs/api/schemas/#schema-Name` by a small script (`src/site/islands/schemaLinks.ts`, an island, since the page's Content Security Policy allows no inline script).
