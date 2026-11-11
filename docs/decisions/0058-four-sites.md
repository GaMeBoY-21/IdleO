# 0058. Four sites: the website, the renter app, the host site and the console

- **Status:** Accepted
- **Date:** 2026-11-09

## Context

One web app (ADR 0018) served renters and hosts from one address, with hosting as one tab of six, and the admin console under `/admin/` (ADR 0027). The website was already its own build at its own address (ADR 0029). Renters and hosts want different things: a renter books GPUs and pays for minutes; a host leaves a laptop on and wants to know what it earned. Showing both in one frame made every screen a compromise, and every first load carried code for people who would never use it. The product owner's decision (Nikki's) is to split the product like Uber's rider and driver apps:

| Site | For |
| --- | --- |
| `idleo.in` | the website: marketing, pricing, docs |
| `app.idleo.in` | renters |
| `host.idleo.in` | hosts |
| `admin.idleo.in` | staff |

One IdleO account works on all of them, with the same Google or email sign-in. Renter credit and host earnings stay separate, as the ledger already has them.

## Decision

### One codebase, a build per site

`web/` stays one workspace with one design system. `src/sites/sites.ts` lists the sites; `src/sites/<site>/` holds each one's routes (`routes.tsx`), its screen list (`screens.ts`), its bars and account sections (`site.ts`) and anything else that differs. `vite.config.ts` builds one site at a time (`IDLEO_WEB_SITE`), with `@site` pointing at that site's folder, so the bundle can only hold that site's routes. `src/sites/frame.tsx` draws the renter app and the host site from their definitions: the screens both have (sign-in, the Terms, an invite code, the account, notifications) at the same paths, the one signed-in frame, the held account's screens. What every site is drawn with, the admin site's too, is `src/sites/screen.tsx`: a screen in its own chunk, the signed-out frame, sign-in, the Terms and the invite step, and the rules every address follows. `scripts/build-sites.ts` (`pnpm build`) builds each site into its own folder: `dist/` for the renter app, `dist-host/` for the host site, `dist-admin/` for the admin site. Each has its own pages, its own `headers.json` (its own Content Security Policy, with exactly the origins it calls), its own redirects and rewrites, and the same budgets: first load at most 150 KB (the admin site's 190 KB, below), the main script at most 96.6 KB with Brotli, LCP 2.0 s, CLS 0, axe 0, accessibility 100. The website keeps its stricter ones.

