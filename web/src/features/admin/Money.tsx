import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useSearch } from "wouter";
import { Button } from "@/components/ui/Button";
import { Money as Paise } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import {
  AdminPage,
  Cell,
  Empty,
  Filters,
  Id,
  PagedView,
  Pill,
  QueryView,
  Row,
  Section,
  SmallField,
  SmallSelect,
  Table,
} from "./ui";

const ACCOUNTS: [string, string][] = [
  ["renter_wallet", "A renter's wallet"],
  ["host_payable", "A host's payable"],
  ["platform_revenue", "Platform revenue"],
  ["promotions", "Promotions (free credit)"],
  ["gateway_clearing", "Gateway clearing"],
];

/** Money (ADR 0014, ADR 0027): the nightly check's latest runs, a run now, and the ledger by transaction or account. */
export function Money() {
  const owner = new URLSearchParams(useSearch()).get("owner") ?? "";
  return (
    <AdminPage title="Money">
      <Reconciliation />
      <Ledger owner={owner} />
    </AdminPage>
  );
}

function Reconciliation() {
  const client = useQueryClient();
  const [running, setRunning] = useState(false);
  const runs = useQuery({
    queryKey: [ADMIN, "reconciliation"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/reconciliation", { params: { query: { limit: 10 } } })),
  });
  return (
    <Section
      title="Wallet reconciliation"
      action={
        <Button variant="secondary" onClick={() => setRunning(true)}>
          Run the check now
        </Button>
      }
    >
      <QueryView
        query={runs}
        what="the reconciliation runs"
        isEmpty={(list) => list.runs.length === 0}
        empty={
          <Empty title="No runs yet">
            The check runs every night at 02:30 in India; run it now to see where things stand.
          </Empty>
        }
      >
        {(list) => (
          <Table caption="Reconciliation runs" columns={["Finished", "Asked by", "Ledger", "Mismatches"]}>
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
                  <span className="tabular text-caption text-text-muted">
                    <Paise paise={run.ledgerDebitsPaise} /> debits
                  </span>
                </Cell>
                <Cell>
                  {run.mismatches.length === 0 ? (
                    <span className="tabular">0</span>
                  ) : (
                    <ul className="flex flex-col gap-1">
                      {run.mismatches.map((mismatch, index) => (
                        <li key={index} className="text-danger">
                          {mismatch.kind} for <Id value={mismatch.userId} />:{" "}
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
        title="Run the wallet check now?"
        change="It compares every wallet, hold and reservation with the ledger, as the nightly check does, and keeps what it finds. It moves no money."
        confirm="Run the check"
        onConfirm={async (reason) => {
          await adminData(adminApi.POST("/admin/v1/reconciliation", { body: { reason } }));
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </Section>
  );
}

function Ledger({ owner }: { owner: string }) {
  const [form, setForm] = useState({ txn: "", account: owner ? "renter_wallet" : "", owner });
  const [asked, setAsked] = useState(form);
  const set = (field: keyof typeof form) => (value: string) => setForm({ ...form, [field]: value });
  const ready = asked.txn !== "" || asked.account !== "";
  const entries = useInfiniteQuery({
    queryKey: [ADMIN, "ledger", asked],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/ledger", {
          params: {
            query: {
              ...(asked.txn ? { txn: asked.txn } : {}),
              ...(asked.account ? { account: asked.account } : {}),
              ...(asked.owner ? { owner: asked.owner } : {}),
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: ready,
  });
  return (
    <Section title="Ledger">
      <Filters
        onSubmit={() => setAsked({ txn: form.txn.trim(), account: form.account, owner: form.owner.trim() })}
      >
        <SmallField label="Transaction id" value={form.txn} onChange={set("txn")} width="w-72" />
        <SmallSelect
          label="Or an account"
          value={form.account}
          onChange={set("account")}
          options={[["", "None"], ...ACCOUNTS]}
        />
        <SmallField
          label="Its owner (user or host id)"
          value={form.owner}
          onChange={set("owner")}
          width="w-72"
        />
      </Filters>
      {ready ? (
        <PagedView
          query={entries}
          what="the ledger"
          items={(page) => page.entries}
          empty={<Empty title="No entries">Nothing in the ledger matches.</Empty>}
        >
          {(rows) => (
            <Table
              caption="Ledger entries"
              columns={["When", "Transaction", "Account", "Owner", "Amount", "For"]}
            >
              {rows.map((entry) => (
                <Row key={entry.id}>
                  <Cell>
                    <RelativeTime value={entry.createdAt} />
                  </Cell>
                  <Cell>
                    <Id value={entry.txnId} />
                  </Cell>
                  <Cell>{entry.account}</Cell>
                  <Cell>{entry.ownerId ? <Id value={entry.ownerId} /> : "—"}</Cell>
                  <Cell className="tabular">
                    {entry.direction === "debit" ? "−" : "+"}
                    <Paise paise={entry.amountPaise} />
                  </Cell>
                  <Cell>
                    {entry.refType} <Id value={entry.refId} />
                  </Cell>
                </Row>
              ))}
            </Table>
          )}
        </PagedView>
      ) : (
        <Empty title="Look something up">Give a transaction id, or an account and its owner.</Empty>
      )}
    </Section>
  );
}
