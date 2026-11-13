import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { StatusChip } from "@/components/ui/StatusChip";
import { Link } from "wouter";
import { Money as Paise } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import type { components } from "@/api/schema.gen";
import { ActionSheet, SheetProblem } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import { ledgerHref } from "./Ledger";
import { AdminPage, Cell, Empty, PagedView, Row, Section, SmallField, SmallSelect, Table } from "./ui";
import { ActionButton } from "./permissions";
import { ListBar } from "./Lists";
import { ListScope, useListView } from "./listView";

type Item = components["schemas"]["AdminSettlementItem"];

/** What each kind of settlement disagreement means (docs/runbooks/settlement-mismatch.md). */
export const SETTLEMENT_MATCH_WORDS: Record<string, string> = {
  matched: "Matches",
  missing_ours: "Razorpay settled it; we have no record",
  missing_theirs: "We credited it; no settlement carried it",
  amount: "A different amount",
  unexpected_fee: "A fee above our rate",
};

const SETTLEMENT_COLUMNS = ["Settled", "Settlement", "To the bank", "Fees", "GST on fees", "State"];
const ITEM_COLUMNS = ["Found", "Item", "Razorpay's", "Ours", "Fee", "What disagrees", ""];

/**
 * Settlements (ADR 0055): what Razorpay settled to our bank, each settlement matched, partly or unmatched against the
 * ledger, and each item that disagrees, resolved by an approved adjustment that names it or as a known difference.
 */
export function Settlements() {
  return (
    <AdminPage title="Settlements">
      <ListScope.Provider value="items">
        <Items />
      </ListScope.Provider>
      <ListScope.Provider value="settlements">
        <SettlementList />
      </ListScope.Provider>
    </AdminPage>
  );
}

