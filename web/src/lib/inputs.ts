import type { components } from "@/api/schema.gen";

type Session = components["schemas"]["SessionResponse"];
type SessionInput = components["schemas"]["SessionInputView"];

/** A session's first input, with its name; null when it reads none. */
export function sessionInput(session: Session): (SessionInput & { name: string }) | null {
  const first = Object.entries(session.inputs)[0];
  return first ? { name: first[0], ...first[1] } : null;
}
