import { useState } from "react";
import { ActionSheet } from "./ActionSheet";
import { adminApi, adminData } from "./api";
import { ActionButton } from "./permissions";
import { revealReasonProblem } from "./rules";

/**
 * A person's data, masked, with a way to read it whole (ADR 0051): one field, with a reason, which goes into the access
 * log and the audit log. The whole value stays only in this component, never in the query cache.
 */
export function Reveal({ field, id, masked }: { field: string; id: string; masked: string }) {
  const [asking, setAsking] = useState(false);
  const [whole, setWhole] = useState<string | null>(null);
  return (
    <span className="inline-flex flex-wrap items-center gap-2">
      <span className="tabular break-all">{whole ?? masked}</span>
      {whole === null && (
        <ActionButton permission="users.pii.reveal" variant="quiet" onClick={() => setAsking(true)}>
          Reveal
        </ActionButton>
      )}
      <ActionSheet
        open={asking}
        onOpenChange={setAsking}
        title="Read this whole?"
        change="You'll see this one value in full. Who you are, whose it is and why go into the access log, and the person can see it in their export."
        confirm="Show it"
        reasonRule={revealReasonProblem}
        onConfirm={async (reason) => {
          const shown = await adminData(
            adminApi.POST("/admin/v1/personal-data/reveal", { body: { field, id, reason } }),
          );
          setWhole(shown.value);
        }}
      />
    </span>
  );
}
