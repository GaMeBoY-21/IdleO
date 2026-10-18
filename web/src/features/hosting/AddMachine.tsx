import { MonitorCheck, MonitorDown, MousePointerClick } from "lucide-react";
import { HostFlow } from "@/components/shell/HostFlow";
import { Tile } from "@/components/ui/Tile";
import { useTitle } from "@/lib/title";

const LEDE = "One download, one click to approve, and then it earns. No code to copy, no terminal.";

const STEPS = [
  {
    icon: MonitorDown,
    title: "Download the IdleO app for Windows",
    text: "One file, opened from your browser's download bar.",
  },
  {
    icon: MonitorCheck,
    title: "Open it: it sets everything up",
    text: "It checks your laptop and sets up the safe space renters' jobs run in. Nothing for you to install by hand.",
  },
  {
    icon: MousePointerClick,
    title: "Approve the link in your browser",
    text: "The app opens this page's link for you. Check it's your laptop, confirm the price, and press Approve.",
  },
] as const;

/**
 * Add a machine (docs/plan/host-experience.md §1, ADR 0032): one download and one Approve, never a code to copy. Three
 * short steps, the main button, and what a laptop needs. The Windows app doesn't exist yet, so the button says so and
 * is off; it never offers a download that isn't there. A developer links a machine from its terminal with `idleo-agent
 * link`, which opens the approval page by itself.
 */
export function AddMachine() {
  useTitle("Earn with this laptop");
  return (
    <HostFlow back="/hosting/" backLabel="Hosting" title="Earn with this laptop" lede={LEDE}>
      <ol aria-label="How it works" className="flex flex-col gap-3.5">
        {STEPS.map(({ icon: Icon, title, text }, index) => (
          <li key={title}>
            <Tile className="flex items-start gap-4">
              <span
                aria-hidden="true"
                className="flex size-12 shrink-0 items-center justify-center rounded-icon bg-ground"
              >
                <Icon className="size-6" strokeWidth={1.75} />
              </span>
              <div className="flex min-w-0 flex-col gap-1">
                <p className="text-small text-text-muted">Step {index + 1}</p>
                <h2 className="text-option font-semibold">{title}</h2>
                <p className="text-small text-text-muted">{text}</p>
              </div>
            </Tile>
          </li>
        ))}
      </ol>
      <Tile className="flex flex-col gap-3">
        <button
          type="button"
          disabled
          className="flex min-h-14 w-full items-center justify-center gap-2 rounded-full bg-primary px-6 text-option font-semibold text-primary-fg disabled:opacity-40"
        >
          Windows app: coming soon
        </button>
        <p className="text-small text-text-muted">Windows 10 or 11 · NVIDIA RTX graphics · 40 GB free</p>
        <p className="text-small text-text-muted">
          Have a developer setup? Run <span className="font-semibold">idleo-agent link</span> on the laptop.
        </p>
      </Tile>
    </HostFlow>
  );
}