The renter app has no hosting screen and the host site no renting screen. Each links to the other once, quietly, in the account menu: "Earn with your laptop" and "Rent a GPU". A link that leads into the other site (a renter's PAN, which lives in the host site's payout details; a host's emails) is that site's whole address (`src/lib/site-links.ts`).

### The code stays apart

`OWNED` in `src/sites/sites.ts` names which parts of `src/` are which site's; everything else is shared. The build writes every chunk's source modules (`.vite/chunk-modules.json`), and `scripts/site-modules.ts` fails a build that holds another site's code anywhere; `scripts/first-load.ts` fails one whose first load does, and a renter's or a host's first load that calls `/admin/v1`. Shared code that a site-owned module needed moved into a shared place rather than being copied: the upload engine (`src/features/upload/`), which Book and an appeal's evidence both use, and the help form's list of what a ticket can be about (`@site/help`).

### The admin site

The console (ADR 0027) is the admin site's alone (`src/sites/admin/`): signing in, the Terms, the invite step and the console, nothing of renting or hosting, and no frame of the other sites' (the console draws its own). It keeps its `/admin/` addresses there, so a link, a bookmark, a runbook or a recorded test changes only its host; the site's root leads to `/admin/`. The renter app answers every old console address with a permanent redirect to the same path on the admin site, query and all (`MOVED_TO_ADMIN`, `src/sites/admin/paths.ts`), and has no page under `/admin/` (the web check fails if it does). Each console screen is a chunk of its own (`features/admin/console-screens.ts`), as every site's screens are; the site fetches the screen an address opens beside the console's frame, and the rest once that has painted.

The API holds the console to its site:

- **CORS.** `/admin/v1` takes the admin site's origin alone (`web.admin-origins`, `WEB_ADMIN_ORIGINS`); `/v1` takes the four sites', the admin site's for its sign-in (`com.idleo.controlplane.WebOrigins`).
- **The `Origin` check.** Defence in depth beside CORS: the admin chain refuses a request to `/admin/v1` whose `Origin` is any but the admin site's, as a path that doesn't exist, whatever CORS is configured to do. A request with no `Origin` isn't a page's (the operator's tools) and goes on to the admin checks.
- **Passkeys.** A passkey is made for the relying party `idleo.in` (`identity.admin.passkeys.rp-id`) as before, and WebAuthn lets a page use one made for its own domain or one above it, so they work on admin.idleo.in as they are (`AdminSitePasskeysTests`, through Spring Security's real checks). They're checked against the admin site's origin alone, so the same passkey is refused from any other site's page. Production refuses to start without an admin site, with one that is plain HTTP or on this machine, or with one off the passkeys' domain (`ProductionSettingsGuard`).

The admin site's first load is the console's frame and its control room, with the tables, the action sheet and the dialog the control room draws: 186.3 KB with Firebase on Nov 11, against 150 KB for the other sites, after each screen became its own chunk (about 232 KB before). Its budget is 190 KB (`firstLoadKb` in `src/sites/sites.ts`), the product owner's say: only staff open it, on laptops, and it never loads for a renter or a host.

### Old addresses keep working

The renter app answers every old hosting address with a permanent redirect to the host site's (`MOVED_FROM_APP`, `src/sites/host/paths.ts`), query and all: `/hosting/` to `/`, `/hosting/machine/?id=…` to `/laptop/?id=…`, `/link/<id>/` to the host site's `/link/<id>/`. One rule (`scripts/redirects.ts`) is followed by the local server and written out for nginx (the web image) and for Cloudflare Pages (`_redirects`, `_headers`, `scripts/pages-config.ts`), so the three hosts agree.

### Every link goes to the right site

- The control plane's notices and emails: each notice says which site its link is a path on (`notifications.notifications.site`, `site` on `GET /v1/notifications`): the renter app's for a renter (`notifications.web-app-url`), the host site's for a host's laptops, earnings and payouts (`notifications.host-site-url`, `NOTIFICATIONS_HOST_SITE_URL`). The link stays a path, as the table has always required, and either site's bell opens a notice on its own site (`placeOf` in `src/lib/site-links.ts`); a notice from before this names no site and is read as the renter app's, an old `/hosting/` path going to the host site. An admin's notice (an approval, a new sign-in, a paused flag or rollout) links to a console path: a path under `/admin/` is the admin site's wherever it's named, since the console's addresses are nowhere else, so its email goes to `notifications.admin-site-url` (`NOTIFICATIONS_ADMIN_SITE_URL`) and either bell opens it there, with no new column.
- A host link's approval page (ADR 0032): `hosts.link.web-app-url` is the host site.
- The Windows app: the setup program writes `host_url` into `agent.toml` (`IDLEO_SETUP_HOST_URL`), and the tray's and the launcher's Open dashboard go to its home (`Status::dashboard_url`). An install older than this has only `app_url`, and goes to its `/hosting/`, which redirects.
- The CLI's sign-in, invoices and a renter's notices stay on the renter app. The alerts' runbook links are the repository's, not a site's.
- Real visits' vitals name their site (`app`, `host`, `admin` or `site`).

### Accounts and joining

One account works on every site. While access is by invitation (ADR 0024), an invite code says what it unlocks: renting, hosting or both (`identity.invite_codes.unlocks`; `create-invites --unlocks`). What an account has unlocked is `identity.user_sides`, one row a side, and `unlocked` on `GET /v1/me`. An active account with no rows has both, as every account had before codes had kinds and as the release before still activates people, so nothing is backfilled. A second code unlocks the other side; one that would unlock nothing new isn't used up.

The server holds each side to it: `@RequiresUnlocked(Side.RENTING)` on booking, buying credit and checking an image, `@RequiresUnlocked(Side.HOSTING)` on approving a link, adding a laptop, pricing it and its enrolment code, refused with 403 `side-not-unlocked` (the CLI's and the SDK's not-signed-in exit). A test fails on any call that needs the Terms and says no side, but the upload, which an appeal on either side uses. On a site whose side the account hasn't unlocked, the frame shows one page saying so ("Hosting isn't open for your account yet") with the code box (`features/invite/CodeForm.tsx`, shared with the invite step) and a link to the other site.

Each site has a first run, shown as a checklist on its home until every step is done, each step read from what the account already has (`components/ui/Checklist.tsx`): the renter's Terms, credit (the free credit counts) and a first test job (the CUDA test); the host's Terms, the Windows app, linking the laptop, its self-test, payout details (PAN and UPI) and its price. Hosting has no terms of its own: the Terms and the privacy notice are what a host accepts, for both sides. When a laptop is available is set in the tray (pausing, auto-pause), not on the site.

The console's Invites screen makes codes of each kind (`unlocks` on `POST /admin/v1/invites`, both when it isn't given, a bulk request's too) and lists what each unlocks; an operator can still make them with `create-invites --unlocks`.

### Full, simple screens

"Full" means useful: at 1440 x 900 a main page's first screen holds real content, with no large empty area; "simple" means one primary action, plain words, and what matters most top left. `e2e/full-screens.spec.ts` holds every main page of both sites to it: a grid of points over the first screen beside the sidebar, each counted as content when it lands on a tile, a heading, text or a control, and empty when it lands on the page between them; under 60% fails, and each page's figure goes to the run's log and `web-full.json`.

- **The renter app's Home:** the services, each with its typical time and its typical cost (the typical price of the smallest GPU size it fits on, `GET /v1/public/availability`, never a guess); the credit with Add credit; the GPUs online by size (8, 12, 16, 24 GB) with their prices an hour, from the live API; this month's spend and GPU time as a bar a day (`GET /v1/usage`, metering's settlements less lost time given back, by day in India); the running and recent sessions; a new renter's checklist, whose test job runs in one click at its template's own budget; and the terminal (the command line and the SDK as their docs give them, a snippet with Copy). Its tabs on a phone are Home, Book, Sessions, Files and Credit.
- **The host site's home:** the earnings (this week, pending, the next payout's day and amount, 8 weeks of bars, `weeks` on `GET /v1/earnings`); each laptop; the demand for its GPU (`GET /v1/demand`: the last 28 days' bookings it could have run, asked, served and unmet, by hour in India, with fewer than 20 said to be too few to show a pattern) and what similar laptops ask; its score and plainly what would raise it; the last payouts with their TDS; a new host's checklist; and Help. Its tabs are Home, Laptops, Earnings, Payouts and Help; payouts have their own tab, no longer under Earnings.
- **The website:** its front door has two paths, Rent a GPU and Earn with your laptop. The rent page (`/rent/`) and the earn page each have how it works, the live prices or the earnings calculator, the sandbox, the questions and one button to their site; the rent page's sections are the landing page's own, and its price list Pricing's (`LivePrices`). Live figures only where the API has them; the rest say "typical".

One component per pattern: the bars (`components/ui/Bars.tsx`, the host's days and weeks and the renter's month), the copy button (`CopyButton`, the API key's too) and the checklist.

### One API

CORS allows exactly the four sites' origins per environment (`web.allowed-origins` for the renter app, the host site and the website; `web.admin-origins` for the admin site, the only origin of `/admin/v1`); storage's CORS allows the renter app and the host site, since an appeal's evidence is uploaded from either.

### Deployment

The web image serves each build as a server block of its own (8080 the renter app, 8081 the website, 8082 the host site, 8083 the admin site). The chart has a route and a certificate name for each site. In production each site is a Cloudflare Pages project of its own (docs/runbooks/deployment.md, "Four sites on Cloudflare Pages").

## Alternatives considered

- **One build, a site chosen at run time by the address.** One bundle, one deploy. Lost: every site's first load would hold every site's frame, and a mistake in one would show another's screens. Separate builds make the separation a property of the bundle, which a test can read.
- **One Vite build with an entry per site.** Faster to build. Lost: Rollup shares chunks between entries, so a module two sites use can drag a third site's code into a shared chunk, and the main script's one-file rule (ADR 0049) can't hold for two entries.
- **Separate repositories or packages per site.** Lost: the design system, the API client and the shared screens would be copied or published, and the shared screens would drift.
- **Keeping hosting a tab and only moving the console.** Lost: the product owner's decision, and the renter's first load would keep the host's code.
- **The console at the admin site's root** (admin.idleo.in/users/). Shorter addresses. Lost: every link and path the console, its runbooks, the control plane's work queues and notices, and its recorded tests name would change at once, for an address only staff read; keeping `/admin/` changes only the host.
- **A third value of a notice's `site`, `admin`.** Explicit. Lost: a migration and a column change for links whose path already says whose they are.
- **Holding the admin site to 150 KB.** Lost: its control room draws tables, the action sheet and a dialog; with all of them out it would still be about 150.4 KB, and getting under would mean a different control room. The product owner gave it 190 KB.

## Consequences

- A site is a folder and a line in `sites.ts`; a screen is a line in its site's routes and screens list.
- Every build takes a little longer: `pnpm build` builds the three sites one after the other.
- Signing in is per site, since each is its own origin: someone who rents and hosts signs in twice, once on each. The sign-in provider needs every site's domain authorised (S5).
- `IDLEO_HOST_URL` and `IDLEO_ADMIN_URL` are required build settings for a deployed build, beside `IDLEO_SITE_URL` and `IDLEO_APP_URL`; the control plane needs `NOTIFICATIONS_HOST_SITE_URL` and `HOSTS_LINK_WEB_APP_URL` set to the host site, and `WEB_ADMIN_ORIGINS` and `NOTIFICATIONS_ADMIN_SITE_URL` to the admin site.
- Staff sign in on the admin site as well, and an admin who rents or hosts signs in there separately too.
- Open question: whether Cloudflare Pages' `_redirects` passes the query on. The runbook's first-deploy check asks for one; if it doesn't, a Single Redirect rule with "preserve query string" replaces those lines.
