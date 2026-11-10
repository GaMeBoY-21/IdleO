import { COMMISSION_PERCENT } from "@/lib/hosting";

/**
 * What a host asks first, answered plainly (ADR 0058): the earn page's questions and the host site's Help, one list.
 */
export const HOST_QUESTIONS = [
  {
    question: "What can a renter's job see on my laptop?",
    answer:
      "Its own scratch space, and nothing else: none of your folders, no administrator rights, no way onto your home network. Hosting opens once our safety tests on real laptops pass.",
  },
  {
    question: "What will I earn?",
    answer: `You keep ${100 - COMMISSION_PERCENT}% of what renters pay for the minutes your laptop ran their jobs. How much it's rented depends on demand, which may be low at first, so any estimate is arithmetic on what you choose, never a promise.`,
  },
  {
    question: "Can I still play on it?",
    answer:
      "Yes. The app will pause the moment you use the laptop or start a game, and a job running then stops after saving a checkpoint.",
  },
  {
    question: "What about tax?",
    answer:
      "Payouts, once they start with the paid pilot, carry the tax deducted at source the law asks for, shown on every payout; the year's statement downloads from the host site.",
  },
] as const;
