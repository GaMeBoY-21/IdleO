import { COMMISSION_PERCENT } from "@/lib/hosting";
import { DEFAULT_OCCUPANCY, OCCUPANCY_CHOICES } from "../calculator";
import { GPU_CLASSES } from "../gpus";
import { DOCS, type EntryLinks } from "../paths";
import {
  column,
  grid,
  largeButton,
  link,
  muted,
  primaryButton,
  sectionTitle,
  tile,
  tileApricot,
  tileDark,
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

const REQUIREMENTS = [
  "Windows 10 or 11",
  "An NVIDIA RTX graphics card",
  "Plugged in, with an internet connection that stays on",
  "Some free disk: you choose how much to keep for yourself",
] as const;

const field = "mt-1 h-14 w-full rounded-control bg-ground px-4 text-body text-text";

/**
 * The host pitch (ADR 0029): what hosting is, the safety promises, what it takes, and an honest earnings calculator
 * (src/site/calculator.ts, wired by src/site/islands/earn.ts). The figures it gives are arithmetic on what the person
 * chooses, and the page says occupancy depends on demand and may be low at first.
 */
export function Earn({ apiUrl, links }: { apiUrl: string | null; links: EntryLinks }) {
  return (
    <>
      <section className={`${column} pt-3.5 pb-6 md:pt-6 md:pb-8`}>
        <div className={tileApricot}>
          <h1 className="max-w-prose text-title md:text-question">Earn with your GPU</h1>
          <p className="mt-4 max-w-prose text-body text-apricot-fg md:text-option">
            If you have a gaming laptop or desktop with an NVIDIA RTX card, you can rent it out by the minute
            while you aren&apos;t using it. You set the price and keep {100 - COMMISSION_PERCENT}% of what
            renters pay. Payouts start with the paid pilot; until then earnings are counted but not paid out.
          </p>
          <div className="mt-8">
            <a href={links.startHosting} className={`${primaryButton} ${largeButton}`}>
              Start hosting
            </a>
          </div>
        </div>
      </section>

      <section className={`${column} pb-10 md:pb-14`} aria-labelledby="safety">
        <h2 id="safety" className={sectionTitle}>
          How hosting will keep you safe
        </h2>
        <p className={`${muted} mt-2 max-w-prose`}>
          Hosting opens after our safety tests on real Windows laptops. These are the protections it will
          have.
        </p>
        <ul className="mt-4 grid gap-2.5 md:grid-cols-2 md:gap-3.5">
          {SAFETY.map((item) => (
            <li key={item.title} className={tile}>
              <h3 className="text-option font-semibold">{item.title}</h3>
              <p className="mt-2 text-body text-text-muted">{item.text}</p>
            </li>
          ))}
        </ul>
        <p className="mt-4 text-small text-text-muted">
          Renters never see who you are, and you never see who rented your machine.{" "}
          <a href={`${DOCS}hosting/`} className={link}>
            The host guide
          </a>
        </p>
      </section>

      <section className={`${column} pb-10 md:pb-14`} aria-labelledby="requirements">
        <h2 id="requirements" className={sectionTitle}>
          What you need
        </h2>
        <ul className={`${grid} mt-4 md:grid-cols-2 xl:grid-cols-4`}>
          {REQUIREMENTS.map((item) => (
            <li key={item} className={`${tile} text-body text-text-muted`}>
              {item}
            </li>
          ))}
        </ul>
      </section>

      <section
        className={`${column} pb-14 md:pb-20`}
        aria-labelledby="calculator"
        data-calculator=""
        data-live-prices=""
        data-api={apiUrl ?? ""}
      >
        <h2 id="calculator" className={sectionTitle}>
          What could it earn?
        </h2>
        <p className="mt-2 max-w-prose text-body text-text-muted">
          Hours free a day × your price × the share of those hours that get rented ×{" "}
          {100 - COMMISSION_PERCENT}%. Change any of them.
        </p>
        <div className="mt-6 grid gap-2.5 md:grid-cols-2 md:gap-3.5">
          <div className={`${tile} grid gap-4`}>
            <label className="block text-small font-semibold">
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
              <label className="block text-small font-semibold" htmlFor="calc-price">
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
                className="mt-1 min-h-4.5 text-caption text-text-muted"
                data-role="hint"
              >
                &nbsp;
              </p>
            </div>
            <label className="block text-small font-semibold">
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
            <label className="block text-small font-semibold">
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
          <div className={tileDark} role="group" aria-label="Estimated earnings">
            <div aria-live="polite" className="grid gap-4" data-role="result">
              <div>
                <p className="text-small text-dark-muted">You keep, a day</p>
                <p className="tabular text-title text-live" data-role="per-day">
                  –
                </p>
              </div>
              <div>
                <p className="text-small text-dark-muted">You keep, in 30 days</p>
                <p className="tabular text-title text-live" data-role="per-month">
                  –
                </p>
              </div>
              <p className="min-h-15 text-small text-dark-muted" data-role="detail">
                Enter a price to see an estimate.
              </p>
            </div>
            <p className="mt-4 text-small font-semibold text-dark-fg">
              Occupancy depends on demand; early on it may be low.
            </p>
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
