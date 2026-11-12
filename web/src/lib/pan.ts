/** "ABCPE1234F": what a PAN looks like, checked again by the server, which says why it's refused. */
export const PAN_SHAPE = /^[A-Z]{3}[PHCFATBLJ][A-Z][0-9]{4}[A-Z]$/;

/** A PAN as it's ever shown: its last four characters. */
export function maskedPan(last4: string): string {
  return `••••••${last4}`;
}
