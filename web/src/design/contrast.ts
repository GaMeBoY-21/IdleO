/** WCAG 2.2 relative luminance and contrast ratio, for checking the tokens (web/DESIGN.md "Contrast"). */
export function luminance(hex: string): number {
  const match = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!match?.[1]) {
    throw new Error(`Not a six-digit hex colour: ${hex}`);
  }
  const channel = (offset: number) => {
    const value = parseInt(match[1]!.slice(offset, offset + 2), 16) / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4);
}

export function contrast(a: string, b: string): number {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (light + 0.05) / (dark + 0.05);
}
