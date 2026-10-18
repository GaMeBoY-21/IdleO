/**
 * The Windows app's download button (ADR 0034): asks GET /v1/agent-releases/latest once the page has painted, and
 * when there's a release shows "Download for Windows" with "v0.2.0 · 9.8 MB" in place of "coming soon". No release,
 * or the API can't be reached: the page stays as built. Plain script: nothing of the app is loaded here.
 */
import { releaseLine } from "@/lib/hosting";

interface LatestRelease {
  version: string;
  setupSizeBytes: number;
}

export function showRelease(root: HTMLElement): void {
  const api = root.dataset.api;
  if (!api) {
    return;
  }
  const load = async (): Promise<void> => {
    try {
      const response = await fetch(`${api}/v1/agent-releases/latest`, {
        headers: { Accept: "application/json" },
        signal: AbortSignal.timeout(10_000),
      });
      if (!response.ok) {
        return;
      }
      const release = (await response.json()) as LatestRelease;
      const meta = root.querySelector<HTMLElement>('[data-role="download-meta"]')!;
      meta.textContent = releaseLine(release);
      meta.hidden = false;
      root.querySelector<HTMLElement>('[data-role="download"]')!.hidden = false;
      root.querySelector<HTMLElement>('[data-role="download-soon"]')!.hidden = true;
    } catch {
      // The page stays as built: "coming soon".
    }
  };
  // After the first paint, so the page never waits on it.
  requestAnimationFrame(() => setTimeout(() => void load(), 0));
}
