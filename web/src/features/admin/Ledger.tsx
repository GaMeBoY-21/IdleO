import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useSearch } from "wouter";
import { Money as Paise } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { ADMIN, adminApi, adminData } from "./api";
import { word } from "./words";
import { recordHref } from "./links";
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
  Table,
} from "./ui";
import { ListBar } from "./Lists";
import { useListView } from "./listView";

/** A moment as the API takes it, from a date (India's midnight at its end) or left empty for now. */
export function momentOf(date: string): string | undefined {
  if (date.trim() === "") {
    return undefined;
  }
  // The end of that day in India: 18:30 UTC.
  return `${date.trim()}T18:30:00Z`;
}

/** Where an entry list for these filters is, on this screen. */
export function ledgerHref(filters: Record<string, string | undefined>): string {
  const query = new URLSearchParams(
    Object.entries(filters).filter(
      (entry): entry is [string, string] => entry[1] !== undefined && entry[1] !== "",
    ),
  );
  return `/admin/ledger/?${query.toString()}`;
}

const ACCOUNT_COLUMNS = ["Account", "Type", "What it holds", "Balance"];
const TRIAL_COLUMNS = ["Account", "Debit", "Credit"];
const ENTRY_COLUMNS = ["When", "Transaction", "Account", "Owner", "Amount", "For"];
const LEG_COLUMNS = ["Account", "Owner", "Debit", "Credit"];

/**
 * The ledger (ADR 0055): the chart of accounts with each balance at a moment, for the platform or one owner; the trial
 * balance; the entries, filtered and exported; and one transaction with every leg and where it came from.
 */
export function Ledger() {
  const search = new URLSearchParams(useSearch());
  const txn = search.get("txn") ?? "";
  return (
    <AdminPage title="Ledger">
      {txn !== "" && <Transaction txn={txn} />}
      <Chart />
      <TrialBalance />
      <Entries
        initial={{
          account: search.get("account") ?? "",
          owner: search.get("owner") ?? "",
          refType: search.get("refType") ?? "",
          from: search.get("from") ?? "",
          to: search.get("to") ?? "",
          txn: "",
        }}
      />
    </AdminPage>
  );
}

