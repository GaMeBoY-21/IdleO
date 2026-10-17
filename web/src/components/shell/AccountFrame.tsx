import { lazy, Suspense, useEffect, type ReactNode } from "react";
import { Page } from "@/components/shell/AppShell";
import { RailColumns } from "@/components/shell/Layout";
import { Skeleton } from "@/components/ui/Skeleton";
import { useWide } from "@/lib/media";

// The rail (the person, the sections, the theme) is drawn only from 768 px, so it is a chunk of its own: a phone's
// first load, and every screen's, doesn't carry it. A laptop asks for it as the screen opens.
const loadRail = () => import("./AccountRail");
const AccountRail = lazy(() => loadRail().then((module) => ({ default: module.AccountRail })));

/**
 * The frame of Account and the screens under it (web/DESIGN.md "Laptop layout"): on a phone a titled page, as every
 * list is; from 768 px a 320 px rail (the person's dark tile, the four sections, appearance and Sign out) and the
 * screen beside it. What the avatar's menu held on a phone is here on a laptop, where the avatar is a link.
 */
export function AccountFrame({
  title,
  children,
  className,
}: {
  /** The screen's heading: shown over the content on a phone, and kept for screen readers beside the rail. */
  title: string;
  children: ReactNode;
  className?: string;
}) {
  const wide = useWide();
  useEffect(() => {
    if (wide) {
      void loadRail();
    }
  }, [wide]);
  if (!wide) {
    return <Page title={title}>{children}</Page>;
  }
  return (
    <Suspense
      fallback={
        <RailColumns
          rail="sm"
          fit={false}
          left={<Skeleton tile className="h-panel" />}
          right={<Skeleton tile className="h-panel" />}
        />
      }
    >
      <AccountRail title={title} className={className}>
        {children}
      </AccountRail>
    </Suspense>
  );
}
