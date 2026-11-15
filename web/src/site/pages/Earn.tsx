import { COMMISSION_PERCENT } from "@/lib/hosting";
import { DEFAULT_OCCUPANCY, OCCUPANCY_CHOICES } from "../calculator";
import { GPU_CLASSES } from "../gpus";
import { HOST_QUESTIONS as FAQ } from "../host-questions";
import { DOCS, type EntryLinks } from "../paths";
import {
  band,
  column,
  exampleTag,
  frame,
  grid,
  largeButton,
  link,
  muted,
  primaryButton,
  secondaryButton,
  sectionTitle,
  tile,
  tileRaised,
} from "../ui";

/** What hosting will have when it opens (ADR 0029): the sandbox is still being finished and tested on real laptops. */
const SAFETY = [
  {
    title: "A locked box",
    text: "Renters' work will run in a sandbox: no access to your files, your folders or your home network, and no way out to your other devices.",
  },
  {
    title: "Your files stay yours",
    text: "A renter's job will get its own scratch space and nothing else: none of your folders mounted into it, and it will run as a plain user, never as an administrator.",
  },
  {
    title: "Take it back any time",
    text: "You'll pause when you want your machine, for a few hours or until you resume, and remove it whenever you like.",
  },
  {
    title: "A 2-minute stop",
    text: "Work will run on a short lease, renewed every 30 seconds. If your machine and our servers lose touch, it will stop within two minutes.",
  },
] as const;

/** How hosting works, in four steps (ADR 0058). */
const HOW = [
  {
    title: "Install the Windows app",
    text: "It checks the laptop can host: Windows, the RTX card, the sandbox.",
  },
  {
    title: "Link the laptop",
    text: "The app opens a page on the host site, where you approve it. No code to type.",
  },
  {
    title: "Set your price",
    text: "You choose what an hour costs. Pause it, or let it pause itself, when you play.",
  },
  {
    title: "Earn by the minute",
    text: "Every minute a renter's job runs counts, and you see it as it does.",
  },
] as const;

const REQUIREMENTS = [
  "Windows 10 or 11",
  "An NVIDIA RTX graphics card",
  "Plugged in, with an internet connection that stays on",
  "Some free disk: you choose how much to keep for yourself",
] as const;

const field =
  "mt-1 min-h-control w-full rounded-control border border-control bg-surface-1 px-button text-body text-ink pointer-coarse:min-h-touch";

/** The earnings card's days, as a share of its height: an example, labelled so. */
const WEEK = [30, 0, 55, 80, 25, 0, 60] as const;
const DAYS = ["M", "T", "W", "T", "F", "S", "S"] as const;

/**
 * The Windows app's download (ADR 0034): "Windows app: coming soon" in the page as built, swapped in place for the
 * Download for Windows button, with its version and size, by src/site/islands/release.ts once the API says a release
 * exists. Both fill the same box, so nothing moves; never a download that isn't there.
 */
function WindowsDownload({ apiUrl }: { apiUrl: string | null }) {
  return (
    <div className="flex min-h-10 items-center gap-2" data-download="" data-api={apiUrl ?? ""}>
      <a
        data-role="download"
        hidden
        href={apiUrl ? `${apiUrl}/v1/agent-releases/latest/setup` : undefined}
        className={`${secondaryButton} ${largeButton}`}
      >
        Download for Windows
      </a>
      <span data-role="download-meta" hidden className="text-small text-ink tabular" />
      <span data-role="download-soon" className="text-small text-ink">
        Windows app: coming soon
      </span>
    </div>
  );
}

/**
 * The host pitch (ADR 0029): what hosting is, the safety promises, what it takes, and an honest earnings calculator
 * (src/site/calculator.ts, wired by src/site/islands/earn.ts). The figures it gives are arithmetic on what the person
 * chooses, and the page says occupancy depends on demand and may be low at first.
 */
