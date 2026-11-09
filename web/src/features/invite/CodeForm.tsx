import { useState } from "react";
import { useActivate } from "@/api/account";
import type { Me } from "@/api/account";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { formatInviteCode, isCompleteInviteCode } from "@/lib/invite";
import { problemMessage } from "@/lib/problems";

/**
 * The one box for an invite code (ADR 0024, ADR 0058): typed in groups, checked for length before it's sent, and the
 * server's answer in words. The first code activates an account; a later one unlocks the side it hasn't.
 */
export function CodeForm({ onDone, label = "Continue" }: { onDone: (me: Me) => void; label?: string }) {
  const activate = useActivate();
  const [code, setCode] = useState("");
  const [short, setShort] = useState(false);

  const submit = () => {
    if (!isCompleteInviteCode(code)) {
      setShort(true);
      return;
    }
    setShort(false);
    activate.mutate(code, { onSuccess: onDone });
  };

  const error = short
    ? "Enter all 8 letters and numbers of your code."
    : activate.isError
      ? problemMessage(activate.error)
      : undefined;

  return (
    <form
      noValidate
      className="flex flex-col gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <Field
        label="Invite code"
        value={code}
        placeholder="IDLE-XXXX-XXXX"
        autoComplete="off"
        autoCapitalize="characters"
        autoCorrect="off"
        spellCheck={false}
        error={error}
        onChange={(event) => {
          setCode(formatInviteCode(event.target.value));
          setShort(false);
          if (activate.isError) {
            activate.reset();
          }
        }}
      />
      <Button type="submit" large block busy={activate.isPending}>
        {label}
      </Button>
    </form>
  );
}
