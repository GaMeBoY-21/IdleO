import { useHosts } from "@/api/hosting";
import type { HelpSubjects } from "../help-subjects";

/** On the host site a ticket can be about one of the person's laptops (ADR 0058). */
export function useHelpSubjects(enabled: boolean): HelpSubjects {
  const hosts = useHosts(enabled);
  return {
    sessions: [],
    machines: (hosts.data?.hosts ?? []).map((host) => ({ id: host.id, label: host.name })),
  };
}
