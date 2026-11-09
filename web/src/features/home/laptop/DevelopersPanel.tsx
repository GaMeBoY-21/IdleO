import { CopyButton } from "@/components/ui/CopyButton";
import { Tile } from "@/components/ui/Tile";
import { siteLink } from "@/lib/site-links";

/** The Python SDK's first session, as the SDK's own reference starts (docs/site/generated/sdk.md). */
export const SDK_SNIPPET = `from idleo import Client

session = Client().sessions.run("cuda-smoke-test", max_spend_paise=500)
session.wait()`;

/**
 * For developers (ADR 0058): the command line and the SDK, each as their docs give them today (both from a copy of the
 * repository, until they're packaged), the snippet with a Copy button, and where the API keys and the docs are.
 */
export function DevelopersPanel() {
  return (
    <Tile size="lg" aria-labelledby="developers-title" className="flex flex-col gap-3">
      <h2 id="developers-title" className="text-caption font-bold tracking-wider text-text-2 uppercase">
        From your terminal
      </h2>
      <p className="text-small text-text-2">
        Build the command line in <code className="font-mono">cli/</code> with{" "}
        <code className="font-mono">cargo build --release</code>, then{" "}
        <code className="font-mono">idleo login</code>. The Python SDK:{" "}
        <code className="font-mono">pip install ./sdk/python</code>.
      </p>
      <pre className="overflow-x-auto rounded-control bg-panel-2 p-3 font-mono text-caption">
        <code>{SDK_SNIPPET}</code>
      </pre>
      <div className="flex flex-wrap items-center gap-3">
        <CopyButton text={SDK_SNIPPET} label="Copy the snippet" />
        <a href={siteLink("/docs/sdk/")} className="text-small font-semibold underline">
          The SDK&apos;s docs
        </a>
        <a href="/account/keys/" className="text-small font-semibold underline">
          API keys
        </a>
      </div>
    </Tile>
  );
}
