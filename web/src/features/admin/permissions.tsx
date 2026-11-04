import { createContext, useContext, useId, type ComponentProps, type ReactNode } from "react";
import { Button } from "@/components/ui/Button";

/**
 * What this admin may do, from `GET /admin/v1/session` (ADR 0051). It only decides what the console shows: hidden
 * sections and disabled actions that say which permission is missing. The server stays the only judge of every call.
 */
const Permissions = createContext<ReadonlySet<string>>(new Set());

export function PermissionsProvider({
  permissions,
  children,
}: {
  permissions: readonly string[];
  children: ReactNode;
}) {
  return <Permissions.Provider value={new Set(permissions)}>{children}</Permissions.Provider>;
}

/** Whether this admin holds the permission. */
export function useCan(): (permission: string) => boolean {
  const held = useContext(Permissions);
  return (permission) => held.has(permission);
}

/** An action's button: disabled, saying why, for an admin without its permission. */
export function ActionButton({
  permission,
  disabled,
  title,
  children,
  ...button
}: ComponentProps<typeof Button> & { permission: string }) {
  const can = useCan();
  const id = useId();
  if (can(permission)) {
    return (
      <Button disabled={disabled} title={title} {...button}>
        {children}
      </Button>
    );
  }
  const why = `You need the ${permission} permission for this.`;
  return (
    <>
      <Button disabled title={why} aria-describedby={id} {...button}>
        {children}
      </Button>
      <span id={id} className="sr-only">
        {why}
      </span>
    </>
  );
}

/** A section's permission: an admin without it sees why, instead of a refused request. */
export function NeedsPermission({ permission, children }: { permission: string; children: ReactNode }) {
  const can = useCan();
  if (can(permission)) {
    return <>{children}</>;
  }
  return (
    <div className="flex flex-col gap-2 rounded-inner border border-line p-6">
      <h1 className="text-section">You can&apos;t open this section</h1>
      <p className="text-small text-text-2">
        It needs the {permission} permission, which your roles don&apos;t include. An Owner can grant a role
        that has it.
      </p>
    </div>
  );
}
