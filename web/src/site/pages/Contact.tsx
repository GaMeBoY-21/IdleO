import { MAX_MESSAGE } from "@/lib/support";
import { band, column, heading, muted, primaryButton, sectionTitle, tile } from "../ui";

const field = "min-h-14 rounded-control bg-ground px-4 text-body text-text";
const label = "text-small font-medium";

/**
 * Contact (ADR 0037): a message to us, from anyone, signed in or not, answered by email; and the grievance officer the
 * law asks for, read from the control plane's configuration. The form is plain HTML that works with the island
 * (src/site/islands/contact.ts), which sends it and says what happened. A hidden field and the time since the page
 * opened tell a person from a machine; no captcha service sees anyone.
 */
export function Contact({ apiUrl }: { apiUrl: string | null }) {
  return (
    <section className={`${column} ${band} flex flex-col gap-6`}>
      <h1 className={heading}>Contact</h1>
      <p className={`${muted} max-w-prose`}>
        Write to us about anything: a session, your money, hosting, your account, your data, or a problem with
        someone. A person reads every message and answers by email.
      </p>
      <div className="grid gap-4 xl:grid-cols-5">
        <form
          className={`${tile} flex flex-col gap-3 xl:col-span-3`}
          data-contact=""
          data-api={apiUrl ?? ""}
          noValidate
        >
          <h2 className={sectionTitle}>Send us a message</h2>
          <div className="flex flex-col gap-2">
            <label htmlFor="contact-topic" className={label}>
              What it&apos;s about
            </label>
            <select id="contact-topic" name="topic" className={field} required defaultValue="">
              <option value="">Choose…</option>
            </select>
          </div>
          <div className="flex flex-col gap-2">
            <label htmlFor="contact-message" className={label}>
              Your message
            </label>
            <textarea
              id="contact-message"
              name="message"
              rows={6}
              maxLength={MAX_MESSAGE}
              required
              className={`${field} py-3`}
            />
          </div>
          <div className="flex flex-col gap-2">
            <label htmlFor="contact-reply" className={label}>
              Your email, for our answer
            </label>
            <input
              id="contact-reply"
              name="replyTo"
              type="email"
              autoComplete="email"
              required
              className={field}
            />
          </div>
          {/* People never see this; a machine that fills every field does. */}
          <div aria-hidden="true" className="hidden">
            <label htmlFor="contact-website">Website</label>
            <input id="contact-website" name="website" type="text" tabIndex={-1} autoComplete="off" />
          </div>
          <p role="status" data-role="outcome" className="min-h-6 text-small" />
          <button type="submit" className={`${primaryButton} self-start`}>
            Send
          </button>
        </form>
        <Grievance className="xl:col-span-2" apiUrl={apiUrl} />
      </div>
    </section>
  );
}

/**
 * The grievance officer (ADR 0037): name, email and postal address from the control plane's configuration, filled in
 * by src/site/islands/grievance.ts. Until they arrive the block holds their room, so nothing moves.
 */
export function Grievance({ apiUrl, className = "" }: { apiUrl: string | null; className?: string }) {
  return (
    <aside className={`${tile} flex flex-col gap-2 ${className}`} data-grievance="" data-api={apiUrl ?? ""}>
      <h2 className={sectionTitle}>Grievance officer</h2>
      <p className={muted}>For a complaint about how we handle your data, write to:</p>
      <p className="min-h-6 text-option font-semibold" data-role="name">
        &nbsp;
      </p>
      <p className="min-h-6 text-body" data-role="email">
        &nbsp;
      </p>
      <p className="min-h-12 text-body whitespace-pre-line" data-role="address">
        &nbsp;
      </p>
      <p className={`${muted} min-h-6 text-small`} data-role="days">
        &nbsp;
      </p>
    </aside>
  );
}
