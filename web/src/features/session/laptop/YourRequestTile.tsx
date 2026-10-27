import { breakable } from "@/lib/breakable";
import type { Session, Template } from "@/api/queries";
import { Money } from "@/components/ui/Money";
import { Tile } from "@/components/ui/Tile";
import { aboutRuntime } from "@/lib/catalog";
import { inputLine, type InputFact } from "@/lib/inputs";

/**
 * "Your request", the white tile on the finding screen (web/DESIGN.md "The session screen"): what was asked for, as the
 * session itself says it: each input, the preset chosen, the spending cap and what is held until it runs. Never a
 * figure the session doesn't carry.
 */
export function YourRequestTile({
  session,
  template,
  inputs: given,
}: {
  session: Session;
  template: Template | undefined;
  /** Every input, with its upload's size once read. */
  inputs: InputFact[];
}) {
  const inputs = given.map((input) => ({
    label: input.label,
    value:
      input.filename && !input.link ? breakable(`${input.filename} · ${inputLine(input)}`) : inputLine(input),
  }));
  const presets = Object.entries(session.presets).flatMap(([name, id]) => {
    const question = template?.questions.find((candidate) => candidate.name === name);
    const option = question?.options.find((candidate) => candidate.id === id);
    if (!question || !option) {
      return [];
    }
    return [
      {
        label: question.label.replace(/\?$/, ""),
        value: [option.label, option.minutes == null ? null : aboutRuntime(option.minutes * 60)]
          .filter(Boolean)
          .join(" · "),
      },
    ];
  });
  return (
    <Tile as="section" aria-labelledby="request-title" size="lg" className="flex flex-col gap-3 xl:p-6">
      <h2 id="request-title" className="text-section">
        Your request
      </h2>
      <dl className="flex flex-col">
        {[...inputs, ...presets].map((row) => (
          <div
            key={row.label}
            className="flex items-baseline justify-between gap-4 border-b border-line py-2.5 text-body"
          >
            <dt className="shrink-0 text-text-2">{row.label}</dt>
            <dd className="min-w-0 flex-1 text-right font-semibold break-words">{row.value}</dd>
          </div>
        ))}
        <div className="flex items-baseline justify-between gap-4 border-b border-line py-2.5 text-body">
          <dt className="text-text-2">Spending cap</dt>
          <dd className="font-semibold">
            <Money paise={session.maxSpendPaise} />
          </dd>
        </div>
        <div className="flex items-baseline justify-between gap-4 py-2.5 text-body">
          <dt className="text-text-2">Held until it runs</dt>
          <dd className="font-semibold">
            <Money paise={session.reservedPaise} />
          </dd>
        </div>
      </dl>
      <p className="text-caption text-text-2">Nothing is charged until it runs.</p>
    </Tile>
  );
}
