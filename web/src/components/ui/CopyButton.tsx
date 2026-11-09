import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/Button";

/** Copies `text`, and says it did: the one way anything is copied in the app (an API key, a command, a snippet). */
export function CopyButton({ text, label, block = false }: { text: string; label: string; block?: boolean }) {
  const [copied, setCopied] = useState(false);
  return (
    <Button
      variant="ghost"
      block={block}
      onClick={() => void navigator.clipboard.writeText(text).then(() => setCopied(true))}
    >
      {copied ? (
        <Check aria-hidden="true" className="size-4" strokeWidth={2} />
      ) : (
        <Copy aria-hidden="true" className="size-4" strokeWidth={1.75} />
      )}
      {copied ? "Copied" : label}
    </Button>
  );
}
