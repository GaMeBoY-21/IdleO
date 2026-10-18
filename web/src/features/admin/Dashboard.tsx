import { useQuery } from "@tanstack/react-query";
import { Skeleton } from "@/components/ui/Skeleton";
import { RelativeTime } from "@/components/ui/Time";
import { ADMIN, adminApi, adminData, type AdminDashboard, type AdminInstalls } from "./api";
import { AdminPage, Cell, Empty, QueryView, Row, Section, Table } from "./ui";

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
      <Installs />
    </AdminPage>
  );
}

/** Minutes with one decimal, or a dash. */
const minutes = (value: number | null | undefined) => (value == null ? "—" : `${value.toFixed(1)} min`);

/**
 * The Installs card (host experience §6 row 11): how long installs take from opening the setup app to the laptop
 * listed, the last 20 with their clicks and where each stopped, and the failures by reason, over the last 30 days.
 */
function Installs() {
  const installs = useQuery({
    queryKey: [ADMIN, "installs"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/installs")),
    refetchInterval: 60_000,
  });
  return (
    <Section title="Installs">
      <QueryView
        query={installs}
        what="the installs"
        skeleton={<Skeleton className="h-40 w-full" />}
        isEmpty={(data: AdminInstalls) => data.opened === 0}
        empty={
          <Empty title="No installs yet">The setup app's measurements show here once someone opens it.</Empty>
        }
      >
        {(data) => (
          <div className="flex flex-col gap-4">
            <p className="text-body">
              <span className="tabular font-bold">{minutes(data.medianMinutes)}</span> median from opening
              setup to listed, over {data.listed} of {data.opened} installs in the last 30 days.
            </p>
            <Table
              caption="The last 20 installs"
              columns={["Opened", "Minutes", "Clicks", "Where it got to", "GPU", "Windows"]}
            >
              {data.recent.map((install) => (
                <Row key={install.installId}>
                  <Cell>
                    <RelativeTime value={install.openedAt} />
                  </Cell>
                  <Cell className="tabular">{minutes(install.minutes)}</Cell>
                  <Cell className="tabular">{install.clicks}</Cell>
                  <Cell>{install.stoppedAt}</Cell>
                  <Cell>{install.gpuModel ?? "—"}</Cell>
                  <Cell className="tabular">{install.windowsBuild ?? "—"}</Cell>
                </Row>
              ))}
            </Table>
            {data.failures.length > 0 && (
              <Table caption="Failures by reason" columns={["Step", "Reason", "How many"]}>
                {data.failures.map((failure) => (
                  <Row key={`${failure.step}-${failure.reason}`}>
                    <Cell>{failure.step}</Cell>
                    <Cell>{failure.reason}</Cell>
                    <Cell className="tabular">{failure.count}</Cell>
                  </Row>
                ))}
              </Table>
            )}
          </div>
        )}
      </QueryView>
    </Section>
  );
}
