import { useEffect, useRef, useState } from "react";
import { data } from "@/api/client";
import { Button } from "@/components/ui/Button";
import { Sheet } from "./Sheet";
import { Problem } from "./ui";
import { adminApi, whenStepUpNeeded } from "./api";
import { CodeField } from "./CodeField";
import { isRecoveryCode, isTotp, recoveryDigits, totpDigits } from "./rules";
import { passkeysAvailable, signWithPasskey } from "./webauthn";

/**
 * Asks for a factor in place (ADR 0051): a sensitive action refused with `step-up-required` waits here, then goes again
 * by itself. A passkey first if the admin has one, or a code.
 */
export function StepUp({ passkeys }: { passkeys: number }) {
  const waiting = useRef<{ resolve: () => void; reject: (reason: Error) => void } | null>(null);
  const [open, setOpen] = useState(false);
  const usesPasskey = passkeys > 0 && passkeysAvailable();
  const [kind, setKind] = useState<"passkey" | "totp" | "recovery">(usesPasskey ? "passkey" : "totp");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    whenStepUpNeeded(
      () =>
        new Promise<void>((resolve, reject) => {
          waiting.current = { resolve, reject };
          setKind(usesPasskey ? "passkey" : "totp");
          setCode("");
          setError(null);
          setOpen(true);
        }),
    );
    return () => whenStepUpNeeded(null);
  }, [usesPasskey]);

  const finish = () => {
    setOpen(false);
    waiting.current?.resolve();
    waiting.current = null;
  };

  const decline = () => {
    setOpen(false);
    waiting.current?.reject(new Error("declined"));
    waiting.current = null;
  };

  const send = async (factor: () => Promise<Parameters<typeof post>[0]>) => {
    setBusy(true);
    setError(null);
    try {
      await post(await factor());
      finish();
    } catch (failed) {
      setError(failed);
      setCode("");
    } finally {
      setBusy(false);
    }
  };

  const valid = kind === "totp" ? isTotp(code) : kind === "recovery" ? isRecoveryCode(code) : true;

  return (
    <Sheet
      above
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          decline();
        }
      }}
      title="Confirm it's you"
      description={
        <p className="text-small text-text-2">
          This action needs a passkey or a code from the last five minutes. It goes ahead by itself once you
          have.
        </p>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (kind === "passkey") {
            void send(async () => ({ passkey: await signWithPasskey(await passkeyOptions()) }));
          } else if (kind === "totp" && valid) {
            void send(() => Promise.resolve({ totp: totpDigits(code) }));
          } else if (valid) {
            void send(() => Promise.resolve({ recoveryCode: recoveryDigits(code) }));
          }
        }}
      >
        {kind !== "passkey" && (
          <CodeField
            kind={kind}
            value={code}
            onChange={setCode}
            error={code !== "" && !valid ? "That isn't a whole code yet." : null}
          />
        )}
        <Problem error={error} />
        <div className="flex flex-col-reverse gap-2 md:flex-row md:justify-end">
          <Button variant="ghost" onClick={decline}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            {kind === "passkey" ? "Use my passkey" : "Confirm"}
          </Button>
        </div>
        <div className="flex flex-wrap gap-2">
          {usesPasskey && kind !== "passkey" && (
            <Button variant="quiet" onClick={() => setKind("passkey")}>
              Use a passkey
            </Button>
          )}
          {kind !== "totp" && (
            <Button
              variant="quiet"
              onClick={() => {
                setKind("totp");
                setCode("");
              }}
            >
              Use a code from your app
            </Button>
          )}
          {kind !== "recovery" && (
            <Button
              variant="quiet"
              onClick={() => {
                setKind("recovery");
                setCode("");
              }}
            >
              Use a recovery code
            </Button>
          )}
        </div>
      </form>
    </Sheet>
  );
}

async function passkeyOptions() {
  return data(adminApi.POST("/admin/v1/session/passkey-options"));
}

async function post(body: {
  totp?: string;
  recoveryCode?: string;
  passkey?: Awaited<ReturnType<typeof signWithPasskey>>;
}) {
  return data(adminApi.POST("/admin/v1/session/step-up", { body }));
}
