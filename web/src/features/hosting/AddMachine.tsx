import { MonitorCheck, MonitorDown, MousePointerClick } from "lucide-react";
import { useLatestRelease } from "@/api/hosting";
import { HostFlow } from "@/components/shell/HostFlow";
import { Skeleton } from "@/components/ui/Skeleton";
import { Tile } from "@/components/ui/Tile";
import { config } from "@/config";
import { useAfterPaint } from "@/lib/after-first-screen";
import { releaseLine } from "@/lib/hosting";
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

const BUTTON =
  "flex min-h-14 w-full items-center justify-center gap-2 rounded-full bg-primary px-6 text-option font-semibold text-primary-fg disabled:opacity-40";

/**
 * The main button (ADR 0034): Download for Windows, with the release's version and size, once the API says there is
 * one; "Windows app: coming soon" while there isn't, never a download that isn't there. Every state is the button's
 * height and one line under it, so nothing moves when the answer comes.
 */
function DownloadButton() {
  // The page paints with the button's skeleton first; the answer is asked for after that paint, so the request is not
  // on the way to the page's largest paint (Lighthouse counts each round trip before it).
  const painted = useAfterPaint();
  const release = useLatestRelease(painted);
  if (release.isPending) {
    return (
      <div className="flex flex-col gap-3" aria-busy="true" aria-label="Checking for the Windows app">
        <Skeleton className="h-14 w-full rounded-full" />
        <Skeleton className="h-5 w-32" />
      </div>
    );
  }
  if (release.isError) {
    return (
      <div className="flex flex-col gap-3">
        <button type="button" className={BUTTON} onClick={() => void release.refetch()}>
          Couldn&apos;t check for the Windows app · Try again
        </button>
        <p className="min-h-5 text-small text-text-muted">Check you&apos;re online, then try again.</p>
      </div>
    );
  }
  if (release.data === null) {
    return (
      <div className="flex flex-col gap-3">
        <button type="button" disabled className={BUTTON}>
          Windows app: coming soon
        </button>
        <p className="min-h-5 text-small text-text-muted">&nbsp;</p>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-3">
      <a href={`${config.apiUrl}/v1/agent-releases/latest/setup`} className={BUTTON}>
        <MonitorDown className="size-5" strokeWidth={2} aria-hidden="true" />
        Download for Windows
      </a>
      <p className="min-h-5 text-small text-text-muted tabular">{releaseLine(release.data)}</p>
    </div>
  );
}

/**
 * Add a machine (docs/plan/host-experience.md §1, ADR 0032): one download and one Approve, never a code to copy. Three
 * short steps, the main button, and what a laptop needs. A developer links a machine from its terminal with
 * `idleo-agent link`, which opens the approval page by itself.
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
        <DownloadButton />
        <p className="text-small text-text-muted">Windows 10 or 11 · NVIDIA RTX graphics · 40 GB free</p>
        <p className="text-small text-text-muted">
          Have a developer setup? Run <span className="font-semibold">idleo-agent link</span> on the laptop.
        </p>
      </Tile>
    </HostFlow>
  );
}
