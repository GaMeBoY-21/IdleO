import { useLocation } from "wouter";
import { Button } from "@/components/ui/Button";
import { useRequest } from "@/features/book/useRequest";
import { problemMessage } from "@/lib/problems";
import { sampleLabel } from "./sample-template";

/**
 * The sample job in one click: booked as the CUDA test's template has it, its own usual budget the spending cap, and the
 * button says that cap. Then the session screen follows it, as any booking.
 */
export function SampleJob({ templateId, budgetPaise }: { templateId: string; budgetPaise: number }) {
  const [, navigate] = useLocation();
  const request = useRequest({ templateId, maxSpendPaise: budgetPaise }, (session) =>
    navigate(`/session/?id=${session.id}`),
  );
  return (
    <span className="flex flex-col items-end gap-1">
      <Button variant="ghost" busy={request.isPending} onClick={() => request.mutate()}>
        {sampleLabel(budgetPaise)}
      </Button>
      {request.isError && (
        <span role="alert" className="max-w-xs text-right text-caption text-danger">
          {problemMessage(request.error)}
        </span>
      )}
    </span>
  );
}
