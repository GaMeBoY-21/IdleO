import { Minus, Plus } from "lucide-react";
import { useId, useState } from "react";
import { usePriceBand, useSaveListing, type Host } from "@/api/hosting";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { gpuName } from "@/lib/gpu";
import {
  KEEP_FREE_MAX,
  KEEP_FREE_MIN,
  MAX_PRICE,
  MIN_PRICE,
  TEMP_MAX,
  TEMP_MIN,
  stepPrice,
  youKeep,
} from "@/lib/hosting";
import { formatPaiseRange } from "@/lib/money";
import { problemMessage } from "@/lib/problems";

/** What a price falls back to when there's neither a listing nor a suggested band: ₹7.20 an hour. */
const FALLBACK_PRICE = 12;
const DEFAULT_TEMP = 85;
const DEFAULT_KEEP_FREE = 20;
const STEP_LABEL = "₹0.60 an hour";

/**
 * A machine's price and limits (docs/design/hosting/price-phone.html), shared by its own screen and the last step of
 * adding one: the price in ₹0.60 steps, what the owner keeps of it, the range others charge, the temperature it
 * stops at, the disk kept free, and that it takes one session at a time. Prices are rupees an hour on screen and paise
 * a minute in the API (1 paisa a minute is exactly ₹0.60 an hour), always whole numbers of paise.
 */
