import { isLive, type Session } from "@/api/queries";
import { Money } from "@/components/ui/Money";
import { Elapsed } from "@/components/ui/Time";
import { gigabytes } from "@/features/book/form";
import { costSoFar } from "@/lib/sessions";

/** "RTX 4080 Laptop": a GPU's name as people say it, without the maker's prefix. */
export const gpuName = (model: string) =>
  model
    .replace(/^NVIDIA\s+/i, "")
    .replace(/^GeForce\s+/i, "")
    .replace(/\s+GPU$/i, "");

/**
 * The four figures of a session (product surface 3.2): how long it has run, ticking until it ends; what it has cost,
 * changed in place, never counted up; how much of the spending cap that is; and the GPU it's on. A 2 × 2 grid on a
 * phone, one row on a laptop.
 */
export function Figures({ session }: { session: Session }) {
  const live = isLive(session);
  const cost = live ? costSoFar(session) : session.chargedPaise;
  const share = Math.min(100, Math.round((cost / Math.max(1, session.maxSpendPaise)) * 100));
  return (
    <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-card border border-border bg-border xl:grid-cols-4">
      <Figure label={live ? "Running for" : "Ran for"}>
        {session.startedAt ? (
          <Elapsed from={session.startedAt} to={live ? null : (session.endedAt ?? session.startedAt)} />
        ) : (
          <span className="text-text-muted">Not started</span>
        )}
      </Figure>
      <Figure label={live || !session.settled ? "Cost so far" : "Charged"}>
        <Money paise={cost} />
      </Figure>
      <Figure label="Spending cap">
        <span className="text-body">
          <Money paise={cost} className="font-semibold" /> <span className="text-text-muted">of</span>{" "}
          <Money paise={session.maxSpendPaise} />
        </span>
        <span
          role="meter"
          aria-label="Share of the spending cap used"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={share}
          className="mt-2 block h-1 w-full overflow-hidden rounded-full bg-sunken"
        >
          <span className="block h-full rounded-full bg-accent" style={{ width: `${share}%` }} />
        </span>
      </Figure>
      <Figure label="GPU">
        {session.gpu ? (
          <span className="text-body break-words">
            {gpuName(session.gpu.model)} · {gigabytes(session.gpu.vramMb)}
          </span>
        ) : (
          <span className="text-body text-text-muted">{live ? "Finding a host…" : "None"}</span>
        )}
      </Figure>
    </dl>
  );
}

function Figure({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col bg-surface p-4">
      <dt className="text-small text-text-muted">{label}</dt>
      <dd className="mt-1 text-title font-semibold tabular">{children}</dd>
    </div>
  );
}
