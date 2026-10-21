/**
 * What a ticket's status says (ADR 0037), in the person's words, and the limits the server applies to a message, so the
 * form says them before sending: 5,000 characters, and a few seconds on the page (a machine sends at once).
 */
export const STATUS_WORDS: Record<string, string> = {
  open: "Open: we'll answer soon",
  waiting: "We've answered: waiting for you",
  resolved: "Resolved",
};

export const MAX_MESSAGE = 5000;

/** The server refuses a message sent within this of the page opening (Tickets.TOO_FAST). */
export const MIN_ELAPSED_MS = 3000;

/** "#1042". */
export const ticketNumber = (number: number) => `#${number}`;
