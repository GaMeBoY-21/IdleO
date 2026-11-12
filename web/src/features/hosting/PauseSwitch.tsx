import { usePause, useResume, type Host } from "@/api/hosting";
import { Switch } from "@/components/ui/Inputs";
import { problemMessage } from "@/lib/problems";

/**
 * Whether a laptop takes new sessions, switched on its card (web/DESIGN.md "Hosting"): off pauses it until it's switched
 * back on, on resumes it. A session it's running always finishes; the choice of how long a pause lasts is on the
 * laptop's own screen (PauseControl).
 */
export function PauseSwitch({ host, className }: { host: Host; className?: string }) {
  const pause = usePause(host.id);
  const resume = useResume(host.id);
  const busy = pause.isPending || resume.isPending;
  const error = pause.error ?? resume.error;
  return (
    <Switch
      label={`${host.name} takes new sessions`}
      checked={!host.paused}
      disabled={busy}
      aria-busy={busy || undefined}
      error={error ? problemMessage(error) : undefined}
      hint="A session it's running always finishes."
      className={className}
      onChange={(event) => {
        if (event.target.checked) {
          resume.mutate();
        } else {
          pause.mutate(null);
        }
      }}
    />
  );
}
