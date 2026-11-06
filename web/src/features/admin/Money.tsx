import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "wouter";
import { Money as Paise } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import { AdminPage, Cell, Empty, Id, Pill, QueryView, Row, Section, Table } from "./ui";
import { ActionButton } from "./permissions";
import { ListBar } from "./Lists";
import { ListScope, useListQuery, useListView } from "./listView";

/** Money (ADR 0014, ADR 0027, ADR 0055): the ledger's checks, each night's and an admin's, and a run now. */
export function Money() {
  return (
    <AdminPage title="Money">
      <ListScope.Provider value="runs">
        <Reconciliation />
      </ListScope.Provider>
      <p className="text-small text-text-2">
        The ledger itself, its accounts, entries and trial balance, is on{" "}
        <Link href="/admin/ledger/" className="underline">
          Ledger
        </Link>
        .
      </p>
    </AdminPage>
  );
}

const RUN_COLUMNS = ["Finished", "Asked by", "Ledger", "Checks", "Mismatches"];

function Reconciliation() {
  const client = useQueryClient();
  const [running, setRunning] = useState(false);
  const view = useListView();
  const runs = useListQuery([ADMIN, "reconciliation", view], "runs", (cursor?: string) =>
    adminData(
      adminApi.GET("/admin/v1/reconciliation", {
        params: { query: { limit: 10, ...view, ...(cursor ? { cursor } : {}) } },
      }),
    ),
  );
  return (
    <Section
      title="The ledger's checks"
      action={
        <ActionButton permission="reconciliation.run" variant="ghost" onClick={() => setRunning(true)}>
          Run the check now
        </ActionButton>
      }
    >
      <ListBar list="wallet-reconciliation" columns={RUN_COLUMNS} tags={false} />
      <QueryView
        query={runs}
        what="the reconciliation runs"
        isEmpty={(list) => list.runs.length === 0}
        empty={
          <Empty title="No runs yet">
            The checks run every night at 02:30 in India; run them now to see where things stand.
          </Empty>
        }
      >
        {(list) => (
          <Table
            caption="Reconciliation runs"
            sorts={{ Finished: "finishedAt", Mismatches: "mismatches" }}
            columns={RUN_COLUMNS}
          >
            {list.runs.map((run) => (
              <Row key={run.id}>
                <Cell>
                  <RelativeTime value={run.finishedAt} />
                </Cell>
                <Cell>{run.trigger}</Cell>
                <Cell>
                  {run.ledgerBalanced ? (
                    <Pill tone="running">balanced</Pill>
                  ) : (
                    <Pill tone="danger">unbalanced</Pill>
                  )}{" "}
                  <span className="tabular text-caption text-text-2">
                    <Paise paise={run.ledgerDebitsPaise} /> debits
                  </span>
                </Cell>
                <Cell>
                  {run.checks.length === 0 ? (
                    <span className="text-text-2">not kept then</span>
                  ) : run.checks.every((check) => check.found === 0) ? (
                    <span className="tabular">all {run.checks.length} pass</span>
                  ) : (
                    <ul className="flex flex-col gap-1">
                      {run.checks
                        .filter((check) => check.found > 0)
                        .map((check) => (
                          <li key={check.position} className="text-danger">
                            {check.name}: <span className="tabular">{check.found}</span>
                          </li>
                        ))}
                    </ul>
                  )}
                </Cell>
                <Cell>
                  {run.mismatches.length === 0 ? (
                    <span className="tabular">0</span>
                  ) : (
                    <ul className="flex flex-col gap-1">
                      {run.mismatches.map((mismatch, index) => (
                        <li key={index} className="text-danger">
                          {mismatch.kind} for <Id value={mismatch.userId} page="user" />:{" "}
                          <Paise paise={mismatch.walletPaise} /> against{" "}
                          <Paise paise={mismatch.expectedPaise} />
                        </li>
                      ))}
                    </ul>
                  )}
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </QueryView>
      <ActionSheet
        open={running}
        onOpenChange={setRunning}
        title="Run the ledger's checks now?"
        change="Every one of the ledger's checks, as the nightly run does: transactions balance, every wallet, hold and lot agrees with its entries, payouts and refunds were posted once, no minute was billed without both witnesses. It reads in a read-only snapshot, keeps what it finds and moves no money."
        confirm="Run the checks"
        onConfirm={async (reason) => {
          await adminData(adminApi.POST("/admin/v1/reconciliation", { body: { reason } }));
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </Section>
  );
}
