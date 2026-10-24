import type { UseQueryResult } from "@tanstack/react-query";
import { useState } from "react";
import { downloadInvoice, type Invoice } from "@/api/wallet";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState } from "@/components/ui/States";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import { problemMessage } from "@/lib/problems";
import { formatDay } from "@/lib/time";

const ROW = "flex min-h-18 items-center gap-3 py-4";

/**
 * Invoices, receipts and credit notes (ADR 0039), newest first, in a box of one height above the history, each a PDF
 * fetched when the renter chooses Download:
 * one for every top-up, one for every refund back to where it was paid from, and a monthly invoice for sessions when
 * GST is charged on use.
 */
export function Documents({ invoices }: { invoices: UseQueryResult<{ invoices: Invoice[] }> }) {
  return (
    <section aria-labelledby="documents-title" className="flex flex-col gap-1">
      <SectionTitle id="documents-title">Invoices and receipts</SectionTitle>
      {/* One height whatever it holds, three documents' worth, the rest scrolled to: nothing beside or below it moves
          when they arrive (CLS 0). */}
      <div className="h-documents overflow-y-auto rounded-tile">
        {invoices.data ? (
          invoices.data.invoices.length === 0 ? (
            <EmptyState title="Nothing yet">
              Each time you add credit, its receipt is here to download.
            </EmptyState>
          ) : (
            <Tile as="div" className="px-4.5 py-0">
              <ul className="flex flex-col divide-y divide-divider">
                {invoices.data.invoices.map((invoice) => (
                  <DocumentRow key={invoice.id} invoice={invoice} />
                ))}
              </ul>
            </Tile>
          )
        ) : invoices.isError ? (
          <ErrorState
            error={invoices.error}
            what="your invoices"
            onRetry={() => void invoices.refetch()}
            retrying={invoices.isFetching}
          />
        ) : (
          <Tile
            as="div"
            className="flex flex-col divide-y divide-divider px-4.5 py-0"
            role="status"
            aria-label="Loading your invoices"
          >
            {[0, 1].map((key) => (
              <div key={key} className={ROW}>
                <div className="flex flex-1 flex-col gap-0.5">
                  <SkeletonLine size="option" width="w-1/2" />
                  <SkeletonLine size="small" width="w-1/3" />
                </div>
                <SkeletonLine size="option" width="w-1/5" />
                <span aria-hidden="true" className="size-touch shrink-0" />
              </div>
            ))}
          </Tile>
        )}
      </div>
    </section>
  );
}

function DocumentRow({ invoice }: { invoice: Invoice }) {
  const [state, setState] = useState<"idle" | "busy" | "failed">("idle");
  const [problem, setProblem] = useState("");
  const download = async () => {
    setState("busy");
    try {
      await downloadInvoice(invoice);
      setState("idle");
    } catch (error) {
      setProblem(problemMessage(error));
      setState("failed");
    }
  };
  return (
    <li className={ROW}>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <p className="text-option font-semibold">{invoice.title}</p>
        <p className="text-small text-text-muted">
          <span className="tabular">{invoice.number}</span> · {formatDay(invoice.issuedAt)}
          {state === "failed" && (
            <span role="alert" className="block text-danger">
              {problem}
            </span>
          )}
        </p>
      </div>
      <Money paise={invoice.totalPaise} className="text-option font-semibold tabular" />
      <Button
        variant="secondary"
        className="shrink-0"
        busy={state === "busy"}
        aria-label={`Download ${invoice.title.toLowerCase()} ${invoice.number}`}
        onClick={() => void download()}
      >
        Download
      </Button>
    </li>
  );
}
