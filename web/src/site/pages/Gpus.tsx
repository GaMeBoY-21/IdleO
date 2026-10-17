import type { EntryLinks } from "../paths";
import { GPU_CLASSES } from "../gpus";
import { band, column, heading, largeButton, muted, primaryButton, sectionTitle, tile } from "../ui";

/**
 * What each GPU memory class can run (ADR 0029). Static: nothing here is measured on our hosts, so every line is marked
 * typical, and no price is quoted (those are on the pricing page, from the live range).
 */
export function Gpus({ links }: { links: EntryLinks }) {
  return (
    <>
      <section className={`${column} ${band}`}>
        <h1 className={`${heading} max-w-prose`}>What each GPU can run</h1>
        <p className={`${muted} mt-4 max-w-prose md:text-option`}>
          A GPU's memory decides most of what fits. You ask for a size, and we give you the best free GPU that
          has at least that much. Everything below is typical: what a card of that size usually manages, not a
          guarantee for your model or your settings.
        </p>
      </section>

      <section className={`${column} pb-14 md:pb-20`}>
        <ul className="grid gap-2.5 md:grid-cols-2 md:gap-3.5">
          {GPU_CLASSES.map((gpu) => (
            <li key={gpu.gb} className={tile} aria-labelledby={`gpu-${gpu.gb}`}>
              <h2 id={`gpu-${gpu.gb}`} className={sectionTitle}>
                {gpu.gb} GB GPU memory
              </h2>
              <p className="mt-1 text-small text-text-muted">Typical cards: {gpu.cards.join(", ")}</p>
              <dl className="mt-4 grid gap-3 text-body">
                {(
                  [
                    ["Fine-tuning with QLoRA", gpu.qlora],
                    ["Training batches", gpu.batch],
                    ["Running a model", gpu.inference],
                    ["Also fits", gpu.also],
                  ] as const
                ).map(([name, text]) => (
                  <div key={name}>
                    <dt className="text-small font-semibold text-text">{name}, typical</dt>
                    <dd className="text-text-muted">{text}</dd>
                  </div>
                ))}
              </dl>
            </li>
          ))}
        </ul>
        <p className="mt-6 max-w-prose text-small text-text-muted">
          A laptop GPU runs at lower power than the desktop card with the same name, so it's slower for the
          same memory. Some cards have more or less memory than their name suggests; we count the memory the
          card reports.
        </p>
        <div className="mt-8">
          <a href={links.getStarted} className={`${primaryButton} ${largeButton}`}>
            Get started
          </a>
        </div>
      </section>
    </>
  );
}
