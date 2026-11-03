/** What a failure of the main script's budget says (scripts/first-load.ts): which of its modules grew, or are new. */

/** The modules by a name that's the same on every machine, and only the ones with any bytes left in the script. */
export function tidyModuleSizes(sizes: Record<string, number>): Record<string, number> {
  const named = Object.entries(sizes)
    .filter(([, bytes]) => bytes > 0)
    .map(([id, bytes]) => {
      const clean = id.replace(/\0|\?.*$/g, "");
      const inPackage = clean.lastIndexOf("/node_modules/");
      const inSource = clean.lastIndexOf("/src/");
      const name =
        inPackage >= 0
          ? clean.slice(inPackage + "/node_modules/".length)
          : inSource >= 0
            ? clean.slice(inSource + 1)
            : clean;
      return [name, bytes] as const;
    })
    .sort(([a], [b]) => a.localeCompare(b));
  return Object.fromEntries(named);
}

/** Before the minifier's share of the savings, so the names are what to look at, not an exact count of the budget. */
export function describeGrowth(
  before: Record<string, number>,
  now: Record<string, number>,
  shown = 8,
): string {
  const grew = Object.entries(now)
    .map(([name, bytes]) => ({ name, added: bytes - (before[name] ?? 0), isNew: !(name in before) }))
    .filter(({ added }) => added > 0)
    .sort((a, b) => b.added - a.added);
  if (grew.length === 0) {
    return "No module in it grew by name: look for a change in how the chunk is split, in vite.config.ts.";
  }
  const lines = grew
    .slice(0, shown)
    .map(
      ({ name, added, isNew }) =>
        `  +${added} bytes before compression  ${name}${isNew ? " (new in the main script: who imports it?)" : ""}`,
    );
  return ["What grew it:", ...lines].join("\n");
}
