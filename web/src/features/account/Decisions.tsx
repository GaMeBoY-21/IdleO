import { useQuery } from "@tanstack/react-query";
import { Link } from "wouter";
import { SectionHeader } from "@/components/ui/Headers";
import { api, data } from "@/api/client";
import { Tile } from "@/components/ui/Tile";
import { formatDay } from "@/lib/time";

/**
 * Anything decided about this account (ADR 0053), each with the way to read and appeal it. A person without any sees
 * nothing here: it isn't a section to wonder about.
 */
export function Decisions() {
  const decisions = useQuery({
    queryKey: ["me", "enforcements"],
    queryFn: () => data(api.GET("/v1/me/enforcements")),
  });
  const rows = decisions.data?.enforcements ?? [];
  if (rows.length === 0) {
    return null;
  }
  return (
    <Tile as="section" aria-labelledby="decisions-title" className="flex flex-col gap-1">
      <SectionHeader id="decisions-title">Decisions about your account</SectionHeader>
      <ul className="flex flex-col divide-y divide-hairline">
        {rows.map((decision) => (
          <li key={decision.id}>
            <Link
              href={`/account/appeal/?id=${decision.id}`}
              className="flex min-h-touch flex-col justify-center gap-1 py-4"
            >
              <span className="text-card font-semibold">{decision.title}</span>
              <span className="text-small text-ink-subtle">
                {formatDay(decision.createdAt)}
                {decision.appeal ? ", appealed" : ", read it or appeal"}
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </Tile>
  );
}
