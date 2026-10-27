import { brand } from "@/brand/brand";
import type { MakerHelp } from "../help";
import { band, column, heading, muted, readable, tile } from "../ui";

/** "your ASUS laptop", or "your laptop" for a maker the setup app doesn't know. */
export const laptop = (maker: MakerHelp) =>
  maker.slug === "other" ? "your laptop" : `your ${maker.name} laptop`;

/**
 * One maker's "Show me how" page (ADR 0033): turning on virtualization, which renters' jobs need, from Windows itself
 * or with the key at start-up, then the setting in that maker's menu. Plain text and numbered steps.
 */
export function Help({ maker }: { maker: MakerHelp }) {
  return (
    <section className={`${column} ${band}`}>
      <div className={readable}>
        <h1 className={heading}>Turn on virtualization on {laptop(maker)}</h1>
        <p className={`${muted} mt-4 max-w-prose`}>
          {brand.name} runs renters&apos; jobs in a sealed space that needs one setting in your laptop&apos;s
          startup menu turned on. It takes a few minutes and one restart. Close your other programs first.
        </p>
        <ol className="mt-6 grid gap-2.5 md:gap-3.5">
          <li className={tile}>
            <h2 className="text-option font-semibold">1. Open the startup menu</h2>
            <p className="mt-2 text-body text-text-2">
              From Windows: Settings, then System, then Recovery. Next to Advanced startup, choose Restart
              now. Then choose Troubleshoot, Advanced options, UEFI Firmware Settings, and Restart. Or restart
              and press {maker.key} as soon as the screen goes dark.
            </p>
          </li>
          {maker.steps.map((step, index) => (
            <li key={step} className={tile}>
              <h2 className="text-option font-semibold">{index + 2}. In the menu</h2>
              <p className="mt-2 text-body text-text-2">{step}</p>
            </li>
          ))}
          <li className={tile}>
            <h2 className="text-option font-semibold">{maker.steps.length + 2}. Save and restart</h2>
            <p className="mt-2 text-body text-text-2">
              Press F10 (or choose Save and Exit), then confirm. When Windows is back, open {brand.name}
              &apos;s setup again: it carries on from where it stopped.
            </p>
          </li>
        </ol>
        <p className={`${muted} mt-6 max-w-prose`}>
          The names in the menu differ a little between models. If you can&apos;t find the setting, the
          laptop&apos;s manual or its maker&apos;s support site has it under &quot;virtualization&quot;.
        </p>
      </div>
    </section>
  );
}
