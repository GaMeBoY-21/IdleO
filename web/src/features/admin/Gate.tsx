import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, useSyncExternalStore, type ReactNode } from "react";
import { data } from "@/api/client";
import { ApiError } from "@/api/errors";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Skeleton";
import { ErrorState } from "@/components/ui/States";
import { NotFound } from "@/pages/NotFound";
import { useTitle } from "@/lib/title";
import { refusal } from "./ActionSheet";
import { ADMIN, adminApi, adminSession, whenNotFound } from "./api";
import { CodeField } from "./CodeField";
import { QrCode } from "./QrCode";
import { isRecoveryCode, isTotp, recoveryDigits, totpDigits } from "./rules";

/** Where the admin stands, asked with whatever admin session this page holds. */
export const sessionKey = [ADMIN, "session"] as const;

export function useAdminToken(): string | null {
  return useSyncExternalStore(adminSession.subscribe, adminSession.token, () => null);
}

/**
 * The console's front door (ADR 0027). Anyone who isn't an admin gets the same "can't find that page" as any wrong
 * address, because the server answers them 404. An admin sets up their authenticator once, then gives a code from it
 * (or a recovery code) to start an admin session, which lives only in this page's memory.
 */
export function Gate({ children }: { children: ReactNode }) {
  const token = useAdminToken();
  const client = useQueryClient();
  useEffect(() => whenNotFound(() => void client.invalidateQueries({ queryKey: sessionKey })), [client]);
  const status = useQuery({
    queryKey: [...sessionKey, token !== null],
    queryFn: () => data(adminApi.GET("/admin/v1/session")),
    staleTime: 60_000,
  });
  if (status.isPending) {
    return <GateSkeleton />;
  }
  if (status.error instanceof ApiError && status.error.status === 404) {
    return <NotFound />;
  }
  if (status.data === undefined) {
    return (
      <GateFrame title="Admin">
        <ErrorState
          error={status.error}
          what="the admin console"
          onRetry={() => void status.refetch()}
          retrying={status.isFetching}
        />
      </GateFrame>
    );
  }
  if (!status.data.enrolled) {
    return <Enrol />;
  }
  if (!status.data.active) {
    return <Check />;
  }
  return <>{children}</>;
}

function GateFrame({ title, children }: { title: string; children: ReactNode }) {
  useTitle(`${title} · Admin`);
  return (
    <main className="mx-auto flex min-h-dvh max-w-narrow flex-col justify-center gap-6 px-4 py-12">
      <h1 className="text-title font-bold">{title}</h1>
      {children}
    </main>
  );
}

function GateSkeleton() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-narrow flex-col justify-center gap-6 px-4 py-12">
      <div role="status" aria-label="Loading the admin console" className="flex flex-col gap-6">
        <Skeleton className="h-8.5 w-48" />
        <Skeleton className="h-14 w-full" />
        <Skeleton className="h-touch w-32" />
      </div>
    </main>
  );
}

/** Starts the admin session the server just gave, and opens the console. */
function useStart() {
  const client = useQueryClient();
  return (token: string) => {
    adminSession.set(token);
    void client.invalidateQueries({ queryKey: sessionKey });
  };
}

/**
 * Setting up the authenticator, at an admin's first visit: the secret once, as a QR code and as text, confirmed with
 * a code from it; then the ten recovery codes, once, before the console opens.
 */
function Enrol() {
  const start = useStart();
  const secret = useQuery({
    queryKey: [ADMIN, "second-factor"],
    queryFn: () => data(adminApi.POST("/admin/v1/second-factor")),
    staleTime: Infinity,
    gcTime: 0,
    retry: false,
  });
  const [code, setCode] = useState("");
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [saved, setSaved] = useState<{ token: string; recoveryCodes: string[] } | null>(null);

  if (saved !== null) {
    return (
      <GateFrame title="Save your recovery codes">
        <p className="text-small text-text-muted">
          Each works once, instead of a code, if you lose your phone. You won&apos;t see them again: copy them
          somewhere safe now.
        </p>
        <ol
          className="tabular grid grid-cols-2 gap-2 rounded-control bg-fill p-4 text-body"
          aria-label="Recovery codes"
        >
          {saved.recoveryCodes.map((recovery) => (
            <li key={recovery}>{recovery}</li>
          ))}
        </ol>
        <Button large block onClick={() => start(saved.token)}>
          I&apos;ve saved them
        </Button>
      </GateFrame>
    );
  }

  const confirm = async () => {
    setTouched(true);
    if (!isTotp(code)) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const confirmed = await data(
        adminApi.POST("/admin/v1/second-factor/confirm", { body: { totp: totpDigits(code) } }),
      );
      setSaved({ token: confirmed.token, recoveryCodes: confirmed.recoveryCodes });
    } catch (failed) {
      setError(failed);
      setCode("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <GateFrame title="Set up your authenticator">
      <p className="text-small text-text-muted">
        The admin console needs a second factor. Scan this with an authenticator app, then type the code it
        shows.
      </p>
      {secret.data ? (
        <div className="flex flex-col items-start gap-3">
          <QrCode value={secret.data.otpauthUri} label="QR code of your authenticator secret" />
          <p className="text-caption text-text-muted">Or type this secret into the app:</p>
          <p className="tabular text-small font-medium break-all">{secret.data.secret}</p>
        </div>
      ) : secret.isPending ? (
        <Skeleton className="size-56" />
      ) : (
        <ErrorState
          error={secret.error}
          what="your authenticator secret"
          onRetry={() => void secret.refetch()}
          retrying={secret.isFetching}
        />
      )}
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          void confirm();
        }}
      >
        <CodeField
          kind="totp"
          value={code}
          onChange={setCode}
          error={touched && !isTotp(code) ? "Type the 6 digits your app shows." : null}
        />
        {error !== null && <Banner tone="danger">{refusal(error)}</Banner>}
        <Button type="submit" large block busy={busy} disabled={secret.data === undefined}>
          Confirm
        </Button>
      </form>
    </GateFrame>
  );
}

/** The code check that starts an admin session: a code from the authenticator, or a recovery code. */
function Check() {
  const start = useStart();
  const [kind, setKind] = useState<"totp" | "recovery">("totp");
  const [code, setCode] = useState("");
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const valid = kind === "totp" ? isTotp(code) : isRecoveryCode(code);

  const submit = async () => {
    setTouched(true);
    if (!valid) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const body = kind === "totp" ? { totp: totpDigits(code) } : { recoveryCode: recoveryDigits(code) };
      const session = await data(adminApi.POST("/admin/v1/session", { body }));
      start(session.token);
    } catch (failed) {
      setError(failed);
      setCode("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <GateFrame title="Admin sign-in">
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
        <CodeField
          kind={kind}
          value={code}
          onChange={setCode}
          error={
            touched && !valid
              ? kind === "totp"
                ? "Type the 6 digits your app shows."
                : "A recovery code is 10 letters and digits."
              : null
          }
        />
        {error !== null && <Banner tone="danger">{refusal(error)}</Banner>}
        <Button type="submit" large block busy={busy}>
          Continue
        </Button>
        <Button
          variant="quiet"
          onClick={() => {
            setKind(kind === "totp" ? "recovery" : "totp");
            setCode("");
            setTouched(false);
            setError(null);
          }}
        >
          {kind === "totp" ? "Use a recovery code instead" : "Use a code from your app"}
        </Button>
      </form>
    </GateFrame>
  );
}
