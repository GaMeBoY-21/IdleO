import type { UseQueryResult } from "@tanstack/react-query";
import { ChevronRight, Clock, Cpu } from "lucide-react";
import type { components } from "@/api/schema.gen";
import type { Template } from "@/api/queries";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { ErrorState } from "@/components/ui/States";
import { gigabytes, priceFrom } from "./prices";

type Availability = components["schemas"]["Availability"];

/** "about 20 seconds", "about 45 minutes", "about 2 hours". */
export function typicalRuntime(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) {
    return "Runtime depends on your data";
  }
  if (seconds < 90) {
    return `Usually about ${Math.max(1, Math.round(seconds / 10) * 10)} seconds`;
  }
  if (seconds < 90 * 60) {
    return `Usually about ${Math.round(seconds / 60)} minutes`;
  }
  return `Usually about ${Math.round(seconds / 3600)} hours`;
}

/** Step 1: every template as a card, and the card is the button (product surface 3.2). */
export function PickStep({
  templates,
  availability,
  onPick,
}: {
  templates: UseQueryResult<{ templates: Template[] }>;
  availability: UseQueryResult<Availability>;
  onPick: (id: string) => void;
}) {
  if (templates.isError) {
    return (
      <ErrorState
        error={templates.error}
        what="the templates"
        onRetry={() => void templates.refetch()}
        retrying={templates.isFetching}
      />
    );
  }
  if (!templates.data) {
    return (
      <div
        className="grid gap-4 md:grid-cols-2 xl:grid-cols-3"
        role="status"
        aria-label="Loading the templates"
      >
        {[0, 1, 2].map((key) => (
          <div
            key={key}
            className="flex flex-col gap-3 rounded-card border border-border bg-surface p-4 md:p-6"
          >
            <Skeleton className="h-6 w-3/4" />
            <Skeleton className="h-10 w-full" />
            <Skeleton className="mt-auto h-5 w-1/2" />
          </div>
        ))}
      </div>
    );
  }
  return (
    <ul className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
      {templates.data.templates.map((template) => {
        const from = availability.data ? priceFrom(availability.data, template.minVramMb) : undefined;
        return (
          <li key={template.id} className="flex">
            <button
              type="button"
              onClick={() => onPick(template.id)}
              className="flex w-full flex-col items-start gap-3 rounded-card border border-border bg-surface p-4 text-left hover:border-accent md:p-6"
            >
              <span className="flex w-full items-start justify-between gap-3">
                <span className="text-title font-semibold break-words">{template.name}</span>
                <ChevronRight
                  aria-hidden="true"
                  className="mt-1 size-5 shrink-0 text-text-muted"
                  strokeWidth={1.75}
                />
              </span>
              <span className="text-small text-text-muted">{template.description}</span>
              <span className="mt-auto flex flex-col gap-1 text-small">
                <span className="flex items-center gap-2">
                  <Cpu aria-hidden="true" className="size-4 text-text-muted" strokeWidth={1.75} />
                  At least {gigabytes(template.minVramMb)} GPU memory
                </span>
                <span className="flex items-center gap-2">
                  <Clock aria-hidden="true" className="size-4 text-text-muted" strokeWidth={1.75} />
                  {typicalRuntime(template.typicalRuntimeSeconds)}
                </span>
                <span className="min-h-5 font-medium">
                  {from === undefined ? (
                    availability.isError ? (
                      ""
                    ) : (
                      <Skeleton className="h-4 w-1/2" />
                    )
                  ) : from === null ? (
                    <span className="text-text-muted">No hosts free right now</span>
                  ) : (
                    <>
                      From <Money paise={from * 60} /> an hour
                    </>
                  )}
                </span>
              </span>
            </button>
          </li>
        );
      })}
    </ul>
  );
}
