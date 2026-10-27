import { AlertTriangle, Loader2 } from "lucide-react";
import { checking, type Image } from "@/api/images";
import { checkLine, IMAGE_STATES, imageSize, shortDigest } from "@/lib/images";
import { cn } from "@/lib/cn";

/**
 * What an image's check found, in the server's words (ADR 0042): while it runs, its progress; then ready with what runs
 * and its warnings (never reasons to block), or blocked and why, or not checked and what to do. Shared by Book's "Use my
 * own image" and Account, My images.
 */
export function ImageFindings({
  image,
  vulnerabilitiesHref,
}: {
  image: Image;
  vulnerabilitiesHref?: string;
}) {
  if (checking(image)) {
    return (
      <p role="status" className="flex items-center gap-2 text-small">
        <Loader2 aria-hidden="true" className="size-4 shrink-0 animate-spin" strokeWidth={2} />
        {checkLine(image)}
      </p>
    );
  }
  const result = image.result;
  return (
    <div className="flex flex-col gap-2 text-small">
      <p className={cn("font-medium", image.state === "ready" ? "text-text" : "text-danger")}>
        {IMAGE_STATES[image.state]}
        {image.detail && <span className="font-normal"> · {image.detail}</span>}
      </p>
      {result && (
        <p className="text-text-2">
          <span className="break-all">{shortDigest(result.digest)}</span> ·{" "}
          {imageSize(result.compressedBytes)} · {result.layers} layers
          {result.cudaNeeded ? ` · CUDA ${result.cudaNeeded}` : ""}
          {result.workingDir ? ` · runs in ${result.workingDir}` : ""}
        </p>
      )}
      {result && result.warnings.length > 0 && (
        <ul className="flex flex-col gap-1.5" aria-label="Warnings">
          {result.warnings.map((warning) => (
            <li key={warning.code + warning.message} className="flex items-start gap-2 text-warn-fg">
              <AlertTriangle aria-hidden="true" className="mt-px size-4 shrink-0" strokeWidth={1.75} />
              <span>
                {warning.message}
                {warning.code === "vulnerabilities" && vulnerabilitiesHref && (
                  <>
                    {" "}
                    <a href={vulnerabilitiesHref} className="underline">
                      See the list
                    </a>
                  </>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
