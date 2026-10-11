/**
 * An invite code as people type it (ADR 0024): `IDLE-7K2Q-9XMD`, the prefix and two groups of four. It's shown in
 * groups as it's typed, in capitals, and a pasted code with spaces, lower case or no dashes comes out the same.
 * The server compares codes the same way (letters and digits only), so what's sent is only a convenience.
 */
const PREFIX = "IDLE";
const GROUP = 4;
const LENGTH = 2 * GROUP;

/** The 8 characters after the prefix, as far as they've been typed. */
function payload(raw: string): string {
  const letters = raw.toUpperCase().replace(/[^A-Z0-9]/g, "");
  return (letters.startsWith(PREFIX) ? letters.slice(PREFIX.length) : letters).slice(0, LENGTH);
}

/** What the field shows for what's been typed or pasted so far. */
export function formatInviteCode(raw: string): string {
  const letters = raw.toUpperCase().replace(/[^A-Z0-9]/g, "");
  // Still typing the prefix itself: "I", "ID", "IDL".
  if (letters.length > 0 && letters.length < PREFIX.length && PREFIX.startsWith(letters)) {
    return letters;
  }
  const rest = payload(raw);
  if (rest === "") {
    return letters === PREFIX ? `${PREFIX}-` : "";
  }
  return rest.length > GROUP ? `${PREFIX}-${rest.slice(0, GROUP)}-${rest.slice(GROUP)}` : `${PREFIX}-${rest}`;
}

/** Whether all 8 characters are there; the server says whether the code is one it accepts. */
export function isCompleteInviteCode(raw: string): boolean {
  return payload(raw).length === LENGTH;
}
