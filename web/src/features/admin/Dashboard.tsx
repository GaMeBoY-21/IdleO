import { useQuery } from "@tanstack/react-query";
import { Skeleton } from "@/components/ui/Skeleton";
import { RelativeTime } from "@/components/ui/Time";
import { ADMIN, adminApi, adminData, type AdminDashboard } from "./api";
import { AdminPage, QueryView } from "./ui";

const FIGURES: [keyof AdminDashboard, string, string][] = [
  ["hostsOnline", "Hosts online", "with a connected agent now"],
  ["sessionsRunning", "Sessions running", "including those stopping"],
  ["sessionsToday", "Sessions today", "booked since midnight in India"],
  ["settlementBacklog", "Settlement backlog", "sessions whose settlement is late"],
  ["reconciliationMismatches", "Reconciliation mismatches", "in the latest wallet check"],
  ["reviewQueue", "Review queue", "minutes nobody has decided yet"],
];

/** The pilot at a glance (ADR 0027): each figure counted, when asked, by the module that owns it. */
export function Dashboard() {
  const dashboard = useQuery({
    queryKey: [ADMIN, "dashboard"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/dashboard")),
    refetchInterval: 30_000,
  });
  return (
    <AdminPage title="Dashboard">
      <QueryView
        query={dashboard}
        what="the dashboard"
        skeleton={
          <div
            role="status"
            aria-label="Loading the dashboard"
            className="grid grid-cols-2 gap-3 lg:grid-cols-3"
          >
            {FIGURES.map(([key]) => (
              <Skeleton key={key} className="h-28 w-full" />
            ))}
          </div>
        }
      >
        {(figures) => (
          <>
            <ul aria-label="Figures" className="grid grid-cols-2 gap-3 lg:grid-cols-3">
              {FIGURES.map(([key, label, means]) => {
                const value = figures[key] as number;
                const attention =
                  (key === "reconciliationMismatches" || key === "settlementBacklog") && value > 0;
                return (
                  <li key={key} className="flex h-28 flex-col justify-between rounded-control bg-fill p-4">
                    <span className="text-small font-medium">{label}</span>
                    <span
                      className={
                        attention
                          ? "tabular text-title font-bold text-danger"
                          : "tabular text-title font-bold"
                      }
                    >
                      {value}
                    </span>
                    <span className="text-caption text-text-muted">
                      {attention ? `Needs a look: ${means}` : means}
                    </span>
                  </li>
                );
              })}
            </ul>
            <p className="text-caption text-text-muted">
              <RelativeTime value={figures.at} prefix="Counted" />, again every 30 seconds.
            </p>
          </>
        )}
      </QueryView>
    </AdminPage>
  );
}
