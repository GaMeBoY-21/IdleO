import { Lock } from "lucide-react";
import { useSaveNotifications, type NotificationItem } from "@/api/account";
import { Banner } from "@/components/ui/Banner";
import { cn } from "@/lib/cn";
import { problemMessage } from "@/lib/problems";

type Channel = "email" | "inApp";

const CHANNELS: { channel: Channel; label: string }[] = [
  { channel: "email", label: "Email" },
  { channel: "inApp", label: "In the app" },
];

const ROWS = "flex items-center gap-2";
const COLUMN = "flex w-16 shrink-0 justify-center md:w-20";

/**
 * What the person is told about, one row for each kind and one switch each for email and for the app. The kinds
 * about money and safety are locked on, each with its reason: they can't be switched off, and the server refuses
 * if asked. A switch moves only when the server has saved it (never optimistically); until then every switch waits.
 */
export function NotificationChoices({ items }: { items: NotificationItem[] }) {
  const save = useSaveNotifications();

  const change = (kind: string, channel: Channel, on: boolean) => {
    save.mutate(items.map((item) => (item.kind === kind ? { ...item, [channel]: on } : item)));
  };

  return (
    <div className="flex flex-col gap-2">
      <div className={cn(ROWS, "pb-1 text-caption font-medium text-text-muted")} aria-hidden="true">
        <span className="flex-1" />
        {CHANNELS.map(({ channel, label }) => (
          <span key={channel} className={COLUMN}>
            {label}
          </span>
        ))}
      </div>
      <ul className="flex flex-col divide-y divide-divider">
        {items.map((item) => (
          <li
            key={item.kind}
            className={cn(ROWS, "min-h-18 py-3 xl:min-h-16", save.isPending && "opacity-60")}
          >
            <div className="flex min-w-0 flex-1 flex-col gap-0.5">
              <p className="text-option font-semibold">{item.title}</p>
              {item.locked && (
                <p className="flex items-start gap-1.5 text-caption text-text-muted">
                  <Lock aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" strokeWidth={2} />
                  <span>{item.lockedReason}</span>
                </p>
              )}
            </div>
            {CHANNELS.map(({ channel, label }) => (
              <span key={channel} className={COLUMN}>
                <SwitchBox
                  label={`${item.title}: ${label.toLowerCase()}`}
                  checked={item[channel]}
                  locked={item.locked}
                  disabled={save.isPending}
                  onChange={(on) => change(item.kind, channel, on)}
                />
              </span>
            ))}
          </li>
        ))}
      </ul>
      <div className="min-h-4.5 text-caption text-text-muted" aria-live="polite">
        {save.isPending ? "Saving…" : save.isSuccess ? "Saved." : ""}
      </div>
      {save.isError && <Banner tone="danger">{problemMessage(save.error)}</Banner>}
    </div>
  );
}

/** A switch with no visible label of its own, since its row names it; a locked one is on, and can't be moved. */
function SwitchBox({
  label,
  checked,
  locked,
  disabled,
  onChange,
}: {
  label: string;
  checked: boolean;
  locked: boolean;
  disabled: boolean;
  onChange: (on: boolean) => void;
}) {
  return (
    <span className="flex min-h-touch items-center justify-center">
      <span className="relative inline-flex items-center">
        <input
          type="checkbox"
          role="switch"
          aria-label={label}
          checked={checked}
          disabled={locked || disabled}
          onChange={(event) => onChange(event.target.checked)}
          className="peer h-7 w-12 cursor-pointer appearance-none rounded-full border-2 border-control bg-fill transition-colors duration-120 checked:border-primary checked:bg-primary disabled:cursor-not-allowed"
        />
        <span
          aria-hidden="true"
          className={cn(
            "pointer-events-none absolute left-1.5 size-4 rounded-full bg-control transition-transform duration-120 peer-checked:translate-x-5 peer-checked:bg-primary-fg",
            locked && "opacity-60",
          )}
        />
      </span>
    </span>
  );
}
