import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useSearch } from "wouter";
import { api, data } from "@/api/client";
import { AccountFrame } from "@/components/shell/AccountFrame";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { TextArea } from "@/components/ui/Inputs";
import { Skeleton } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState } from "@/components/ui/States";
import { Tile } from "@/components/ui/Tile";
import { problemMessage } from "@/lib/problems";
import { formatDay } from "@/lib/time";
import { useTitle } from "@/lib/title";

const MAX = 5000;
const MOST_FILES = 3;

const OUTCOME: Record<string, string> = {
  open: "We have your appeal. Someone other than whoever decided this will look at it, and we'll write to you.",
  upheld: "We looked at your appeal again and kept the decision.",
  reversed: "We looked at your appeal again and reversed the decision.",
};

/**
 * A decision about the person's account (ADR 0053), and appealing it, once: a few words, and up to 3 of their own files.
 * It's open to an account that's suspended or banned, since that's who needs it most.
 */
export function Appeal() {
  useTitle("Appeal");
  const id = new URLSearchParams(useSearch()).get("id");
  const client = useQueryClient();
  const decisions = useQuery({
    queryKey: ["me", "enforcements"],
    queryFn: () => data(api.GET("/v1/me/enforcements")),
  });
  const files = useQuery({
    queryKey: ["uploads", "for-appeal"],
    queryFn: () => data(api.GET("/v1/uploads", { params: { query: { limit: 50 } } })),
    retry: false,
  });
  const [body, setBody] = useState("");
  const [flagged, setFlagged] = useState(false);
  const [attachments, setAttachments] = useState<ReadonlySet<string>>(new Set());
  const send = useMutation({
    mutationFn: () =>
      data(
        api.POST("/v1/me/enforcements/{id}/appeal", {
          params: { path: { id: id ?? "" } },
          body: { body: body.trim(), attachments: [...attachments] },
        }),
      ),
    onSuccess: () => client.invalidateQueries({ queryKey: ["me", "enforcements"] }),
  });
  const decision = decisions.data?.enforcements.find((each) => each.id === id);
  const toggle = (file: string) =>
    setAttachments((now) => {
      const next = new Set(now);
      if (next.has(file)) {
        next.delete(file);
      } else if (next.size < MOST_FILES) {
        next.add(file);
      }
      return next;
    });
  const empty = flagged && body.trim() === "";
  return (
    <AccountFrame title="Appeal">
      {decisions.isPending ? (
        <Skeleton className="h-48 w-full" />
      ) : decisions.isError ? (
        <ErrorState
          error={decisions.error}
          what="this decision"
          onRetry={() => void decisions.refetch()}
          retrying={decisions.isFetching}
        />
      ) : !decision ? (
        <EmptyState title="We can't find this decision">
          The link may be old. Anything decided about your account shows here when you open its link.
        </EmptyState>
      ) : (
        <Tile as="section" className="flex flex-col gap-4 md:p-6">
          <h2 className="text-option font-semibold">{decision.title}</h2>
          {decision.reason && <p className="text-body">Why: {decision.reason}</p>}
          <p className="text-small text-text-2">
            Decided {formatDay(decision.createdAt)}, under {decision.clause}.
            {decision.endsAt ? ` It lasts until ${formatDay(decision.endsAt)}.` : ""}
          </p>
          {decision.appeal ? (
            <Banner tone="info">
              {OUTCOME[decision.appeal]}
              {decision.appealOutcome ? ` Why: ${decision.appealOutcome}` : ""}
            </Banner>
          ) : (
            <form
              noValidate
              className="flex flex-col gap-4"
              onSubmit={(event) => {
                event.preventDefault();
                setFlagged(true);
                if (body.trim() !== "") {
                  send.mutate();
                }
              }}
            >
              <TextArea
                label="What should we look at again?"
                maxLength={MAX}
                value={body}
                error={empty ? "Say a few words about why." : undefined}
                onChange={(event) => setBody(event.target.value)}
              />
              {(files.data?.uploads.length ?? 0) > 0 && (
                <fieldset className="flex flex-col gap-1 text-small">
                  <legend className="font-medium">Attach up to {MOST_FILES} of your files</legend>
                  {files.data?.uploads.map((file) => (
                    <label key={file.id} className="flex min-h-touch items-center gap-2">
                      <input
                        type="checkbox"
                        checked={attachments.has(file.id)}
                        disabled={!attachments.has(file.id) && attachments.size >= MOST_FILES}
                        onChange={() => toggle(file.id)}
                      />
                      {file.name}
                    </label>
                  ))}
                </fieldset>
              )}
              {send.isError && <Banner tone="danger">{problemMessage(send.error)}</Banner>}
              <Button type="submit" busy={send.isPending} className="self-start">
                Send my appeal
              </Button>
            </form>
          )}
        </Tile>
      )}
    </AccountFrame>
  );
}
