import { NOW, me } from "./fixtures";

/**
 * Invented data for Help, Contact and the admin's Tickets (ADR 0037): Asha's two messages to support and the server's
 * contact details, as the API answers them.
 */
const DAY = 86_400_000;
const ago = (days: number) => new Date(NOW.getTime() - days * DAY).toISOString();
const ahead = (days: number) => new Date(NOW.getTime() + days * DAY).toISOString();

/** The topics and the development placeholder grievance officer, as the control plane's configuration has them. */
export const contact = () => ({
  topics: [
    { code: "session", label: "A session" },
    { code: "money", label: "Money and credit" },
    { code: "hosting", label: "Hosting" },
    { code: "account", label: "My account" },
    { code: "privacy", label: "A privacy request" },
    { code: "report", label: "Report a problem with a host or renter" },
    { code: "grievance", label: "A grievance" },
  ],
  grievanceOfficer: {
    name: "Grievance Officer (development placeholder)",
    email: "grievance@example.invalid",
    postalAddress: "Not a real address: development only",
  },
  responseDays: 30,
});

export const PRIVACY_TICKET = "01a11300-0000-7000-8000-0000000f0001";
export const MONEY_TICKET = "01a11300-0000-7000-8000-0000000f0002";

const ticket = (
  id: string,
  number: number,
  topic: string,
  status: string,
  days: number,
  due: number | null,
) => ({
  id,
  number,
  topic,
  status,
  sessionId: null,
  hostId: null,
  createdAt: ago(days),
  updatedAt: ago(days / 2),
  dueAt: due === null ? null : ahead(due),
});

export const tickets = () => ({
  tickets: [
    ticket(PRIVACY_TICKET, 1042, "privacy", "open", 3, 27),
    ticket(MONEY_TICKET, 1017, "money", "waiting", 12, null),
  ],
});

export const thread = () => ({
  ticket: ticket(MONEY_TICKET, 1017, "money", "waiting", 12, null),
  messages: [
    { author: "person", body: "My credit shows ₹5 less than I expected.", createdAt: ago(12) },
    {
      author: "admin",
      body: "Thanks. That was the hold for your running session; it came back when it ended.",
      createdAt: ago(11),
    },
  ],
});

export const newTicket = () => ticket(MONEY_TICKET, 1043, "money", "open", 0, null);

/** The admin's view: one privacy request overdue, one money question. */
const adminTicket = (id: string, number: number, topic: string, due: number | null, overdue: boolean) => ({
  ...ticket(id, number, topic, "open", overdue ? 35 : 3, due),
  userId: me().id,
  replyTo: me().email,
  resolvedAt: null,
  overdue,
});

export const adminTickets = () => ({
  tickets: [
    adminTicket(PRIVACY_TICKET, 1042, "privacy", -5, true),
    adminTicket(MONEY_TICKET, 1017, "money", null, false),
  ],
  nextCursor: null,
});

export const adminThread = () => ({
  ticket: adminTicket(PRIVACY_TICKET, 1042, "privacy", -5, true),
  messages: [{ author: "person", body: "Please send me everything you hold about me.", createdAt: ago(35) }],
});

export const adminDeletion = () => ({ request: null, blockers: [] });
