import { brand } from "@/brand/brand";
import { COMMISSION_PERCENT } from "@/lib/hosting";
import type { SiteContext } from "../context";
import { GPU_CLASSES } from "../gpus";
import { EARN, PRICING } from "../paths";
import {
  band,
  bandTitle,
  column,
  ghostButton,
  label,
  largeButton,
  link,
  liveButton,
  primaryButton,
  secondaryButton,
  sectionHeading,
  tileNight,
} from "../ui";

const STEPS = [
  {
    title: "Upload your model or code",
    text: "From your laptop: a folder or a file. It carries on where it stopped if your connection drops, and stays in My files so it goes up once.",
    surface: "bg-ember",
  },
  {
    title: "Request a GPU",
    text: "We find the best free GPU that fits, the way a ride app finds a car. You see the price range before you ask.",
    surface: "bg-ion",
  },
  {
    title: "Download your results",
    text: "Watch it run, then download what it wrote. You pay for the minutes it ran.",
    surface: "bg-volt",
  },
] as const;

const PROMISES = [
  {
    title: "Pay only for minutes used",
    text: "You're charged for the minutes your session ran, by the minute, in rupees. If it never runs, nothing is charged.",
  },
  {
    title: "A hold, not a charge",
    text: "When you request a GPU we set aside up to 30 minutes of its price. It's released as your session settles, and you set a spending cap it never passes.",
  },
  {
    title: "Refunds for lost time",
    text: "If a host's machine drops out, you aren't charged for work that was never saved, and anything already charged for it is given back.",
  },
] as const;

const SEALED = [
  {
    title: "Encrypted scratch disk",
    text: "Every job gets a fresh disk with a key that exists only while it runs.",
  },
  {
    title: "Home network blocked",
    text: "Jobs reach the internet, never the host's router, printer or files.",
  },
  {
    title: "Fair billing",
    text: "Both sides count the minutes. Lost time is given back automatically.",
  },
] as const;

const QUESTIONS = [
  {
    question: "Do you give me a model?",
    answer:
      "No. You bring your own model, data and code. We provide the GPU and the sealed space it runs in.",
  },
  {
    question: "What if the laptop's owner comes back?",
    answer: "Your job saves a checkpoint and moves to another laptop. You aren't charged for lost time.",
  },
  {
    question: "How do hosts get paid?",
    answer: "Weekly, to the UPI ID they verified, after a short window for renters to report a problem.",
  },
] as const;

/** The hero card's loss curve, as a share of its height; the last bar is the one still to come. */
const CURVE = [96, 82, 70, 61, 55, 50, 46, 43, 41, 39, 38, 37, 36] as const;

/** The marquee's words, crossed twice over so it can start again where it began. */
const MARQUEE = "Fine-tune · Run your code · Pay by the minute · Earn while you sleep · ";

/** The service tiles' colours, in turn: ember, ion, then the always-dark panel. */
const SERVICE_SURFACES = [
  "bg-ember text-ink",
  "bg-ion text-ink",
  "scope-night bg-night text-on-night",
] as const;

/**
 * The landing page (ADR 0029, ADR 0043): Night Shift's home. What this is, with a preview of a live session beside it;
 * the marquee; three steps; what you can run; the promises and the hosts' pitch; the sealed band; the GPUs with their
 * prices (live, after the page has painted: src/site/islands/pricing.ts, and "typical" until then); the questions; and
 * one more way in.
 */