function SettlementList() {
  const client = useQueryClient();
  const view = useListView();
  const [fetching, setFetching] = useState(false);
  const [day, setDay] = useState("");
  const settlements = useInfiniteQuery({
    queryKey: [ADMIN, "settlements", view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/settlements", {
          params: { query: { ...view, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <Section
      title="Settlements"
      action={
        <ActionButton permission="reconciliation.run" variant="ghost" onClick={() => setFetching(true)}>
          Fetch a day now
        </ActionButton>
      }
    >
      <ListBar list="settlements" tags={false} />
      <PagedView
        query={settlements}
        what="the settlements"
        items={(page) => page.settlements}
        empty={
          <Empty title="No settlements yet">
            Razorpay&apos;s settlements are fetched every night at 04:30 in India; fetch a day now to see one
            sooner.
          </Empty>
        }
      >
        {(rows) => (
          <Table
            caption="Settlements"
            list="settlements"
            sorts={{ Settled: "settledAt" }}
            columns={SETTLEMENT_COLUMNS}
          >
            {rows.map((settlement) => (
              <Row key={settlement.id}>
                <Cell>
                  <RelativeTime value={settlement.settledAt} />
                </Cell>
                <Cell>
                  {settlement.txnId ? (
                    <Link href={ledgerHref({ txn: settlement.txnId })} className="underline">
                      {settlement.id}
                    </Link>
                  ) : (
                    settlement.id
                  )}
                  <span className="block text-caption text-ink-subtle">
                    {settlement.status}
                    {settlement.utr ? `, UTR ${settlement.utr}` : ""}
                  </span>
                </Cell>
                <Cell className="tabular">
                  <Paise paise={settlement.amountPaise} />
                </Cell>
                <Cell className="tabular">
                  <Paise paise={settlement.feesPaise} />
                </Cell>
                <Cell className="tabular">
                  <Paise paise={settlement.taxPaise} />
                </Cell>
                <Cell>
                  <StatusChip size="sm" domain="settlement" state={settlement.state} />
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </PagedView>
      <ActionSheet
        open={fetching}
        onOpenChange={setFetching}
        title="Fetch a day's settlements now?"
        change="Razorpay's settlements of the day and every item they carried, matched with the ledger, as the nightly run does. Fetching a day again adds nothing twice."
        confirm="Fetch"
        extra={<SmallField label="Day (YYYY-MM-DD, empty for today)" value={day} onChange={setDay} />}
        onConfirm={async (reason) => {
          await adminData(
            adminApi.POST("/admin/v1/settlements/fetch", {
              body: { day: day.trim() === "" ? null : day.trim(), reason },
            }),
          );
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </Section>
  );
}

function Items() {
  const client = useQueryClient();
  const view = useListView();
  const [state, setState] = useState("open");
  const [resolving, setResolving] = useState<Item | null>(null);
  const [resolution, setResolution] = useState("known_difference");
  const [adjustment, setAdjustment] = useState("");
  const items = useInfiniteQuery({
    queryKey: [ADMIN, "settlement-items", state, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/settlement-items", {
          params: {
            query: { ...(state ? { state } : {}), ...view, ...(pageParam ? { cursor: pageParam } : {}) },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <Section title="What disagrees">
      <div className="flex">
        <SmallSelect
          label="Show"
          value={state}
          onChange={setState}
          options={[
            ["open", "Open"],
            ["resolved", "Resolved"],
            ["", "Every item"],
          ]}
        />
      </div>
      <ListBar list="settlement-items" tags={false} extra={state ? { state } : {}} />
      <PagedView
        query={items}
        what="the settlement items"
        items={(page) => page.items}
        empty={
          <Empty title="Nothing disagrees">
            Every item of every settlement matches the ledger, or is resolved.
          </Empty>
        }
      >
        {(rows) => (
          <Table
            caption="Settlement items"
            list="settlement-items"
            sorts={{ Found: "foundAt" }}
            columns={ITEM_COLUMNS}
          >
            {rows.map((item) => (
              <Row key={item.id}>
                <Cell>
                  <RelativeTime value={item.foundAt} />
                </Cell>
                <Cell>
                  {item.type} {item.entityId}
                  <span className="block text-caption text-ink-subtle">
                    {item.settlementId ?? "in no settlement"}
                  </span>
                </Cell>
                <Cell className="tabular">
                  <Paise paise={item.creditPaise - item.debitPaise} />
                </Cell>
                <Cell className="tabular">
                  {item.ourPaise != null ? <Paise paise={item.ourPaise} /> : "—"}
                </Cell>
                <Cell className="tabular">
                  <Paise paise={item.feePaise + item.taxPaise} />
                </Cell>
                <Cell>
                  {SETTLEMENT_MATCH_WORDS[item.match] ?? item.match}
                  {item.state === "resolved" && (
                    <span className="block text-caption text-ink-subtle">
                      {item.resolution === "settled_later"
                        ? "carried by a later settlement"
                        : `${item.resolution?.replace("_", " ")}: ${item.reason ?? ""}`}
                    </span>
                  )}
                </Cell>
                <Cell>
                  {item.state === "open" && (
                    <ActionButton
                      permission="recon.resolve"
                      variant="ghost"
                      onClick={() => {
                        setResolution("known_difference");
                        setAdjustment("");
                        setResolving(item);
                      }}
                    >
                      Resolve
                    </ActionButton>
                  )}
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </PagedView>
      <ActionSheet
        open={resolving !== null}
        onOpenChange={(open) => !open && setResolving(null)}
        record={resolving ?? undefined}
        title="Resolve this item?"
        change={
          <>
            As a known difference, with why; or by the approved adjustment that put it right, whose reference
            names this item ({resolving?.id.slice(0, 8)}). Ask for one on{" "}
            <Link href="/admin/adjustments/" className="underline">
              Adjustments
            </Link>{" "}
            first.
          </>
        }
        confirm="Resolve"
        extra={
          <div className="flex flex-wrap items-end gap-2">
            <SmallSelect
              label="How"
              value={resolution}
              onChange={setResolution}
              options={[
                ["known_difference", "A known difference"],
                ["adjustment", "By an adjustment"],
              ]}
            />
            {resolution === "adjustment" && (
              <SmallField
                label="The adjustment's id"
                value={adjustment}
                onChange={setAdjustment}
                width="w-96"
              />
            )}
          </div>
        }
        onConfirm={async (reason) => {
          if (resolution === "adjustment" && adjustment.trim() === "") {
            throw new SheetProblem("Give the adjustment's id.");
          }
          await adminData(
            adminApi.POST("/admin/v1/settlement-items/{id}/resolve", {
              params: { path: { id: resolving!.id } },
              body: {
                resolution: resolution as "known_difference" | "adjustment",
                adjustmentId: resolution === "adjustment" ? adjustment.trim() : null,
                reason,
              },
            }),
          );
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </Section>
  );
}
