import { useNotifications } from "@/api/account";
import { useCachedMe, useMeAfterPaint } from "@/api/queries";
import { SectionHeader } from "@/components/ui/Headers";
import { signOut } from "@/auth";
import { AccountFrame } from "@/components/shell/AccountFrame";
import { AppearanceChoice } from "@/components/shell/AppearanceChoice";
import { Button } from "@/components/ui/Button";
import { Tile } from "@/components/ui/Tile";
import { Skeleton, SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { useAfterPaint } from "@/lib/after-first-screen";
import { useTitle } from "@/lib/title";
import { ChevronRight } from "lucide-react";
import { Link } from "wouter";
import { nav } from "@site/site";
import { NameRow, ROW } from "./NameRow";
import { NotificationChoices } from "./NotificationChoices";

const METHODS: Record<string, string> = { google: "Google", "email-link": "An email link" };

/**
 * The account: who you are, how you sign in, and what you're told about (ADR 0024). The notification choices come
 * last on purpose, so their loading skeleton, whatever height their rows turn out, moves nothing below it.
 */
export function Account() {
  useTitle("Account");
  // The account and the choices aren't what the screen paints first (what's kept of the account draws its rows, and the rest
  // fills them), so they're asked for once it has painted: a request made before the first paint counts against the speed
  // budgets (ADR 0018).
  const me = useMeAfterPaint();
  const kept = useCachedMe();
  const painted = useAfterPaint();
  const notifications = useNotifications(painted);
  const since = staleSince(me, notifications);
  const methods = me.data?.signInMethods.map((method) => METHODS[method] ?? method).join(", ") ?? "";

  return (
    <AccountFrame title="Account" className="xl:grid xl:grid-cols-5 xl:items-start">
      {since !== null && <StaleNotice updatedAt={since} />}
      <div className="flex min-w-0 flex-col gap-4 xl:col-span-2">
        <Tile as="section" aria-labelledby="you-title" className="flex flex-col gap-1 md:p-6 xl:p-8">
          <SectionHeader id="you-title">
            <span className="md:hidden">You</span>
            <span className="hidden md:inline">Profile</span>
          </SectionHeader>
          {me.data || (kept && !me.isError) ? (
            <div className="flex flex-col divide-y divide-line">
              <NameRow
                name={
                  me.data
                    ? (me.data.displayName ?? me.data.email ?? "You")
                    : (kept?.displayName ?? kept?.emailName ?? "You")
                }
              />
              <Fact
                label="Email"
                value={me.data ? (me.data.email ?? "No email on file") : undefined}
                note={me.data?.emailUndeliverable ? UNDELIVERABLE : undefined}
                action={
                  <Link href="/account/help/" className="shrink-0 text-small underline">
                    To change it, write to us
                  </Link>
                }
              />
              {(!me.data || methods !== "") && (
                <Fact label="Signed in with" value={me.data ? methods : undefined} />
              )}
              {nav.accountSections.flatMap(({ href, label, detail }) =>
                detail
                  ? [
                      <Link key={href} href={href} className={`${ROW} hover:bg-panel-2 md:hidden`}>
                        <div className="flex min-w-0 flex-1 flex-col gap-1">
                          <p className="text-option font-semibold">{label}</p>
                          <p className="text-small text-text-2">{detail}</p>
                        </div>
                        <ChevronRight
                          aria-hidden="true"
                          className="size-5 shrink-0 text-text-2"
                          strokeWidth={1.75}
                        />
                      </Link>,
                    ]
                  : [],
              )}
            </div>
          ) : me.isError ? (
            <ErrorState
              error={me.error}
              what="your account"
              onRetry={() => void me.refetch()}
              retrying={me.isFetching}
            />
          ) : (
            <div
              className="flex flex-col divide-y divide-line"
              role="status"
              aria-label="Loading your account"
            >
              {[0, 1, 2].map((key) => (
                <div key={key} className={ROW}>
                  <div className="flex flex-1 flex-col gap-1">
                    <SkeletonLine size="caption" width="w-16" />
                    <SkeletonLine size="option" width="w-1/2" />
                  </div>
                  {key === 0 && <Skeleton className="h-touch w-16" />}
                </div>
              ))}
            </div>
          )}
          <div className="pt-4 md:hidden">
            <Button variant="ghost" onClick={() => void signOut()}>
              Sign out
            </Button>
          </div>
        </Tile>
        <AppearanceChoice />
      </div>
      <Tile
        as="section"
        aria-labelledby="notifications-title"
        className="flex flex-col gap-1 md:p-6 xl:col-span-3 xl:p-8"
      >
        <SectionHeader id="notifications-title">Notifications</SectionHeader>
        <p className="mb-2 max-w-prose text-small text-text-2">
          Choose what we tell you about. Messages about your money and your safety always stay on.
        </p>
        {notifications.data ? (
          notifications.data.items.length === 0 ? (
            <EmptyState title="Nothing to choose yet">
              The messages we send will be listed here, each with a switch.
            </EmptyState>
          ) : (
            <NotificationChoices items={notifications.data.items} />
          )
        ) : notifications.isError ? (
          <ErrorState
            error={notifications.error}
            what="your notification choices"
            onRetry={() => void notifications.refetch()}
            retrying={notifications.isFetching}
          />
        ) : (
          <div
            className="flex flex-col divide-y divide-line"
            role="status"
            aria-label="Loading your notification choices"
          >
            {[0, 1, 2, 3, 4].map((key) => (
              <div key={key} className={ROW}>
                <div className="flex flex-1 flex-col gap-1">
                  <SkeletonLine size="option" width="w-2/3" />
                </div>
                <Skeleton className="h-7 w-12" />
                <Skeleton className="h-7 w-12" />
              </div>
            ))}
          </div>
        )}
      </Tile>
    </AccountFrame>
  );
}

/** What Account says once mail to the person's address hard-bounced (ADR 0036); it goes when the address changes. */
export const UNDELIVERABLE = "We couldn't email you: check your address";

export function Fact({
  label,
  value,
  note,
  action,
}: {
  label: string;
  /** Undefined while it's on its way: the row keeps its height. */
  value: string | undefined;
  note?: string | undefined;
  action?: React.ReactNode;
}) {
  return (
    <div className={ROW}>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <p className="text-caption text-text-2">{label}</p>
        {value === undefined ? (
          <SkeletonLine size="option" width="w-1/2" />
        ) : (
          <p className="truncate text-option font-semibold" title={value}>
            {value}
          </p>
        )}
        {note !== undefined && <p className="text-small font-semibold text-warn-fg">{note}</p>}
      </div>
      {action}
    </div>
  );
}