export function Landing({ context }: { context: SiteContext }) {
  return (
    <>
      <section
        className={`${column} grid items-center gap-14 pt-6 pb-16 md:pt-12 md:pb-24 xl:grid-cols-2`}
        aria-labelledby="what"
      >
        <div className="flex flex-col gap-7">
          <span
            className={`${label} self-start rounded-full bg-raised px-3.5 py-2 text-link`}
            data-testid="kicker"
          >
            India&apos;s peer-to-peer GPU cloud
          </span>
          <h1 id="what" className="font-display text-display-hero uppercase">
            Rent a gaming GPU by the minute, in India.
          </h1>
          <p className="max-w-lede text-lede text-text-2">
            Fine-tune a model or run your code on an RTX GPU that someone nearby isn&apos;t using, and pay in
            rupees for the minutes it ran. We provide the GPU. You bring your own model, data and code.
          </p>
          <div className="flex flex-wrap gap-3">
            <a href={context.links.getStarted} className={`${primaryButton} ${largeButton}`}>
              Get started
            </a>
            <a href={PRICING} className={`${ghostButton} ${largeButton}`}>
              See the prices
            </a>
          </div>
          <p className="text-small text-text-3">{brand.name} is invite-only while it&apos;s in its pilot.</p>
        </div>

        <div
          role="img"
          aria-label="An example of a live session: a fine-tune running on a laptop RTX GPU, with its time, cost so far and checkpoints"
          className={`${tileNight} flex flex-col gap-4.5 shadow-glow`}
        >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="flex items-center gap-2.5 text-small font-bold text-volt uppercase">
              <span className="size-2.5 rounded-full bg-volt" />
              Running · epoch 4 of 5
            </span>
            <span className="text-small text-text-3">RTX 4080 Laptop · 12 GB</span>
          </div>
          <span className="font-title text-display-feature">Fine-tune your model</span>
          <div className="grid h-hero-chart grid-cols-14 items-end gap-1.5 rounded-inner bg-canvas p-4.5">
            {CURVE.map((height, index) => (
              <span
                key={index}
                className={`rounded-icon ${index === CURVE.length - 1 ? "bg-volt" : "bg-ion-deep"}`}
                style={{ height: `${height}%` }}
              />
            ))}
            <span className="rounded-icon bg-line" style={{ height: "8%" }} />
          </div>
          <div className="grid grid-cols-3 gap-3">
            {(
              [
                ["Time", "1:24", ""],
                ["Spent", "₹0.24", "text-volt"],
                ["Checkpoints", "16", ""],
              ] as const
            ).map(([name, value, tone]) => (
              <div key={name} className="rounded-inner bg-panel-2 p-4">
                <span className="block text-caption text-text-3">{name}</span>
                <span className={`tabular text-figure font-bold ${tone}`}>{value}</span>
              </div>
            ))}
          </div>
          <p className="text-caption text-text-3">An example, not a live session.</p>
        </div>
      </section>

      <div aria-hidden="true" className="overflow-hidden bg-ion-deep py-5 text-on-ion-deep">
        <div className="flex w-max animate-marquee font-display text-display-section whitespace-nowrap uppercase">
          {[0, 1].map((half) => (
            <span key={half} className="shrink-0 pr-4">
              {MARQUEE.repeat(2)}
            </span>
          ))}
        </div>
      </div>

      <section className={`${column} flex flex-col gap-10 pt-20 pb-6 md:pt-28`} aria-labelledby="steps">
        <h2 id="steps" className={`${bandTitle} max-w-form`}>
          How it works
        </h2>
        <ol className="grid gap-5 md:grid-cols-3">
          {STEPS.map((step, index) => (
            <li
              key={step.title}
              className={`${step.surface} flex min-h-step flex-col justify-between gap-8 rounded-feature p-8 text-ink`}
            >
              <p className="font-display text-display-number">
                <span className="sr-only">Step </span>
                {index + 1}
              </p>
              <div>
                <h3 className="text-figure font-bold">{step.title}</h3>
                <p className="mt-2 text-body">{step.text}</p>
              </div>
            </li>
          ))}
        </ol>
      </section>

      <section className={`${column} ${band}`} aria-labelledby="run">
        <h2 id="run" className={`${bandTitle} max-w-form`}>
          What you can run
        </h2>
        <ul className="mt-10 grid gap-5 md:grid-cols-3">
          {context.services.map((service, index) => (
            <li
              key={service.id}
              className={`${SERVICE_SURFACES[index % SERVICE_SURFACES.length]} flex min-h-step flex-col justify-between gap-6 rounded-feature p-8`}
            >
              <h3 className="font-title text-display-card">{service.name}</h3>
              <div>
                <p className="text-body">{service.tagline}</p>
                {service.youGet && <p className="mt-3 text-small">You get: {service.youGet}</p>}
              </div>
            </li>
          ))}
        </ul>
        <p className="mt-6 text-small text-text-3">
          Every service takes your model, data or code as its input; none of them supplies a model.{" "}
          <a href="/docs/services/" className={link}>
            What each one takes
          </a>
        </p>
      </section>

      <section className={`${column} grid gap-5 py-6 xl:grid-cols-2`}>
        <div
          className="flex flex-col gap-5 rounded-hero border border-edge bg-panel p-8 text-text md:p-10"
          aria-labelledby="promises"
        >
          <span className={`${label} text-brand`}>For builders</span>
          <h2 id="promises" className="font-title text-display-panel">
            Your job, on someone&apos;s idle RTX.
          </h2>
          <ul className="flex flex-col gap-4">
            {PROMISES.map((promise) => (
              <li key={promise.title}>
                <h3 className="text-option font-bold">{promise.title}</h3>
                <p className="mt-1 text-body text-text-2">{promise.text}</p>
              </li>
            ))}
          </ul>
          <a href={context.links.getStarted} className={`${secondaryButton} mt-auto self-start`}>
            Get started
          </a>
        </div>
        <div
          className="flex flex-col gap-5 rounded-hero bg-volt p-8 text-ink md:p-10"
          aria-labelledby="hosts"
        >
          <span className={label}>For gamers</span>
          <h2 id="hosts" className="font-title text-display-panel">
            Have an RTX laptop or desktop?
          </h2>
          <p className="max-w-prose text-option">
            Rent out the hours you aren&apos;t using it and keep {100 - COMMISSION_PERCENT}% of what renters
            pay.
          </p>
          <a
            href={EARN}
            className="mt-auto inline-flex min-h-touch items-center justify-center self-start rounded-control bg-ink px-5 text-small font-bold text-on-night hover:bg-night"
          >
            Earn with your GPU
          </a>
        </div>
      </section>

      <section className={`${column} ${band}`} aria-labelledby="sealed">
        <div className="scope-night flex flex-col gap-9 rounded-hero bg-black px-6 py-12 text-on-night md:px-11 md:py-14">
          <h2 id="sealed" className={`${sectionHeading} max-w-form text-on-night`}>
            Sealed, on both sides.
          </h2>
          <ul className="grid gap-7 md:grid-cols-3">
            {SEALED.map((item) => (
              <li key={item.title} className="flex flex-col gap-2.5">
                <h3 className="text-section">{item.title}</h3>
                <p className="text-body text-on-night-2">{item.text}</p>
              </li>
            ))}
          </ul>
          <p className="text-small text-on-night-3">
            A host owns the laptop. Don&apos;t send truly secret data to community hosts.
          </p>
        </div>
      </section>

      <section
        className={`${column} flex flex-col gap-7 py-10 md:py-16`}
        aria-labelledby="pick"
        data-live-prices=""
        data-api={context.apiUrl ?? ""}
      >
        <h2 id="pick" className={sectionHeading}>
          Pick a GPU
        </h2>
        <div
          role="region"
          aria-label="GPU sizes and prices"
          // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- a wide table scrolls; the keyboard must reach it
          tabIndex={0}
          className="overflow-x-auto rounded-panel border border-edge bg-panel"
        >
          <table className="w-full min-w-form border-collapse text-body">
            <thead>
              <tr className="text-left text-caption tracking-wider text-text-3 uppercase">
                <th scope="col" className="px-7 py-5 font-semibold">
                  GPU memory
                </th>
                <th scope="col" className="px-7 py-5 font-semibold">
                  Typical cards
                </th>
                <th scope="col" className="px-7 py-5 font-semibold">
                  Fine-tuning, typical
                </th>
                <th scope="col" className="px-7 py-5 font-semibold">
                  Price per hour
                </th>
              </tr>
            </thead>
            <tbody>
              {GPU_CLASSES.map((gpu) => (
                <tr
                  key={gpu.gb}
                  className="border-t border-line"
                  data-class-row={gpu.gb}
                  data-state="loading"
                >
                  <th scope="row" className="px-7 py-5 text-left font-bold">
                    {gpu.gb} GB
                  </th>
                  <td className="px-7 py-5 text-text-2">{gpu.cards.slice(0, 3).join(", ")}</td>
                  <td className="px-7 py-5 text-text-2">{gpu.qlora}</td>
                  <td className="px-7 py-5 align-top">
                    <span
                      data-role="skeleton"
                      className="skeleton mt-1 block h-6 w-24 rounded-control"
                      aria-hidden="true"
                    />
                    <span data-role="price" className="tabular block h-6 font-bold text-link" hidden></span>
                    <span data-role="kind" className="block h-4.5 text-caption text-text-3"></span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div
          className="flex min-h-14 items-start justify-between gap-x-3 text-caption text-text-3"
          aria-live="polite"
        >
          <p data-role="status" className="flex-1">
            Looking up today&apos;s prices…
          </p>
          <button
            type="button"
            data-role="retry"
            className="min-h-touch shrink-0 px-2 text-small font-semibold text-link underline underline-offset-4"
            hidden
          >
            Try again
          </button>
        </div>
        <noscript>
          <p className="text-small text-text-2">Today&apos;s prices need JavaScript on this page.</p>
        </noscript>
      </section>

      <section className={`${column} flex flex-col gap-3.5 pb-16`} aria-labelledby="questions">
        <h2 id="questions" className={`${sectionHeading} mb-3.5`}>
          Questions
        </h2>
        {QUESTIONS.map((item) => (
          <details key={item.question} className="rounded-inner border border-edge bg-panel px-7 py-6">
            <summary className="cursor-pointer text-option font-semibold">{item.question}</summary>
            <p className="mt-3.5 text-body text-text-2">{item.answer}</p>
          </details>
        ))}
      </section>

      <section className={`${column} pb-24`}>
        <div className="flex flex-wrap items-center justify-between gap-7 rounded-hero bg-ion-deep px-6 py-12 text-on-ion-deep md:px-11 md:py-16">
          <h2 className="font-display text-display-section max-w-form uppercase">
            Your first GPU is a minute away.
          </h2>
          <a href={context.links.getStarted} className={`${liveButton} ${largeButton}`}>
            Get started
          </a>
        </div>
      </section>
    </>
  );
}
