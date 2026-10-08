import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "wouter";
import { api, data } from "@/api/client";
import { ApiError, type FieldError } from "@/api/errors";
import type { Session, Template } from "@/api/queries";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Card, CardTitle } from "@/components/ui/Card";
import { Money } from "@/components/ui/Money";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { problemMessage } from "@/lib/problems";
import { gigabytes, hoursAndMinutes, type BookValues, type CreateSession } from "./form";
import { formatBytes } from "@/lib/bytes";
import type { ChosenFile } from "./useUpload";

/**
 * Step 3: what's being booked, the quote (`POST /v1/session-quotes`) shown exactly as the API gives it, and the one
 * primary button. The quote runs when review opens and again after any change; while the input is still uploading
 * it waits, because only a ready upload can be quoted. The Idempotency-Key is made once per review and reused if the
 * button is pressed again or the request is retried, so a flaky network never books twice.
 */
export function ReviewStep({
  template,
  values,
  request,
  file,
  laptop,
  onBooked,
  onRefused,
}: {
  template: Template;
  values: BookValues;
  request: CreateSession;
  file: ChosenFile | null;
  laptop: boolean;
  onBooked: (session: Session) => void;
  onRefused: (errors: FieldError[]) => void;
}) {
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const uploading = file !== null && file.progress.phase !== "ready";
  const quote = useQuery({
    queryKey: ["quote", request],
    queryFn: () => data(api.POST("/v1/session-quotes", { body: request })),
    enabled: !uploading,
    staleTime: 0,
    gcTime: 0,
    refetchOnWindowFocus: false,
  });
  const book = useMutation({
    mutationFn: () =>
      data(
        api.POST("/v1/sessions", {
          params: { header: { "Idempotency-Key": idempotencyKey } },
          body: request,
        }),
      ),
    // A network failure or a 5xx is retried with the same key; the server's answer to a 4xx is final.
    retry: (failures, error) => failures < 2 && !(error instanceof ApiError && error.status < 500),
    onSuccess: onBooked,
    onError: (error) => {
      if (error instanceof ApiError && error.type === "/problems/validation-failed") {
        onRefused(error.errors);
      } else if (error instanceof ApiError && error.type === "/problems/insufficient-credit") {
        void quote.refetch();
      }
    },
  });
  const configure = `/book/?template=${encodeURIComponent(template.id)}&step=configure`;
  const q = quote.data;
  const cap = request.maxSpendPaise;

  const summary = (
    <Card aria-labelledby="summary-title">
      <CardTitle
        id="summary-title"
        action={
          <Link
            href={configure}
            className="flex min-h-touch items-center px-2 text-small font-medium text-accent-text"
          >
            Change
          </Link>
        }
      >
        What you&apos;re booking
      </CardTitle>
      <p className="mb-4 text-body font-medium break-words">{template.name}</p>
      <dl className="grid gap-x-6 gap-y-3 text-small md:grid-cols-2">
        {template.params.map((param) => {
          const value = values.params[param.name];
          return (
            <div key={param.name} className="min-w-0">
              <dt className="text-text-muted">{param.label}</dt>
              <dd className="font-medium break-words">
                {typeof value === "boolean"
                  ? value
                    ? "Yes"
                    : "No"
                  : value === "" || value === undefined
                    ? "Default"
                    : value}
              </dd>
            </div>
          );
        })}
        {template.input.accepts !== "none" && (
          <div className="min-w-0">
            <dt className="text-text-muted">{template.input.label ?? "Input"}</dt>
            <dd className="font-medium break-words">
              {request.input?.url ?? (file ? `${file.name}, ${formatBytes(file.size)}` : "None")}
            </dd>
          </div>
        )}
        <div>
          <dt className="text-text-muted">GPU memory</dt>
          <dd className="font-medium">At least {gigabytes(request.vramNeededMb ?? template.minVramMb)}</dd>
        </div>
        <div>
          <dt className="text-text-muted">The most per hour</dt>
          <dd className="font-medium">
            <Money paise={request.maxPricePaisePerMin! * 60} />
          </dd>
        </div>
        <div>
          <dt className="text-text-muted">Spending cap</dt>
          <dd className="font-medium">
            <Money paise={cap} />
          </dd>
        </div>
        <div>
          <dt className="text-text-muted">Longest it may run</dt>
          <dd className="font-medium">
            {hoursAndMinutes(request.maxRuntimeSeconds ?? template.defaultMaxRuntimeSeconds)}
          </dd>
        </div>
      </dl>
    </Card>
  );

  const short = q !== undefined && !q.enoughCredit;
  const quoteCard = (
    <Card aria-labelledby="quote-title">
      <CardTitle id="quote-title">Price</CardTitle>
      {uploading ? (
        <p className="text-small text-text-muted">The price shows as soon as your upload has finished.</p>
      ) : q ? (
        <div className="flex flex-col gap-4">
          <div>
            <p className="text-heading font-semibold">
              <Money paise={q.holdPaise} /> is held now
            </p>
            <p className="text-caption text-text-muted">
              30 minutes at your most per hour ({<Money paise={request.maxPricePaisePerMin! * 60} />}), set
              aside from your credit while the session runs. You&apos;re charged only for the minutes it runs.
            </p>
          </div>
          <dl className="flex flex-col gap-3 border-t border-border pt-4 text-small">
            <div>
              <dt className="sr-only">Most you&apos;ll pay</dt>
              <dd>
                You&apos;ll pay at most <Money paise={cap} className="font-semibold" />, your spending cap.
              </dd>
            </div>
            <div>
              <dt className="sr-only">Hosts free now</dt>
              <dd>
                Hosts free now: <span className="font-semibold tabular">{q.hostsFitNow}</span>
                {q.pricePaisePerMin && (
                  <>
                    ,{" "}
                    <span className="font-semibold">
                      <Money paise={q.pricePaisePerMin.min * 60} />
                      {q.pricePaisePerMin.max !== q.pricePaisePerMin.min && (
                        <>
                          –<Money paise={q.pricePaisePerMin.max * 60} />
                        </>
                      )}
                    </span>{" "}
                    an hour
                  </>
                )}
              </dd>
            </div>
            <div>
              <dt className="sr-only">If a host drops</dt>
              <dd>
                If a host drops, your session moves to another and you&apos;re not charged for lost time.
              </dd>
            </div>
            <div>
              <dt className="sr-only">Credit after the hold</dt>
              <dd>
                {short ? (
                  <>
                    Your available credit: <Money paise={q.availablePaise} className="font-semibold" />
                  </>
                ) : (
                  <>
                    Your available credit after the hold:{" "}
                    <Money paise={q.availablePaise - q.holdPaise} className="font-semibold" />
                  </>
                )}
              </dd>
            </div>
          </dl>
          {short && (
            <Banner tone="danger">
              Booking holds <Money paise={q.holdPaise} />; you have <Money paise={q.availablePaise} />{" "}
              available. Lower the most per hour or the spending cap.
            </Banner>
          )}
          {!short && q.hostsFitNow === 0 && (
            <Banner tone="warning">
              No host fits right now. If none frees up within a minute, nothing is charged.
            </Banner>
          )}
        </div>
      ) : quote.isError ? (
        <div className="flex flex-col items-start gap-3" role="alert">
          <p className="text-small">{problemMessage(quote.error)}</p>
          <Button variant="secondary" onClick={() => void quote.refetch()} busy={quote.isFetching}>
            Try again
          </Button>
        </div>
      ) : (
        <div role="status" aria-label="Working out the price" className="flex flex-col gap-4">
          <div>
            <SkeletonLine size="title" width="w-2/3" />
            <SkeletonLine size="caption" width="w-full" />
            <SkeletonLine size="caption" width="w-3/4" />
          </div>
          <div className="flex flex-col gap-3 border-t border-border pt-4">
            {[0, 1, 2, 3].map((key) => (
              <SkeletonLine key={key} size="small" width="w-3/4" />
            ))}
          </div>
        </div>
      )}
      <div className="mt-6 flex flex-col gap-3">
        {book.isError &&
          !(book.error instanceof ApiError && book.error.type === "/problems/validation-failed") && (
            <Banner tone="danger">{problemMessage(book.error)}</Banner>
          )}
        <Button block disabled={uploading || !q || short} busy={book.isPending} onClick={() => book.mutate()}>
          {uploading ? (
            <span className="tabular">Waiting for your upload… {file.progress.percent}%</span>
          ) : (
            <span>
              Book for up to <Money paise={cap} />
            </span>
          )}
        </Button>
      </div>
    </Card>
  );

  return laptop ? (
    <div className="grid grid-cols-12 items-start gap-6">
      <div className="col-span-7 min-w-0">{summary}</div>
      <div className="col-span-5 min-w-0">{quoteCard}</div>
    </div>
  ) : (
    <div className="flex flex-col gap-4">
      {summary}
      {quoteCard}
    </div>
  );
}
