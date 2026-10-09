import type { Template } from "@/api/queries";
import { Chip } from "@/components/ui/Card";
import { Route } from "@/features/flow/Route";
import { YourSession, type DataStop } from "@/features/flow/YourSession";
import { aboutRuntime } from "@/lib/catalog";
import { formatPaise, formatPaiseRange, parseRupees } from "@/lib/money";
import type { BookValues } from "./form";
import { uploadLine } from "./FilePicker";
import { settingsSummary } from "./summary";
import type { QuoteState } from "./useQuote";
import type { ChosenFile } from "./useUpload";
import { firstInput } from "@/lib/inputs";

/** The session's data as the route's first stop; null for a template that takes none. */
export function dataStop(template: Template, values: BookValues, file: ChosenFile | null): DataStop | null {
  const input = firstInput(template);
  if (input === null) {
    return null;
  }
  if (values.inputKind === "url" && values.inputUrl.trim() !== "") {
    return { detail: "From your link", done: true };
  }
  if (values.inputKind === "upload" && file) {
    return { detail: `${file.name} · ${uploadLine(file)}`, done: file.progress.phase === "ready" };
  }
  return {
    detail: input.required ? "Upload a file or give a link" : "None: it's optional",
    done: !input.required,
  };
}

/**
 * The laptop's right panel on Book (docs/design/redesign/book-laptop.html): the route from the data to the outputs,
 * then the chips: where it stops, that it's charged per minute, and the price range per hour (from the quote).
 */
export function BookPanel({
  template,
  values,
  file,
  quote,
}: {
  template: Template;
  values: BookValues;
  file: ChosenFile | null;
  quote: QuoteState | null;
}) {
  const range = quote?.kind === "quoted" ? quote.quote.pricePaisePerMin : null;
  const cap = parseRupees(values.spendingCap);
  return (
    <YourSession
      data={dataStop(template, values, file)}
      runtime={aboutRuntime(template.typicalRuntimeSeconds)}
      chips={
        <>
          {cap !== null && cap > 0 && <Chip className="tabular">Stops at {formatPaise(cap)}</Chip>}
          <Chip>Charged per minute</Chip>
          {/* Last, so the price arriving from the quote moves none of the others. */}
          {range && (
            <Chip className="tabular">{formatPaiseRange(range.min * 60, range.max * 60)} an hour</Chip>
          )}
        </>
      }
    />
  );
}

/** The phone's summary in the grey area above the sheet (docs/design/redesign/book-phone.html): what, then what comes out. */
export function BookSummary({
  template,
  values,
  file,
}: {
  template: Template;
  values: BookValues;
  file: ChosenFile | null;
}) {
  const data = dataStop(template, values, file);
  const runtime = aboutRuntime(template.typicalRuntimeSeconds);
  const settings = settingsSummary(template, values);
  return (
    <div className="rounded-panel bg-ground px-4.5 py-4">
      <Route
        compact
        stops={[
          {
            title: template.name,
            detail: data?.detail ?? "Nothing to upload",
            state: data === null || data.done ? "done" : "next",
          },
          {
            title: "Your outputs",
            detail: [runtime && `in ${runtime}`, settings].filter(Boolean).join(" · "),
            state: "end",
          },
        ]}
      />
    </div>
  );
}
