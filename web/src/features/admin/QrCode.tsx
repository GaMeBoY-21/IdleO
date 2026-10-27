import { useMemo } from "react";
import { encode } from "uqr";

/**
 * The authenticator secret as a QR code (ADR 0027), drawn here as one SVG path, dark on light in both themes (the log panel's fixed pair), with
 * the four-module quiet zone scanners need. Nothing is fetched: the secret never leaves the page.
 */
export function QrCode({ value, label }: { value: string; label: string }) {
  const { size, path } = useMemo(() => {
    const qr = encode(value, { ecc: "M", border: 0 });
    let d = "";
    qr.data.forEach((row, y) =>
      row.forEach((on, x) => {
        if (on) {
          d += `M${x + 4} ${y + 4}h1v1h-1z`;
        }
      }),
    );
    return { size: qr.size + 8, path: d };
  }, [value]);
  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`0 0 ${size} ${size}`}
      className="size-56 rounded-control bg-on-night text-night"
      shapeRendering="crispEdges"
    >
      <path d={path} fill="currentColor" />
    </svg>
  );
}
