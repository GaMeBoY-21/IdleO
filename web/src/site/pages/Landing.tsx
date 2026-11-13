import { brand } from "@/brand/brand";
import { COMMISSION_PERCENT } from "@/lib/hosting";
import type { SiteContext } from "../context";
import { GPU_CLASSES } from "../gpus";
import { EARN, RENT } from "../paths";
import {
  band,
  bandTitle,
  column,
  label,
  largeButton,
  link,
  primaryButton,
  secondaryButton,
  frame,
  exampleTag,
  sectionHeading,
  tile,
  tileRaised,
} from "../ui";

const STEPS = [
  {
    title: "Upload your model or code",
    text: "From your laptop: a folder or a file. It carries on where it stopped if your connection drops, and stays in My files so it goes up once.",
  },
  {
    title: "Request a GPU",
    text: "We find the best free GPU that fits, the way a ride app finds a car. You see the price range before you ask.",
  },
  {
    title: "Download your results",
    text: "Watch it run, then download what it wrote. You pay for the minutes it ran.",
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

/**
 * The landing page (ADR 0029, ADR 0060): dark first. What this is, with a framed example of a live session beside it;
 * three steps; what you can run; the promises and the hosts' pitch; the sealed band; the GPUs with their
 * prices (live, after the page has painted: src/site/islands/pricing.ts, and "typical" until then); the questions; and
 * one more way in.
 */
export function Landing({ context }: { context: SiteContext }) {
  return (
    <>
      <section
        className={`${column} grid grid-cols-1 items-center gap-14 pt-6 pb-16 md:pt-12 md:pb-24 xl:grid-cols-2`}
        aria-labelledby="what"
      >
        <div className="flex flex-col gap-6">
          <span
            className={`${label} self-start rounded-full border border-hairline bg-surface-1 px-4 py-1`}
            data-testid="kicker"
          >
            India&apos;s peer-to-peer GPU cloud
          </span>
          <h1 id="what" className="text-display">
            Rent a gaming GPU by the minute, in India.
          </h1>
          <p className="max-w-lede text-lede text-ink-subtle">
            Fine-tune a model or run your code on an RTX GPU that someone nearby isn&apos;t using, and pay in
            rupees for the minutes it ran. We provide the GPU. You bring your own model, data and code.
          </p>
          <div className="flex flex-wrap gap-2">
            <a href={RENT} className={`${primaryButton} ${largeButton}`}>
              Rent a GPU
            </a>
            <a href={EARN} className={`${secondaryButton} ${largeButton}`}>
              Earn with your laptop
            </a>
          </div>
          <p className="text-small text-ink-subtle">
            {brand.name} is invite-only while it&apos;s in its pilot.
          </p>
        </div>

        <div
          role="img"
          aria-label="An example of a live session: a fine-tune running on a laptop RTX GPU, with its time, cost so far and checkpoints"
          className={`${frame} flex flex-col gap-4`}
        >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="flex items-center gap-2 text-caption text-ink">
              <span className="size-1.5 rounded-full bg-live" />
              Running · epoch 4 of 5
            </span>
            <span className={exampleTag}>Example</span>
          </div>
          <span className="text-section">Fine-tune your model</span>
          <span className="-mt-2 text-small text-ink-subtle">RTX 4080 Laptop · 12 GB</span>
          <div className="grid h-hero-chart grid-cols-14 items-end gap-1 rounded-card border border-hairline bg-canvas p-4">
            {CURVE.map((height, index) => (
              <span
                key={index}
                className={`rounded-tag ${index === CURVE.length - 1 ? "bg-live" : "bg-hairline-tertiary"}`}
                style={{ height: `${height}%` }}
              />
            ))}
            <span className="rounded-tag bg-hairline" style={{ height: "8%" }} />
          </div>
          <div className="grid grid-cols-3 gap-2">
            {(
              [
                ["Time", "1:24"],
                ["Spent", "₹0.24"],
                ["Checkpoints", "16"],
              ] as const
            ).map(([name, value]) => (
              <div key={name} className="rounded-card border border-hairline bg-surface-2 p-4">
                <span className="block text-caption text-ink-subtle">{name}</span>
                <span className="tabular text-figure">{value}</span>
              </div>
            ))}
          </div>
          <p className="text-caption text-ink-tertiary">An example, not a live session.</p>
        </div>
      </section>

      <HowItWorks />

      <section className={`${column} ${band}`} aria-labelledby="run">
        <h2 id="run" className={`${bandTitle} max-w-form`}>
          What you can run
        </h2>
        <ul className="mt-10 grid grid-cols-1 gap-4 md:grid-cols-3">
          {context.services.map((service) => (
            <li key={service.id} className={`${tile} flex min-h-step flex-col justify-between gap-6`}>
              <h3 className="text-section">{service.name}</h3>
              <div>
                <p className="text-body">{service.tagline}</p>
                {service.youGet && <p className="mt-4 text-small">You get: {service.youGet}</p>}
              </div>
            </li>
          ))}
        </ul>
        <p className="mt-6 text-small text-ink-tertiary">
          Every service takes your model, data or code as its input; none of them supplies a model.{" "}
          <a href="/docs/services/" className={link}>
            What each one takes
          </a>
        </p>
      </section>

      <section className={`${column} grid grid-cols-1 gap-4 py-6 xl:grid-cols-2`}>
        <div className={`${tile} flex flex-col gap-4 md:p-8`} aria-labelledby="promises">
          <span className={label}>For builders</span>
          <h2 id="promises" className="text-hero">
            Your job, on someone&apos;s idle RTX.
          </h2>
          <ul className="flex flex-col gap-4">
            {PROMISES.map((promise) => (
              <li key={promise.title}>
                <h3 className="text-card">{promise.title}</h3>
                <p className="mt-1 text-body text-ink-subtle">{promise.text}</p>
              </li>
            ))}
          </ul>
          <a href={context.links.getStarted} className={`${secondaryButton} mt-auto self-start`}>
            Get started
          </a>
        </div>
        <div className={`${tileRaised} flex flex-col gap-4`} aria-labelledby="hosts">
          <span className={label}>For gamers</span>
          <h2 id="hosts" className="text-hero">
            Have an RTX laptop or desktop?
          </h2>
          <p className="max-w-prose text-card text-ink-muted">
            Rent out the hours you aren&apos;t using it and keep {100 - COMMISSION_PERCENT}% of what renters
            pay.
          </p>
          <a href={EARN} className={`${secondaryButton} mt-auto self-start`}>
            Earn with your GPU
          </a>
        </div>
      </section>

      <Sealed />

      <section
        className={`${column} flex flex-col gap-6 py-10 md:py-16`}
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
          className="overflow-x-auto rounded-card border border-hairline bg-surface-1"
        >
          <table className="w-full min-w-form border-collapse text-body">
            <thead>
              <tr className="text-left text-caption text-ink-subtle">
                <th scope="col" className="px-6 py-4 font-medium">
                  GPU memory
                </th>
                <th scope="col" className="px-6 py-4 font-medium">
                  Typical cards
                </th>
                <th scope="col" className="px-6 py-4 font-medium">
                  Fine-tuning, typical
                </th>
                <th scope="col" className="px-6 py-4 font-medium">
                  Price per hour
                </th>
              </tr>
            </thead>
            <tbody>
              {GPU_CLASSES.map((gpu) => (
                <tr
                  key={gpu.gb}
                  className="border-t border-hairline"
                  data-class-row={gpu.gb}
                  data-state="loading"
                >
                  <th scope="row" className="px-6 py-4 text-left font-medium">
                    {gpu.gb} GB
                  </th>
                  <td className="px-6 py-4 text-ink-subtle">{gpu.cards.slice(0, 3).join(", ")}</td>
                  <td className="px-6 py-4 text-ink-subtle">{gpu.qlora}</td>
                  <td className="px-6 py-4 align-top">
                    <span
                      data-role="skeleton"
                      className="skeleton mt-1 block h-6 w-24 rounded-control"
                      aria-hidden="true"
                    />
                    <span data-role="price" className="tabular block h-6 text-ink" hidden></span>
                    <span data-role="kind" className="block h-4.5 text-caption text-ink-tertiary"></span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div
          className="flex min-h-touch items-start justify-between gap-x-2 text-caption text-ink-tertiary"
          aria-live="polite"
        >
          <p data-role="status" className="flex-1">
            Looking up today&apos;s prices…
          </p>
          <button
            type="button"
            data-role="retry"
            className="min-h-touch shrink-0 px-2 text-button text-ink underline underline-offset-4"
            hidden
          >
            Try again
          </button>
        </div>
        <noscript>
          <p className="text-small text-ink-subtle">Today&apos;s prices need JavaScript on this page.</p>
        </noscript>
      </section>

      <Questions />

      <section className={`${column} pb-24`}>
        <div className={`${tileRaised} flex flex-wrap items-center justify-between gap-6 md:px-12 md:py-16`}>
          <h2 className="max-w-form text-hero">Your first GPU is a minute away.</h2>
          <a href={context.links.getStarted} className={`${primaryButton} ${largeButton}`}>
            Get started
          </a>
        </div>
      </section>
    </>
  );
}

/** How renting works, in three steps: the landing page's and the rent page's (ADR 0058). */
export function HowItWorks() {
  return (
    <section className={`${column} flex flex-col gap-10 pt-16 pb-6 md:pt-24`} aria-labelledby="steps">
      <h2 id="steps" className={`${bandTitle} max-w-form`}>
        How it works
      </h2>
      <ol className="grid grid-cols-1 gap-4 md:grid-cols-3">
        {STEPS.map((step, index) => (
          <li key={step.title} className={`${tile} flex min-h-step flex-col justify-between gap-8`}>
            <p className="text-hero text-ink-tertiary">
              <span className="sr-only">Step </span>
              {index + 1}
            </p>
            <div>
              <h3 className="text-figure">{step.title}</h3>
              <p className="mt-2 text-body text-ink-subtle">{step.text}</p>
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}

/** The sandbox and what it keeps apart, on both sides, plainly. */
export function Sealed() {
  return (
    <section className={`${column} ${band}`} aria-labelledby="sealed">
      <div className={`${tileRaised} flex flex-col gap-10 md:px-12 md:py-14`}>
        <h2 id="sealed" className={`${sectionHeading} max-w-form`}>
          Sealed, on both sides.
        </h2>
        <ul className="grid grid-cols-1 gap-6 md:grid-cols-3">
          {SEALED.map((item) => (
            <li key={item.title} className="flex flex-col gap-2">
              <h3 className="text-section">{item.title}</h3>
              <p className="text-body text-ink-subtle">{item.text}</p>
            </li>
          ))}
        </ul>
        <p className="text-small text-ink-subtle">
          A host owns the laptop. Don&apos;t send truly secret data to community hosts.
        </p>
      </div>
    </section>
  );
}

/** The questions a renter asks first. */
export function Questions() {
  return (
    <section className={`${column} flex flex-col gap-4 pb-16`} aria-labelledby="questions">
      <h2 id="questions" className={`${sectionHeading} mb-4`}>
        Questions
      </h2>
      {QUESTIONS.map((item) => (
        <details key={item.question} className="rounded-card border border-hairline bg-surface-1 px-6 py-4">
          <summary className="cursor-pointer text-card">{item.question}</summary>
          <p className="mt-4 text-body text-ink-subtle">{item.answer}</p>
        </details>
      ))}
    </section>
  );
}