export function Earn({ apiUrl, links }: { apiUrl: string | null; links: EntryLinks }) {
  return (
    <>
      <section
        className={`${column} grid grid-cols-1 items-center gap-12 pt-6 pb-10 md:pt-12 md:pb-16 xl:grid-cols-2`}
      >
        <div className="flex flex-col gap-4">
          <h1 className="max-w-form text-hero">Earn with your GPU</h1>
          <p className="max-w-prose text-body text-ink-subtle md:text-card">
            If you have a gaming laptop or desktop with an NVIDIA RTX card, you can rent it out by the minute
            while you aren&apos;t using it. You set the price and keep {100 - COMMISSION_PERCENT}% of what
            renters pay. Payouts start with the paid pilot; until then earnings are counted but not paid out.
          </p>
          <div className="mt-4 flex flex-wrap items-center gap-2">
            <a href={links.startHosting} className={`${primaryButton} ${largeButton}`}>
              Start hosting
            </a>
            <WindowsDownload apiUrl={apiUrl} />
          </div>
        </div>
        <div
          role="img"
          aria-label="An example of the host's earnings card: a week of days as bars, each as tall as its share of the busiest"
          className={`${frame} flex flex-col gap-4`}
        >
          <div className="flex items-center justify-between gap-2">
            <span className="text-card">Earned this week</span>
            <span className={exampleTag}>Example</span>
          </div>
          <div className="flex h-chart items-end gap-2">
            {WEEK.map((height, index) => (
              <span key={index} className="flex h-full flex-1 flex-col items-center justify-end gap-1">
                <span
                  className={`w-full rounded-tag ${height === 0 ? "bg-hairline" : index === WEEK.length - 1 ? "bg-live" : "bg-hairline-tertiary"}`}
                  style={{ height: height === 0 ? "4px" : `${height}%` }}
                />
                <span className="text-caption text-ink-subtle">{DAYS[index]}</span>
              </span>
            ))}
          </div>
          <p className="text-caption text-ink-tertiary">
            An example, not real earnings. Every minute a renter&apos;s job runs counts, and you see it as it
            does.
          </p>
        </div>
      </section>

      <section className={`${column} ${band}`} aria-labelledby="how">
        <h2 id="how" className={sectionTitle}>
          How it works
        </h2>
        <ol className="mt-4 grid grid-cols-1 gap-2 md:grid-cols-4 md:gap-4">
          {HOW.map((step, index) => (
            <li key={step.title} className={`${tile} flex flex-col gap-2`}>
              <span className="text-hero text-ink-tertiary">
                <span className="sr-only">Step </span>
                {index + 1}
              </span>
              <h3 className="text-card">{step.title}</h3>
              <p className={muted}>{step.text}</p>
            </li>
          ))}
        </ol>
      </section>
      <section className={`${column} ${band}`} aria-labelledby="safety">
        <h2 id="safety" className={sectionTitle}>
          How hosting will keep you safe
        </h2>
        <p className={`${muted} mt-2 max-w-prose`}>
          Hosting opens after our safety tests on real Windows laptops. These are the protections it will
          have.
        </p>
        <ul className="mt-4 grid grid-cols-1 gap-2 md:grid-cols-2 md:gap-4">
          {SAFETY.map((item) => (
            <li key={item.title} className={tile}>
              <h3 className="text-card">{item.title}</h3>
              <p className="mt-2 text-body text-ink-subtle">{item.text}</p>
            </li>
          ))}
        </ul>
        <p className="mt-4 text-small text-ink-subtle">
          Renters never see who you are, and you never see who rented your machine.{" "}
          <a href={`${DOCS}hosting/`} className={link}>
            The host guide
          </a>
        </p>
      </section>

      <section className={`${column} ${band}`} aria-labelledby="requirements">
        <h2 id="requirements" className={sectionTitle}>
          What you need
        </h2>
        <ul className={`${grid} mt-4 md:grid-cols-2 xl:grid-cols-4`}>
          {REQUIREMENTS.map((item) => (
            <li key={item} className={`${tile} text-body text-ink-subtle`}>
              {item}
            </li>
          ))}
        </ul>
      </section>

      <section className={`${column} ${band} flex flex-col gap-2`} aria-labelledby="faq">
        <h2 id="faq" className={sectionTitle}>
          Questions
        </h2>
        {FAQ.map((item) => (
          <details key={item.question} className={tile}>
            <summary className="cursor-pointer text-card">{item.question}</summary>
            <p className={`${muted} mt-4`}>{item.answer}</p>
          </details>
        ))}
      </section>
      <section
        className={`${column} ${band}`}
        aria-labelledby="calculator"
        data-calculator=""
        data-live-prices=""
        data-api={apiUrl ?? ""}
      >
        <h2 id="calculator" className={sectionTitle}>
          What could it earn?
        </h2>
        <p className="mt-2 max-w-prose text-body text-ink-subtle">
          Hours free a day × your price × the share of those hours that get rented ×{" "}
          {100 - COMMISSION_PERCENT}%. Change any of them.
        </p>
        <div className="mt-6 grid grid-cols-1 gap-2 md:grid-cols-2 md:gap-4">
          <div className={`${tile} grid grid-cols-1 gap-4`}>
            <label className="block text-small font-medium">
              Your GPU's memory
              <select id="calc-class" defaultValue="12" className={field}>
                {GPU_CLASSES.map((gpu) => (
                  <option key={gpu.gb} value={gpu.gb}>
                    {gpu.gb} GB
                  </option>
                ))}
              </select>
            </label>
            <div>
              <label className="block text-small font-medium" htmlFor="calc-price">
                Your price, ₹ an hour
              </label>
              <input
                id="calc-price"
                inputMode="decimal"
                autoComplete="off"
                aria-describedby="calc-price-hint"
                className={`${field} tabular`}
              />
              <p
                id="calc-price-hint"
                className="mt-1 min-h-4.5 text-caption text-ink-subtle"
                data-role="hint"
              >
                &nbsp;
              </p>
            </div>
            <label className="block text-small font-medium">
              Hours a day your machine is free to rent
              <input
                id="calc-hours"
                type="number"
                min={1}
                max={24}
                step={1}
                defaultValue={8}
                className={`${field} tabular`}
              />
            </label>
            <label className="block text-small font-medium">
              Share of those hours that get rented
              <select id="calc-occupancy" defaultValue={String(DEFAULT_OCCUPANCY)} className={field}>
                {OCCUPANCY_CHOICES.map((choice) => (
                  <option key={choice} value={choice}>
                    {choice}%
                  </option>
                ))}
              </select>
            </label>
          </div>
          <div className={tileRaised} role="group" aria-label="Estimated earnings">
            <div aria-live="polite" className="grid grid-cols-1 gap-4" data-role="result">
              <div>
                <p className="text-small text-ink-subtle">You keep, a day</p>
                <p className="tabular text-title text-live" data-role="per-day">
                  –
                </p>
              </div>
              <div>
                <p className="text-small text-ink-subtle">You keep, in 30 days</p>
                <p className="tabular text-title text-live" data-role="per-month">
                  –
                </p>
              </div>
              <p className="min-h-15 text-small text-ink-subtle" data-role="detail">
                Enter a price to see an estimate.
              </p>
            </div>
            <p className="mt-4 text-small text-ink">Occupancy depends on demand; early on it may be low.</p>
          </div>
        </div>
        <div className="mt-8">
          <a href={links.startHosting} className={`${primaryButton} ${largeButton}`}>
            Start hosting
          </a>
        </div>
      </section>
    </>
  );
}