export function PriceForm({
  host,
  saveLabel = "Save",
  onSaved,
}: {
  host: Host;
  saveLabel?: string;
  onSaved: () => void;
}) {
  const band = usePriceBand(host.id);
  const save = useSaveListing(host.id);
  const listing = host.listing;
  const [edits, setEdits] = useState<{ price?: number; temp?: number; keepFree?: string }>({});
  const gpu = host.gpus.find((candidate) => !candidate.gone);

  const bandMiddle = band.data
    ? Math.round((band.data.lowPaisePerMin + band.data.highPaisePerMin) / 2)
    : band.isError
      ? FALLBACK_PRICE
      : null;
  const price = edits.price ?? listing?.batchPricePaisePerMin ?? bandMiddle;
  const temp = edits.temp ?? listing?.gpuTempCapC ?? DEFAULT_TEMP;
  const keepFreeText = edits.keepFree ?? String(listing?.keepFreeGb ?? DEFAULT_KEEP_FREE);
  const keepFree = /^\d{1,4}$/.test(keepFreeText) ? Number(keepFreeText) : null;
  const keepFreeError =
    keepFree === null || keepFree < KEEP_FREE_MIN || keepFree > KEEP_FREE_MAX
      ? `Choose from ${KEEP_FREE_MIN} to ${KEEP_FREE_MAX} GB.`
      : undefined;
  const tempId = useId();

  const change = (next: typeof edits) => {
    setEdits({ ...edits, ...next });
    save.reset();
  };

  if (price === null) {
    return <PriceSkeleton />;
  }
  const { keep, fee } = youKeep(price);

  return (
    <form
      className="flex flex-col gap-6"
      onSubmit={(event) => {
        event.preventDefault();
        if (keepFree !== null && !keepFreeError) {
          save.mutate(
            {
              batchPricePaisePerMin: price,
              gpuTempCapC: temp,
              maxConcurrentSessions: 1,
              keepFreeGb: keepFree,
            },
            { onSuccess: onSaved },
          );
        }
      }}
    >
      <section aria-labelledby="price-title" className="flex flex-col gap-4">
        <h2 id="price-title" className="text-small font-semibold text-text-muted">
          Your price per hour
        </h2>
        <div className="flex items-center justify-between gap-4">
          <Button
            variant="secondary"
            aria-label={`Lower the price by ${STEP_LABEL}`}
            className="size-14 rounded-full p-0"
            disabled={price <= MIN_PRICE}
            onClick={() => change({ price: stepPrice(price, -1) })}
          >
            <Minus aria-hidden="true" className="size-5" strokeWidth={2} />
          </Button>
          <output aria-live="polite" className="text-timer tabular">
            <span className="sr-only">Price: </span>
            <Money paise={price * 60} />
            <span className="sr-only"> an hour</span>
          </output>
          <Button
            variant="secondary"
            aria-label={`Raise the price by ${STEP_LABEL}`}
            className="size-14 rounded-full p-0"
            disabled={price >= MAX_PRICE}
            onClick={() => change({ price: stepPrice(price, 1) })}
          >
            <Plus aria-hidden="true" className="size-5" strokeWidth={2} />
          </Button>
        </div>
        <dl className="flex flex-col gap-2 rounded-control bg-fill px-4 py-3.5 text-small">
          <div className="flex items-baseline justify-between gap-4">
            <dt className="font-semibold">You keep</dt>
            <dd className="tabular font-semibold">
              <Money paise={keep} /> an hour
            </dd>
          </div>
          <div className="flex items-baseline justify-between gap-4 text-text-muted">
            <dt>Platform fee (20%)</dt>
            <dd className="tabular">
              <Money paise={fee} />
            </dd>
          </div>
        </dl>
        <p className="min-h-10 text-small text-text-muted" role="status">
          {band.data ? (
            band.data.sampleSize >= 3 ? (
              <>
                Other {gpu ? gpuName(gpu.model) : "GPUs like yours"}s charge{" "}
                <span className="tabular">
                  {formatPaiseRange(band.data.lowPaisePerMin * 60, band.data.highPaisePerMin * 60)}
                </span>{" "}
                an hour. A lower price gets picked more often.
              </>
            ) : (
              <>
                Hosts with a GPU like yours usually charge{" "}
                <span className="tabular">
                  {formatPaiseRange(band.data.lowPaisePerMin * 60, band.data.highPaisePerMin * 60)}
                </span>{" "}
                an hour. A lower price gets picked more often.
              </>
            )
          ) : band.isError ? (
            "We can't suggest a price until the host app has connected. A lower price gets picked more often."
          ) : (
            "Looking up what others charge…"
          )}
        </p>
      </section>

      <section aria-label="Temperature limit" className="flex flex-col gap-2">
        <div className="flex items-baseline justify-between gap-4">
          <label htmlFor={`${tempId}-range`} className="text-small font-semibold">
            Pause above this temperature
          </label>
          <output htmlFor={`${tempId}-range`} className="text-option font-semibold tabular">
            {temp}°C
          </output>
        </div>
        <input
          id={`${tempId}-range`}
          type="range"
          min={TEMP_MIN}
          max={TEMP_MAX}
          step={1}
          value={temp}
          onChange={(event) => change({ temp: Number(event.target.value) })}
          className="h-touch w-full accent-primary"
          aria-describedby={`${tempId}-help`}
        />
        <div className="flex justify-between text-caption text-text-muted tabular" aria-hidden="true">
          <span>{TEMP_MIN}°C</span>
          <span>{TEMP_MAX}°C</span>
        </div>
        <p id={`${tempId}-help`} className="text-small text-text-muted">
          The session saves a checkpoint and pauses if your GPU gets hotter. Paused minutes aren&apos;t
          charged.
        </p>
      </section>

      <Field
        label="Keep free on my disk (GB)"
        inputMode="numeric"
        value={keepFreeText}
        onChange={(event) => change({ keepFree: event.target.value })}
        error={keepFreeError}
        hint="A session is only offered if its files fit beside this."
        autoComplete="off"
      />

      <p className="text-small text-text-muted">One session at a time, so your laptop stays usable.</p>

      {save.isError && <Banner tone="danger">{problemMessage(save.error)}</Banner>}
      <Button type="submit" large block busy={save.isPending} disabled={keepFreeError !== undefined}>
        {saveLabel}
      </Button>
    </form>
  );
}

/** The form's shape while the suggested price is looked up: the same blocks, the same heights. */
function PriceSkeleton() {
  return (
    <div role="status" aria-label="Loading the price" className="flex flex-col gap-6">
      <Skeleton className="h-5 w-1/3" />
      <Skeleton className="h-14 w-full" />
      <Skeleton className="h-20 w-full" />
      <Skeleton className="h-10 w-full" />
      <Skeleton className="h-28 w-full" />
      <Skeleton className="h-24 w-full" />
    </div>
  );
}
