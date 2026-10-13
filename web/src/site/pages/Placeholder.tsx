import { band, column, heading, muted } from "../ui";

/**
 * Terms, Privacy and Contact stay placeholders (ADR 0029): a lawyer is drafting the first two (docs/plan/product-surface.md
 * 11.14), and no contact address has been chosen. Each says so and invents nothing.
 */
export function Placeholder({ title, note }: { title: string; note: string }) {
  return (
    <section className={`${column} ${band}`}>
      <h1 className={heading}>{title}</h1>
      <p className={`${muted} mt-4 max-w-prose`}>Being drafted.</p>
      <p className={`${muted} mt-2 max-w-prose`}>{note}</p>
    </section>
  );
}
