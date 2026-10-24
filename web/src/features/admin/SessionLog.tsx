import { useInfiniteQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import { Empty, PagedView, Section } from "./ui";

/**
 * A session's log, for judging a report or gathering a chargeback's evidence (ADR 0039). It's the renter's own job's
 * output, so it's read only once the admin has said why, which goes into the audit log with the first page.
 */
export function SessionLog({ sessionId, purpose }: { sessionId: string; purpose: string }) {
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState<string | null>(null);
  const log = useInfiniteQuery({
    queryKey: [ADMIN, "session-log", sessionId, reason],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/sessions/{id}/log", {
          params: {
            path: { id: sessionId },
            query: { reason: reason!, ...(pageParam ? { cursor: pageParam } : {}) },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: reason !== null,
  });
  return (
    <Section
      title="The session's log"
      action={
        reason === null ? (
          <Button variant="secondary" onClick={() => setAsking(true)}>
            Read the log
          </Button>
        ) : undefined
      }
    >
      {reason === null ? (
        <Empty title="Not read yet">
          It&apos;s the renter&apos;s job&apos;s own output: say why before you read it.
        </Empty>
      ) : (
        <PagedView
          query={log}
          what="the session's log"
          items={(page) => page.chunks}
          empty={<Empty title="Nothing in it">The job wrote nothing to its log.</Empty>}
        >
          {(chunks) => (
            <pre className="max-h-96 overflow-auto rounded-control bg-ground p-4 text-caption whitespace-pre-wrap">
              {chunks.map((chunk) => chunk.text).join("")}
            </pre>
          )}
        </PagedView>
      )}
      <ActionSheet
        open={asking}
        onOpenChange={setAsking}
        title="Read the session's log?"
        change={`The log is what the renter's job printed. Reading it is recorded in the audit log with your reason (${purpose}).`}
        confirm="Read it"
        onConfirm={(why) => {
          setReason(why);
          return Promise.resolve();
        }}
      />
    </Section>
  );
}
