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

`web/` stays one workspace with one design system. `src/sites/sites.ts` lists the sites; `src/sites/<site>/` holds each one's routes (`routes.tsx`), its screen list (`screens.ts`; the renter app's is `src/screens.ts`), its bars and account sections (`site.ts`) and anything else that differs. `vite.config.ts` builds one site at a time (`IDLEO_WEB_SITE`), with `@site` pointing at that site's folder, so the bundle can only hold that site's routes. `src/sites/frame.tsx` draws every site from its definition: the screens every site has (sign-in, the Terms, an invite code, the account, notifications) at the same paths, the one signed-in frame, the held account's screens. `scripts/build-sites.ts` (`pnpm build`) builds each site into its own folder: `dist/` for the renter app, `dist-host/` for the host site. Each has its own pages, its own `headers.json` (its own Content Security Policy, with exactly the origins it calls), its own redirects and rewrites, and the same budgets: first load at most 150 KB, the main script at most 96.6 KB with Brotli, LCP 2.0 s, CLS 0, axe 0, accessibility 100. The website keeps its stricter ones.

The renter app has no hosting screen and the host site no renting screen. Each links to the other once, quietly, in the account menu: "Earn with your laptop" and "Rent a GPU". A link that leads into the other site (a renter's PAN, which lives in the host site's payout details; a host's emails) is that site's whole address (`src/lib/site-links.ts`).

### The code stays apart

`OWNED` in `src/sites/sites.ts` names which parts of `src/` are which site's; everything else is shared. The build writes every chunk's source modules (`.vite/chunk-modules.json`), and `scripts/site-modules.ts` fails a build that holds another site's code anywhere; `scripts/first-load.ts` fails one whose first load does, the admin console's included. Shared code that a site-owned module needed moved into a shared place rather than being copied: the upload engine (`src/features/upload/`), which Book and an appeal's evidence both use, and the help form's list of what a ticket can be about (`@site/help`).

Until the console has its own site, it stays a lazily loaded chunk of the renter app's build, never in a first load (ADR 0027).

### Old addresses keep working

The renter app answers every old hosting address with a permanent redirect to the host site's (`MOVED_FROM_APP`, `src/sites/host/paths.ts`), query and all: `/hosting/` to `/`, `/hosting/machine/?id=…` to `/laptop/?id=…`, `/link/<id>/` to the host site's `/link/<id>/`. One rule (`scripts/redirects.ts`) is followed by the local server and written out for nginx (the web image) and for Cloudflare Pages (`_redirects`, `_headers`, `scripts/pages-config.ts`), so the three hosts agree.

### Every link goes to the right site

- The control plane's notices and emails: each notice says which site its link is a path on (`notifications.notifications.site`, `site` on `GET /v1/notifications`): the renter app's for a renter (`notifications.web-app-url`), the host site's for a host's laptops, earnings and payouts (`notifications.host-site-url`, `NOTIFICATIONS_HOST_SITE_URL`). The link stays a path, as the table has always required, and either site's bell opens a notice on its own site (`placeOf` in `src/lib/site-links.ts`); a notice from before this names no site and is read as the renter app's, an old `/hosting/` path going to the host site.
- A host link's approval page (ADR 0032): `hosts.link.web-app-url` is the host site.
- The Windows app: the setup program writes `host_url` into `agent.toml` (`IDLEO_SETUP_HOST_URL`), and the tray's and the launcher's Open dashboard go to its home (`Status::dashboard_url`). An install older than this has only `app_url`, and goes to its `/hosting/`, which redirects.
- The CLI's sign-in, invoices and a renter's notices stay on the renter app. The alerts' runbook links are the repository's, not a site's.
- Real visits' vitals name their site (`app`, `host` or `site`).

### Accounts and joining

One account works on every site. While access is by invitation (ADR 0024), an invite code says what it unlocks: renting, hosting or both (`identity.invite_codes.unlocks`; `create-invites --unlocks`). What an account has unlocked is `identity.user_sides`, one row a side, and `unlocked` on `GET /v1/me`. An active account with no rows has both, as every account had before codes had kinds and as the release before still activates people, so nothing is backfilled. A second code unlocks the other side; one that would unlock nothing new isn't used up.

The server holds each side to it: `@RequiresUnlocked(Side.RENTING)` on booking, buying credit and checking an image, `@RequiresUnlocked(Side.HOSTING)` on approving a link, adding a laptop, pricing it and its enrolment code, refused with 403 `side-not-unlocked` (the CLI's and the SDK's not-signed-in exit). A test fails on any call that needs the Terms and says no side, but the upload, which an appeal on either side uses. On a site whose side the account hasn't unlocked, the frame shows one page saying so ("Hosting isn't open for your account yet") with the code box (`features/invite/CodeForm.tsx`, shared with the invite step) and a link to the other site.

Each site has a first run, shown as a checklist on its home until every step is done, each step read from what the account already has (`components/ui/Checklist.tsx`): the renter's Terms, credit (the free credit counts) and a first test job (the CUDA test); the host's Terms, the Windows app, linking the laptop, its self-test, payout details (PAN and UPI) and its price. Hosting has no terms of its own: the Terms and the privacy notice are what a host accepts, for both sides. When a laptop is available is set in the tray (pausing, auto-pause), not on the site.

Open question: the admin console's invites screen makes codes of each kind with the console's move to its own site; until then an operator makes them with `create-invites --unlocks`.

### One API

CORS allows exactly the sites' origins per environment (`web.allowed-origins`); storage's CORS allows the renter app and the host site, since an appeal's evidence is uploaded from either.

### Deployment

The web image serves each build as a server block of its own (8080 the renter app, 8081 the website, 8082 the host site). The chart has a route and a certificate name for each site. In production each site is a Cloudflare Pages project of its own (docs/runbooks/deployment.md, "Four sites on Cloudflare Pages").

## Alternatives considered

- **One build, a site chosen at run time by the address.** One bundle, one deploy. Lost: every site's first load would hold every site's frame, and a mistake in one would show another's screens. Separate builds make the separation a property of the bundle, which a test can read.
- **One Vite build with an entry per site.** Faster to build. Lost: Rollup shares chunks between entries, so a module two sites use can drag a third site's code into a shared chunk, and the main script's one-file rule (ADR 0049) can't hold for two entries.
- **Separate repositories or packages per site.** Lost: the design system, the API client and the shared screens would be copied or published, and the shared screens would drift.
- **Keeping hosting a tab and only moving the console.** Lost: the product owner's decision, and the renter's first load would keep the host's code.

## Consequences

- A site is a folder and a line in `sites.ts`; a screen is a line in its site's routes and screens list.
- Every build takes a little longer: `pnpm build` builds both sites one after the other.
- Signing in is per site, since each is its own origin: someone who rents and hosts signs in twice, once on each. The sign-in provider needs every site's domain authorised (S5).
- `IDLEO_HOST_URL` is a required build setting for a deployed build, beside `IDLEO_SITE_URL` and `IDLEO_APP_URL`; the control plane needs `NOTIFICATIONS_HOST_SITE_URL` and `HOSTS_LINK_WEB_APP_URL` set to the host site.
- Open question: whether Cloudflare Pages' `_redirects` passes the query on. The runbook's first-deploy check asks for one; if it doesn't, a Single Redirect rule with "preserve query string" replaces those lines.
