/** What a help ticket can be about on a site (src/features/help/Help.tsx): the person's sessions there, or machines. */
export interface HelpSubjects {
  sessions: ReadonlyArray<{ id: string; label: string }>;
  machines: ReadonlyArray<{ id: string; label: string }>;
}
