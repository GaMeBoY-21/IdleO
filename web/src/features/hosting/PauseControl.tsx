import { useState } from "react";
import { usePause, useResume, type Host } from "@/api/hosting";
import { Tile } from "@/components/ui/Tile";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { RadioGroup } from "@/components/ui/Inputs";
import { useNow } from "@/lib/clock";
import { formatPauseEnd, pauseChoices } from "@/lib/hosting";
import { problemMessage } from "@/lib/problems";

/**
 * "Pause hosting" (docs/design/hosting/machine-phone.html): a choice of how long, then Pause. A paused machine gets no
 * new sessions and one it's running finishes first; "Resume" ends the pause. The choices are opened in place, not in
 * a dialog (web/DESIGN.md principle 5). It sits at the foot of the machine screen, as the phone action bar does.
 */
export function PauseControl({ host }: { host: Host }) {
  const now = useNow("half-minute");
  const pause = usePause(host.id);
  const resume = useResume(host.id);
  const [choosing, setChoosing] = useState(false);
  const [choice, setChoice] = useState("resume");
  const choices = pauseChoices(now);
  const picked = choices.find((candidate) => candidate.id === choice) ?? choices[0]!;
  const error = pause.error ?? resume.error;

  return (
    <Tile as="section" aria-label="Pause hosting" className="flex flex-col gap-3">
      {error && <Banner tone="danger">{problemMessage(error)}</Banner>}
      {host.paused ? (
        <>
          <p className="text-small text-text-muted">
            {host.pausedUntil
              ? `Paused until ${formatPauseEnd(host.pausedUntil, now)}. A session that's running finishes first.`
              : "Paused until you resume. A session that's running finishes first."}
          </p>
          <Button large block busy={resume.isPending} onClick={() => resume.mutate()}>
            Resume
          </Button>
        </>
      ) : choosing ? (
        <>
          <RadioGroup
            legend="Pause for"
            quiet
            options={choices.map(({ id, label }) => ({ value: id, label }))}
            inputProps={(value) => ({
              name: "pause",
              checked: choice === value,
              onChange: () => setChoice(value),
            })}
          />
          <div className="flex flex-col gap-2 md:flex-row-reverse">
            <Button
              large
              block
              variant="secondary"
              busy={pause.isPending}
              onClick={() =>
                pause.mutate(picked.until, {
                  onSuccess: () => setChoosing(false),
                })
              }
            >
              Pause hosting
            </Button>
            <Button
              large
              block
              variant="secondary"
              onClick={() => {
                setChoosing(false);
                pause.reset();
              }}
            >
              Keep hosting
            </Button>
          </div>
        </>
      ) : (
        <>
          <Button large block variant="secondary" onClick={() => setChoosing(true)}>
            Pause hosting
          </Button>
          <p className="text-center text-small text-text-muted">
            The running session finishes first. No new ones start until you resume.
          </p>
        </>
      )}
    </Tile>
  );
}
