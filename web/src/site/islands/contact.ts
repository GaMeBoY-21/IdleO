/**
 * The Contact page's form (ADR 0037): fills the topics from GET /v1/public/contact, then sends the message to POST
 * /v1/support/tickets with no sign-in, with how long the page has been open and the hidden field a machine fills, and
 * says what happened: the message's number, or the server's reason in plain words. The grievance officer's block is
 * src/site/islands/grievance.ts.
 */
import { brand } from "@/brand/brand";
import { MAX_MESSAGE } from "@/lib/support";
import { contact } from "./grievance";

const form = document.querySelector<HTMLFormElement>("[data-contact]");

if (form) {
  const opened = performance.now();
  const api = form.dataset.api ?? "";
  const topic = form.querySelector<HTMLSelectElement>('[name="topic"]')!;
  const outcome = form.querySelector<HTMLElement>('[data-role="outcome"]')!;
  const button = form.querySelector<HTMLButtonElement>('button[type="submit"]')!;

  void contact(api).then((details) => {
    for (const option of details?.topics ?? []) {
      topic.add(new Option(option.label, option.code));
    }
  });

  const say = (text: string, failed: boolean) => {
    outcome.textContent = text;
    outcome.classList.toggle("text-danger", failed);
  };

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const fields = new FormData(form);
    const value = (name: string) => {
      const entry = fields.get(name);
      return typeof entry === "string" ? entry : "";
    };
    const message = value("message").trim();
    const replyTo = value("replyTo").trim();
    if (topic.value === "" || message === "" || replyTo === "" || message.length > MAX_MESSAGE) {
      say("Choose what it's about, write your message, and give your email.", true);
      return;
    }
    button.disabled = true;
    void fetch(`${api}/v1/support/tickets`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        topic: topic.value,
        message,
        replyTo,
        website: value("website"),
        elapsedMs: Math.round(performance.now() - opened),
      }),
    })
      .then(async (response) => {
        const body = (await response.json().catch(() => ({}))) as { number?: number; type?: string };
        if (response.ok && body.number) {
          form.reset();
          say(`Sent as #${body.number}. We've emailed you to say we have it.`, false);
        } else if (body.type === "/problems/rate-limited") {
          say("That's a lot of messages at once. Wait a while, then send it again.", true);
        } else if (body.type === "/problems/message-looks-automated") {
          say("That was sent faster than a person writes. Wait a moment, then send it again.", true);
        } else {
          say("We couldn't send that. Check your email address, then try again.", true);
        }
      })
      .catch(() => say(`We couldn't reach ${brand.name}. Try again in a moment.`, true))
      .finally(() => {
        button.disabled = false;
      });
  });
}
