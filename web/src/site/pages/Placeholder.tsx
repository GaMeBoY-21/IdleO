import { band, column, heading, muted } from "../ui";

/**
 * Terms and Privacy stay placeholders (ADR 0029): a lawyer is drafting them (docs/plan/product-surface.md 11.14). Each
 * says so and invents nothing; Privacy also names the grievance officer from configuration (ADR 0037).
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
