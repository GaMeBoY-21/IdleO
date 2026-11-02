# 0049. What a screen loads before it paints

- **Status:** Accepted
- **Date:** 2026-11-02

## Context

Every app screen read 1,951 to 1,954 ms against the 2,000 ms budget (ADR 0018), on CI and on the Mac alike, so one more file on the first load turned CI red. None of them waited on an API call: each drew from figures kept from an earlier visit, in about 70 ms on a machine that isn't throttled.

Replaying Lighthouse's simulation on saved runs (`lanternLargestContentfulPaint`, with the sizes in the devtools log altered to try a change without building it) showed where that number comes from. The simulated largest paint is when the last request that finished before the real paint would finish on the slow phone. Everything fetched ahead of the paint counts, needed or not. On that phone:

- a page costs 601 ms before anything else can start, and a round trip is 150 ms, so readings move in steps of 150 ms;
- the bandwidth is shared equally among requests in flight, even ones still opening their connection, so a 1 KB file costs far more than its size;
- a new connection costs two round trips before its first byte; the page's own connection, already open, costs one.

On the signed-in screens the load was 168 KB in nine files, and `index.js`, nearly all React DOM, finished last.

## Decision

Each first load is the page, one main script, one file of the screen's own, the bundler's small runtime, and the two fonts. The page's largest paint no longer waits for anything else.

- **The sign-in adapter comes after the paint, when figures are kept.** `RequireAuth` used to hold every screen until the adapter said who was there. A screen now restores the figures kept for whoever was signed in here last (`keptUser`, from what the kept cache was saved for) and draws them, and `src/main.tsx` asks for the adapter once that has painted. A screen that does need a token first (a session the app has never listed, opened by a link) gets it fetched then and there. With nothing kept, it's asked for at once and the screen waits for sign-in, as before. No screen preloads the adapter any more. This is worth most in production: Firebase's adapter is 30 KB, against the local one's 16 KB.
- **The style sheet is in the page.** There is no style sheet to fetch, so one more request is gone, and the first paint needs only the page. It's 11 KB of the page with its compression, inside the first 14.6 KB a connection can send at once. Every screen uses it, and moving between screens loads no page.
- **The main script asks first.** The first request after the page takes the page's open connection, and it's a round trip ahead. It's the largest file by far, so `scripts/postbuild.ts` puts it ahead of the fonts.
- **One main script.** React, the frame and the main module are one chunk, `shell`; nothing is gained by keeping React apart, since the file that held it changed with every release anyway. Vite's modulepreload helper is off, with its table of every chunk's hashed name, which barely compresses; a page's own chunks are named in its HTML.
- **Home is a screen like any other,** so other screens don't carry its tiles.
- **The wordmark is drawn.** It's on every first screen, and its font file cost a request for five letters. `scripts/wordmark-path.py` writes the outline from the same font. The website keeps the file: its large footer wordmark is set in it.
- **The words for problem types aren't in the main script.** They're 3 KB, and no first screen shows one. They're fetched after the first screen, and the API client waits for them before it hands on a refused request, so `problemMessage` has them when it's asked. If they can't be fetched the network is what's wrong, and it says so.

The result, on all twelve screens that are asserted, on the same simulation and settings: 1,651 to 1,653 ms on the Mac, and medians of 1,652 to 1,747 ms on CI's slower runner, from 1,882 to 1,955 ms, with layout shift and accessibility as they were. Blocking time on CI stays at 57 ms or less, against 150. The real first paint, as Lighthouse simulates it, came down from about 1,500 ms to about 1,400 ms. The website, whose pages share only the wordmark, reads the same. Production's first load is 144.0 KB gzipped, from 148.6 KB, and about 114 KB of it comes before the paint on a screen with kept figures.

## Alternatives considered

Each measured on the same simulated phone.

- **Inter after the paint, over its metric-matched fallback.** Took a further 150 ms off some screens, and is the largest single saving. But Inter's letters aren't the same widths as Arial's, only the same on average, so when it arrives rows of buttons and wrapped lines move. Six of 58 layout-shift cases failed at one size each, and more would come with each new string. A better fallback for the bold weights (Arial Bold is 5.6% too wide at Inter's scale) made it fewer, not none. The budget for layout shift is 0.
- **Removing a font's preload and leaving its `@font-face`.** Slower, about 2,100 ms: the browser still fetches it for the first text it lays out, and now it starts later.
- **Splitting the shell into smaller shared chunks,** so each screen carries only what it uses. Smaller by 11 KB, but with up to seven extra small files: Session read 1,951 ms. A screen with a second file of its own reads a whole round slower than one with one.
- **A lazy chunk for a session that has ended.** Took Session's own file from 10 KB to 6 KB, and made an ended session 2,578 ms instead of about 1,950 ms: the cost moved to a state the budget doesn't measure.
- **Preact in place of React DOM.** About 50 KB less, the largest possible saving. Not tried: it's a framework change for headroom we now have, and `startTransition`, which keeps Book's form within the interaction budget, does nothing there.
- **Serving the local stack over HTTP/2.** Would read faster, since the simulator then needs no second connection. Not done: that changes the measure, not the app. Production is on HTTP/2 or 3 already (Cloudflare Pages).
- **Inlining only the CSS a screen's first frame uses.** The signed-in frames hold no content, so what they'd paint next would have no styles yet.

## Consequences

- **A person whose session has ended elsewhere can see their own kept figures for a moment,** up to a day old, in the browser they were kept in, until the adapter loads and says no one is here, then they're cleared. Signing out here still removes them, so a shared browser after a sign-out shows nothing. The kept cache was already readable in this browser's IndexedDB.
- **A screen that waits on its data gained nothing.** The adapter is no longer preloaded, so it's asked for only once the main script has run, which cancels what the lighter first load saved. Credit with nothing kept reads 2,257 ms (2,259 before), and a session opened by a link 2,278 ms (2,258 before). They stay over 1,800 ms, as both were: the figure they wait for can't be asked for before the adapter holds a token.
- **A hard load draws the avatar's letter from the kept account,** until the adapter says who's here.
- **Lazy screens ask for their own imports once they've loaded,** with no preload helper: a move to a screen whose chunks haven't arrived yet takes a round longer. Every screen's code is fetched just after the first screen, so that's a window of a few seconds.
- **Watch the main script's size and the count of files.** On Lantern's steps, the main script at about 97 KB with Brotli is under one more round, and one more file beside a screen's own costs a round. Past that a screen reads 1,802 ms, which is still 198 ms under the budget.
- The local stack and CI serve over HTTP/1.1, which is what's measured. On a host with HTTP/2 there's one connection, so the order of requests and the count of files matter less, and fewer bytes before the paint helps the same.
