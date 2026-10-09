import type { components } from "@/api/schema.gen";

type Template = components["schemas"]["TemplateResponse"];
type TemplateInput = components["schemas"]["TemplateInput"];
type Session = components["schemas"]["SessionResponse"];
type SessionInput = components["schemas"]["SessionInputView"];

/**
 * The input the booking form asks for: a template's first (ADR 0022). A template names its inputs; this form asks
 * for one, so it books templates with at most one input, and the service screen built from the catalog asks for each.
 */
export const firstInput = (template: Template): TemplateInput | null => template.inputs[0] ?? null;

/** A session's first input, with its name; null when it reads none. */
export function sessionInput(session: Session): (SessionInput & { name: string }) | null {
  const first = Object.entries(session.inputs)[0];
  return first ? { name: first[0], ...first[1] } : null;
}
