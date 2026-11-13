import { breakable } from "@/lib/breakable";
import type { Session, Template } from "@/api/queries";
import { SectionHeader } from "@/components/ui/Headers";
import { KeyValueList } from "@/components/ui/KeyValues";
import { Money } from "@/components/ui/Money";
import { Tile } from "@/components/ui/Tile";
import { aboutRuntime } from "@/lib/catalog";
import { inputLine, type InputFact } from "@/lib/inputs";

/**
 * "Your request", the card on the finding screen (web/DESIGN.md "The session screen"): what was asked for, as the
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
    <Tile as="section" aria-labelledby="request-title" size="lg" className="flex flex-col gap-2 xl:p-6">
      <SectionHeader id="request-title">Your request</SectionHeader>
      <KeyValueList
        layout="split"
        size="body"
        divided
        items={[
          ...[...inputs, ...presets].map((row): [string, React.ReactNode] => [row.label, row.value]),
          ["Spending cap", <Money key="cap" paise={session.maxSpendPaise} />],
          ["Held until it runs", <Money key="held" paise={session.reservedPaise} />],
        ]}
      />
      <p className="text-caption text-ink-subtle">Nothing is charged until it runs.</p>
    </Tile>
  );
}
