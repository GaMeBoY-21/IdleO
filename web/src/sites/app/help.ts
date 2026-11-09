import { useSessions, useTemplateName } from "@/api/queries";
import { formatDay } from "@/lib/time";
import type { HelpSubjects } from "../help-subjects";

/** On the renter app a ticket can be about one of the person's sessions (ADR 0058). */
export function useHelpSubjects(enabled: boolean): HelpSubjects {
  const sessions = useSessions(enabled);
  const templateName = useTemplateName();
  return {
    sessions: (sessions.data?.sessions ?? []).map((session) => ({
      id: session.id,
      label: `${templateName(session.templateId)} · ${formatDay(session.createdAt)}`,
    })),
    machines: [],
  };
}