function Chart() {
  const [form, setForm] = useState({ at: "", owner: "" });
  const [asked, setAsked] = useState(form);
  const accounts = useQuery({
    queryKey: [ADMIN, "ledger-accounts", asked],
    queryFn: () =>
      adminData(
        adminApi.GET("/admin/v1/ledger/accounts", {
          params: {
            query: {
              ...(momentOf(asked.at) ? { at: momentOf(asked.at) } : {}),
              ...(asked.owner ? { owner: asked.owner } : {}),
            },
          },
        }),
      ),
  });
  return (
    <Section title="Chart of accounts">
      <Filters onSubmit={() => setAsked({ at: form.at.trim(), owner: form.owner.trim() })}>
        <SmallField
          label="At the end of (YYYY-MM-DD, empty for now)"
          value={form.at}
          onChange={(at) => setForm({ ...form, at })}
          width="w-64"
        />
        <SmallField
          label="For one owner (user or host id)"
          value={form.owner}
          onChange={(owner) => setForm({ ...form, owner })}
          width="w-72"
        />
      </Filters>
      <QueryView query={accounts} what="the chart of accounts">
        {(chart) => (
          <Table caption="Chart of accounts" columns={ACCOUNT_COLUMNS}>
            {chart.accounts.map((account) => (
              <Row key={account.account}>
                <Cell>
                  <Link
                    href={ledgerHref({ account: account.account, owner: chart.owner ?? undefined })}
                    className="underline"
                  >
                    {word(account.account)}
                  </Link>
                </Cell>
                <Cell>
                  {account.type}
                  <span className="block text-caption text-text-2">
                    raised by {account.normalSide}s{account.owned ? ", owned" : ""}
                  </span>
                </Cell>
                <Cell className="max-w-prose text-small">{account.description}</Cell>
                <Cell className="tabular">
                  <Paise paise={account.balancePaise} />
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </QueryView>
    </Section>
  );
}

function TrialBalance() {
  const [form, setForm] = useState("");
  const [asked, setAsked] = useState("");
  const trial = useQuery({
    queryKey: [ADMIN, "trial-balance", asked],
    queryFn: () =>
      adminData(
        adminApi.GET("/admin/v1/ledger/trial-balance", {
          params: { query: momentOf(asked) ? { at: momentOf(asked) } : {} },
        }),
      ),
  });
  return (
    <Section title="Trial balance">
      <Filters onSubmit={() => setAsked(form.trim())}>
        <SmallField
          label="At the end of (YYYY-MM-DD, empty for now)"
          value={form}
          onChange={setForm}
          width="w-64"
        />
      </Filters>
      <QueryView query={trial} what="the trial balance">
        {(balance) => (
          <>
            {balance.balanced ? (
              <p className="text-small text-text-2">
                <Pill tone="running">balanced</Pill> Debits <Paise paise={balance.debitsPaise} /> equal
                credits <Paise paise={balance.creditsPaise} />.
              </p>
            ) : (
              <p role="alert" className="rounded-control bg-danger-soft p-3 text-small text-danger">
                The ledger doesn&apos;t balance: debits <Paise paise={balance.debitsPaise} />, credits{" "}
                <Paise paise={balance.creditsPaise} />. This pages the on-call admin; see the runbook&apos;s
                &ldquo;Ledger unbalanced&rdquo;.
              </p>
            )}
            <Table caption="Trial balance" columns={TRIAL_COLUMNS}>
              {balance.accounts
                .filter((row) => row.debitPaise !== 0 || row.creditPaise !== 0)
                .map((row) => (
                  <Row key={row.account}>
                    <Cell>{word(row.account)}</Cell>
                    <Cell className="tabular">{row.debitPaise ? <Paise paise={row.debitPaise} /> : ""}</Cell>
                    <Cell className="tabular">
                      {row.creditPaise ? <Paise paise={row.creditPaise} /> : ""}
                    </Cell>
                  </Row>
                ))}
            </Table>
          </>
        )}
      </QueryView>
    </Section>
  );
}

function Transaction({ txn }: { txn: string }) {
  const found = useQuery({
    queryKey: [ADMIN, "ledger-transaction", txn],
    queryFn: () =>
      adminData(adminApi.GET("/admin/v1/ledger/transactions/{id}", { params: { path: { id: txn } } })),
  });
  return (
    <Section title="Transaction">
      <QueryView query={found} what="the transaction">
        {(transaction) => (
          <>
            <p className="text-small text-text-2">
              <Id value={transaction.txnId} /> <RelativeTime value={transaction.createdAt} />, from{" "}
              {transaction.source.page && transaction.source.pageId ? (
                <Link
                  href={recordHref(transaction.source.page, transaction.source.pageId)}
                  className="underline"
                >
                  {word(transaction.source.refType)}
                </Link>
              ) : (
                transaction.source.refType
              )}{" "}
              <Id value={transaction.source.refId} />
            </p>
            <Table caption="Every leg of the transaction" columns={LEG_COLUMNS}>
              {transaction.legs.map((leg) => (
                <Row key={leg.id}>
                  <Cell>{word(leg.account)}</Cell>
                  <Cell>{leg.ownerId ? <Id value={leg.ownerId} page="user" /> : "—"}</Cell>
                  <Cell className="tabular">
                    {leg.direction === "debit" ? <Paise paise={leg.amountPaise} /> : ""}
                  </Cell>
                  <Cell className="tabular">
                    {leg.direction === "credit" ? <Paise paise={leg.amountPaise} /> : ""}
                  </Cell>
                </Row>
              ))}
            </Table>
          </>
        )}
      </QueryView>
    </Section>
  );
}

type EntryFilters = {
  txn: string;
  account: string;
  owner: string;
  refType: string;
  from: string;
  to: string;
};

function Entries({ initial }: { initial: EntryFilters }) {
  const [form, setForm] = useState(initial);
  const [asked, setAsked] = useState(initial);
  const set = (field: keyof EntryFilters) => (value: string) => setForm({ ...form, [field]: value });
  const ready = asked.txn !== "" || asked.account !== "";
  const view = useListView();
  const query = Object.fromEntries(Object.entries(asked).filter(([, value]) => value !== ""));
  const entries = useInfiniteQuery({
    queryKey: [ADMIN, "ledger", asked, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/ledger", {
          params: { query: { ...query, ...view, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: ready,
  });
  return (
    <Section title="Entries">
      <Filters
        onSubmit={() =>
          setAsked(Object.fromEntries(Object.entries(form).map(([k, v]) => [k, v.trim()])) as EntryFilters)
        }
      >
        <SmallField label="Transaction id" value={form.txn} onChange={set("txn")} width="w-72" />
        <SmallField label="Or an account" value={form.account} onChange={set("account")} />
        <SmallField
          label="Its owner (user or host id)"
          value={form.owner}
          onChange={set("owner")}
          width="w-72"
        />
        <SmallField label="Cause (ref type)" value={form.refType} onChange={set("refType")} />
        <SmallField label="From (ISO moment)" value={form.from} onChange={set("from")} />
        <SmallField label="To (ISO moment, not included)" value={form.to} onChange={set("to")} />
      </Filters>
      {ready && <ListBar list="ledger" tags={false} extra={query} />}
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
              list="ledger"
              sorts={{ When: "createdAt", Amount: "amount" }}
              columns={ENTRY_COLUMNS}
            >
              {rows.map((entry) => (
                <Row key={entry.id}>
                  <Cell>
                    <RelativeTime value={entry.createdAt} />
                  </Cell>
                  <Cell>
                    <Link href={ledgerHref({ txn: entry.txnId })} className="underline">
                      <Id value={entry.txnId} />
                    </Link>
                  </Cell>
                  <Cell>{word(entry.account)}</Cell>
                  <Cell>{entry.ownerId ? <Id value={entry.ownerId} page="user" /> : "—"}</Cell>
                  <Cell className="tabular">
                    {entry.direction === "debit" ? "−" : "+"}
                    <Paise paise={entry.amountPaise} />
                  </Cell>
                  <Cell>
                    {word(entry.refType)} <Id value={entry.refId} />
                  </Cell>
                </Row>
              ))}
            </Table>
          )}
        </PagedView>
      ) : (
        <Empty title="Look something up">
          Open an account above, or give a transaction id, or an account and its owner.
        </Empty>
      )}
    </Section>
  );
}
