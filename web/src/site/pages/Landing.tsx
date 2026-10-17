import { brand } from "@/brand/brand";
import { COMMISSION_PERCENT } from "@/lib/hosting";
import type { SiteContext } from "../context";
import { EARN, PRICING, SIGN_IN } from "../paths";
import {
  column,
  grid,
  largeButton,
  link,
  liveButton,
  secondaryButton,
  sectionTitle,
  tile,
  tileApricot,
  tileDark,
  tileLavender,
  tileLime,
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

/**
 * The landing page (ADR 0029, ADR 0031): a bento of tiles of different sizes. What this is, in the dark tile, with the
 * services beside it (the first lime); three steps; the promises on billing; and one tile for hosts.
 */
export function Landing({ context }: { context: SiteContext }) {
  const [main, ...others] = context.services;
  return (
    <>
      <section className={`${column} pt-3.5 pb-2.5 md:pt-6 md:pb-3.5`} aria-labelledby="what">
        <div className={`${grid} md:grid-cols-4`}>
          <div className={`${tileDark} md:col-span-3 md:row-span-2`}>
            <h1 id="what" className="max-w-prose text-title md:text-question">
              Rent a gaming GPU by the minute, in India.
            </h1>
            <p className="mt-4 max-w-prose text-body text-dark-muted md:text-option">
              Fine-tune a model or run your code on an RTX GPU that someone nearby isn&apos;t using, and pay
              in rupees for the minutes it ran. We provide the GPU. You bring your own model, data and code.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <a href={SIGN_IN} className={`${liveButton} ${largeButton}`}>
                Get started
              </a>
              <a href={PRICING} className={`${secondaryButton} ${largeButton}`}>
                See the prices
              </a>
            </div>
          </div>
          <h2 className="sr-only">What you can run</h2>
          {main && (
            <div className={`${tileLime} md:row-span-1`}>
              <h3 className="text-section">{main.name}</h3>
              <p className="mt-1 text-body text-lime-fg">{main.tagline}</p>
              {main.youGet && <p className="mt-3 text-small text-lime-fg">You get: {main.youGet}</p>}
            </div>
          )}
          {others.map((service) => (
            <div key={service.id} className={tile}>
              <h3 className="text-section">{service.name}</h3>
              <p className="mt-1 text-body text-text-muted">{service.tagline}</p>
              {service.youGet && <p className="mt-3 text-small text-text-muted">You get: {service.youGet}</p>}
            </div>
          ))}
        </div>
        <p className="mt-3.5 text-small text-text-muted">
          Every service takes your model, data or code as its input; none of them supplies a model.{" "}
          <a href="/docs/services/" className={link}>
            What each one takes
          </a>
        </p>
      </section>

      <section className={`${column} py-6 md:py-8`} aria-labelledby="steps">
        <h2 id="steps" className={sectionTitle}>
          How it works
        </h2>
        <ol className={`${grid} mt-3 md:grid-cols-3`}>
          {STEPS.map((step, index) => (
            <li key={step.title} className={tile}>
              <p className="text-small font-semibold text-text-muted">Step {index + 1}</p>
              <h3 className="mt-1 text-option font-semibold">{step.title}</h3>
              <p className="mt-2 text-body text-text-muted">{step.text}</p>
            </li>
          ))}
        </ol>
      </section>

      <section className={`${column} py-6 md:py-8`} aria-labelledby="promises">
        <h2 id="promises" className={sectionTitle}>
          What we promise
        </h2>
        <ul className={`${grid} mt-3 md:grid-cols-3`}>
          {PROMISES.map((promise, index) => (
            <li key={promise.title} className={index === 0 ? tileLavender : tile}>
              <h3 className="text-option font-semibold">{promise.title}</h3>
              <p className={`mt-2 text-body ${index === 0 ? "text-lavender-fg" : "text-text-muted"}`}>
                {promise.text}
              </p>
            </li>
          ))}
        </ul>
      </section>

      <section className={`${column} pt-6 pb-14 md:pt-8 md:pb-20`} aria-labelledby="hosts">
        <div className={tileApricot}>
          <h2 id="hosts" className="text-headline">
            Have an RTX laptop or desktop?
          </h2>
          <p className="mt-2 max-w-prose text-body text-apricot-fg">
            Rent out the hours you aren&apos;t using it and keep {100 - COMMISSION_PERCENT}% of what renters
            pay.{" "}
            <a href={EARN} className="text-on-colour underline underline-offset-4">
              Earn with your GPU
            </a>
          </p>
        </div>
        <p className="mt-6 text-small text-text-muted">
          {brand.name} is invite-only while it&apos;s in its pilot.
        </p>
      </section>
    </>
  );
}
