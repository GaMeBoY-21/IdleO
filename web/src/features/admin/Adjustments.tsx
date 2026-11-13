import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { StatusChip, Tag } from "@/components/ui/StatusChip";
import { Link } from "wouter";
import { Button } from "@/components/ui/Button";
import { Money as Paise } from "@/components/ui/Money";
import { RelativeTime } from "@/components/ui/Time";
import { formatPaise, parseRupees } from "@/lib/money";
import type { components } from "@/api/schema.gen";
import { ActionSheet, SheetProblem } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import { word } from "./words";
import { ledgerHref } from "./Ledger";
import { AdminPage, Cell, Empty, Id, PagedView, Row, Section, SmallField, SmallSelect, Table } from "./ui";
import { ActionButton } from "./permissions";
import { ListBar } from "./Lists";
import { useListView } from "./listView";

type Adjustment = components["schemas"]["AdminAdjustment"];
type Leg = { account: string; ownerId: string; rupees: string; direction: "debit" | "credit" };

/** What each template is, and its fixed legs: null for a journal, whose legs are the admin's own. */
export const TEMPLATES: Record<string, { label: string; legs: Omit<Leg, "rupees" | "ownerId">[] | null }> = {
  journal: { label: "Journal entry (any legs that balance)", legs: null },
  goodwill_credit: {
    label: "Goodwill credit to a renter",
    legs: [
      { account: "promotions", direction: "debit" },
      { account: "renter_wallet", direction: "credit" },
    ],
  },
  fee_correction: {
    label: "A Razorpay fee we didn't expect",
    legs: [
      { account: "gateway_fees", direction: "debit" },
      { account: "gateway_clearing", direction: "credit" },
    ],
  },
  write_off: {
    label: "Write off a small difference (at most ₹100)",
    legs: [
      { account: "write_offs", direction: "debit" },
      { account: "gateway_clearing", direction: "credit" },
    ],
  },
};

/** The accounts an adjustment may post to, as the server allows (ADR 0055). */
export const ADJUSTABLE = [
  "host_payable",
  "host_receivable",
  "platform_revenue",
  "gateway_clearing",
  "promotions",
  "tax_cgst",
  "tax_sgst",
  "tax_igst",
  "free_credit_expired",
  "chargeback_losses",
  "tds_payable",
  "earnings_forfeited",
  "gateway_fees",
  "gst_input_credit",
  "write_offs",
];

const OWNED = new Set(["host_payable", "host_receivable", "renter_wallet"]);

/** The legs' totals in paise, and what's wrong with them, or null when they can be asked for. */
export function legsProblem(legs: Leg[]): { debits: number; credits: number; problem: string | null } {
  let debits = 0;
  let credits = 0;
  for (const leg of legs) {
    const paise = parseRupees(leg.rupees);
    if (paise === null || paise <= 0) {
      return { debits, credits, problem: "Every leg needs an amount above ₹0." };
    }
    if (OWNED.has(leg.account) && leg.ownerId.trim() === "") {
      return { debits, credits, problem: `${word(leg.account)} needs the owner it belongs to.` };
    }
    if (leg.direction === "debit") {
      debits += paise;
    } else {
      credits += paise;
    }
  }
  if (legs.length < 2) {
    return { debits, credits, problem: "An adjustment needs at least two legs." };
  }
  if (debits !== credits) {
    return {
      debits,
      credits,
      problem: `The legs don't balance: debits ${formatPaise(debits)}, credits ${formatPaise(credits)}.`,
    };
  }
  return { debits, credits, problem: null };
}

/**
 * Adjustments (ADR 0055): a journal entry of legs that balance, or one of the templates, asked for with a reason and a
 * reference; another admin approves it, seeing every leg, and it posts only then. Each posted one can be reversed the
 * same way.
 */
export function Adjustments() {
  return (
    <AdminPage title="Adjustments">
      <Ask />
      <Posted />
    </AdminPage>
  );
}

function blank(template: string): Leg[] {
  const fixed = TEMPLATES[template]?.legs;
  if (fixed) {
    return fixed.map((leg) => ({ ...leg, ownerId: "", rupees: "" }));
  }
  return [
    { account: "gateway_fees", direction: "debit", ownerId: "", rupees: "" },
    { account: "gateway_clearing", direction: "credit", ownerId: "", rupees: "" },
  ];
}

function Ask() {
  const client = useQueryClient();
  const [template, setTemplate] = useState("fee_correction");
  const [legs, setLegs] = useState<Leg[]>(() => blank("fee_correction"));
  const [reference, setReference] = useState("");
  const [correctsMonth, setCorrectsMonth] = useState("");
  const [open, setOpen] = useState(false);
  const fixed = TEMPLATES[template]?.legs !== null;
  const totals = legsProblem(legs);
  const setLeg = (index: number, change: Partial<Leg>) =>
    setLegs(legs.map((leg, at) => (at === index ? { ...leg, ...change } : leg)));
  // A fixed template's legs move together: one amount for both.
  const setAmount = (rupees: string) => setLegs(legs.map((leg) => ({ ...leg, rupees })));
  return (
    <Section
      title="Ask for an adjustment"
      action={
        <ActionButton permission="money.adjust" onClick={() => setOpen(true)}>
          Ask for approval
        </ActionButton>
      }
    >
      <div className="flex flex-wrap items-end gap-2">
        <SmallSelect
          label="Template"
          value={template}
          onChange={(next) => {
            setTemplate(next);
            setLegs(blank(next));
          }}
          options={Object.entries(TEMPLATES).map(([value, { label }]) => [value, label])}
        />
        {fixed && (
          <SmallField
            label="Amount in ₹"
            value={legs[0]?.rupees ?? ""}
            onChange={setAmount}
            inputMode="decimal"
          />
        )}
        {template === "goodwill_credit" && (
          <SmallField
            label="The renter (user id)"
            value={legs[1]?.ownerId ?? ""}
            onChange={(ownerId) => setLeg(1, { ownerId })}
            width="w-72"
          />
        )}
        <SmallField
          label="Reference (a ticket or a reconciliation item)"
          value={reference}
          onChange={setReference}
          width="w-72"
        />
        <SmallField
          label="Corrects a closed month (YYYY-MM, or empty)"
          value={correctsMonth}
          onChange={setCorrectsMonth}
        />
      </div>
      {!fixed && (
        <div className="flex flex-col gap-2">
          {legs.map((leg, index) => (
            <div key={index} className="flex flex-wrap items-end gap-2">
              <SmallSelect
                label={`Leg ${index + 1}`}
                value={leg.direction}
                onChange={(direction) => setLeg(index, { direction: direction as Leg["direction"] })}
                options={[
                  ["debit", "Debit"],
                  ["credit", "Credit"],
                ]}
              />
              <SmallSelect
                label="Account"
                value={leg.account}
                onChange={(account) => setLeg(index, { account })}
                options={ADJUSTABLE.map((account) => [account, account])}
              />
              {OWNED.has(leg.account) && (
                <SmallField
                  label="Owner (host id)"
                  value={leg.ownerId}
                  onChange={(ownerId) => setLeg(index, { ownerId })}
                  width="w-72"
                />
              )}
              <SmallField
                label="Amount in ₹"
                value={leg.rupees}
                onChange={(rupees) => setLeg(index, { rupees })}
                inputMode="decimal"
              />
              {legs.length > 2 && (
                <Button variant="ghost" onClick={() => setLegs(legs.filter((_, at) => at !== index))}>
                  Remove
                </Button>
              )}
            </div>
          ))}
          <div>
            <Button
              variant="ghost"
              onClick={() =>
                setLegs([
                  ...legs,
                  { account: "gateway_clearing", direction: "credit", ownerId: "", rupees: "" },
                ])
              }
            >
              Add a leg
            </Button>
          </div>
        </div>
      )}
      <p className="tabular text-small text-ink-subtle" aria-live="polite">
        Debits <Paise paise={totals.debits} />, credits <Paise paise={totals.credits} />
        {totals.problem ? ` — ${totals.problem}` : ", balanced."}
      </p>
      <ActionSheet
        open={open}
        onOpenChange={setOpen}
        title="Ask another admin to approve this adjustment?"
        change={
          <>
            {TEMPLATES[template]?.label}:{" "}
            {legs.map((leg, index) => (
              <span key={index}>
                {index > 0 ? "; " : ""}
                {leg.direction} {word(leg.account)}
                {leg.ownerId ? ` of ${leg.ownerId.slice(0, 8)}` : ""}{" "}
                {formatPaise(parseRupees(leg.rupees) ?? 0)}
              </span>
            ))}
            . Nothing moves until it&apos;s approved.
          </>
        }
        confirm="Ask for approval"
        onConfirm={async (reason) => {
          if (totals.problem !== null) {
            throw new SheetProblem(totals.problem);
          }
          if (reference.trim() === "") {
            throw new SheetProblem("Give a reference: the ticket or the reconciliation item.");
          }
          await adminData(
            adminApi.POST("/admin/v1/adjustments", {
              body: {
                template: template as "journal" | "goodwill_credit" | "fee_correction" | "write_off",
                legs: legs.map((leg) => ({
                  account: leg.account,
                  ownerId: leg.ownerId.trim() === "" ? null : leg.ownerId.trim(),
                  amountPaise: parseRupees(leg.rupees) ?? 0,
                  direction: leg.direction,
                })),
                reason,
                reference: reference.trim(),
                correctsMonth: correctsMonth.trim() === "" ? null : correctsMonth.trim(),
              },
            }),
          );
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </Section>
  );
}

const COLUMNS = ["When", "Template", "Reference", "Legs", "Asked by", ""];

function Posted() {
  const client = useQueryClient();
  const view = useListView();
  const [reversing, setReversing] = useState<Adjustment | null>(null);
  const [reference, setReference] = useState("");
  const adjustments = useInfiniteQuery({
    queryKey: [ADMIN, "adjustments", view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/adjustments", {
          params: { query: { ...view, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <Section title="Posted">
      <ListBar list="adjustments" tags={false} />
      <PagedView
        query={adjustments}
        what="the adjustments"
        items={(page) => page.adjustments}
        empty={<Empty title="No adjustments">None has been approved and posted yet.</Empty>}
      >
        {(rows) => (
          <Table
            caption="Posted adjustments"
            list="adjustments"
            sorts={{ When: "createdAt" }}
            columns={COLUMNS}
          >
            {rows.map((adjustment) => (
              <Row key={adjustment.id}>
                <Cell>
                  <RelativeTime value={adjustment.createdAt} />
                </Cell>
                <Cell>
                  {word(adjustment.template)}
                  {adjustment.correctsMonth ? (
                    <span className="block text-caption text-ink-subtle">
                      corrects {adjustment.correctsMonth}
                    </span>
                  ) : null}
                </Cell>
                <Cell className="max-w-prose text-small">
                  {adjustment.reference}
                  <span className="block text-caption text-ink-subtle">{adjustment.reason}</span>
                </Cell>
                <Cell>
                  <ul className="flex flex-col gap-1 text-small">
                    {adjustment.legs.map((leg, index) => (
                      <li key={index} className="tabular">
                        {leg.direction} {word(leg.account)} <Paise paise={leg.amountPaise} />
                      </li>
                    ))}
                  </ul>
                  <Link href={ledgerHref({ txn: adjustment.txnId })} className="text-caption underline">
                    the transaction
                  </Link>
                </Cell>
                <Cell>{adjustment.requestedBy}</Cell>
                <Cell>
                  {adjustment.reversesId ? (
                    <Tag>
                      {" "}
                      reverses <Id value={adjustment.reversesId} />{" "}
                    </Tag>
                  ) : adjustment.reversedById ? (
                    <StatusChip size="sm" domain="due" state="reversed" />
                  ) : (
                    <ActionButton
                      permission="money.adjust"
                      variant="ghost"
                      onClick={() => {
                        setReference("");
                        setReversing(adjustment);
                      }}
                    >
                      Reverse
                    </ActionButton>
                  )}
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </PagedView>
      <ActionSheet
        open={reversing !== null}
        onOpenChange={(open) => !open && setReversing(null)}
        record={reversing ?? undefined}
        title="Ask to reverse this adjustment?"
        change="The same legs the other way round, as an adjustment of its own that names this one. Another admin approves it; a goodwill credit's reversal takes back what's left of the credit."
        confirm="Ask for approval"
        extra={<SmallField label="Reference" value={reference} onChange={setReference} width="w-72" />}
        onConfirm={async (reason) => {
          if (reference.trim() === "") {
            throw new SheetProblem("Give a reference: the ticket or the reconciliation item.");
          }
          await adminData(
            adminApi.POST("/admin/v1/adjustments/{id}/reverse", {
              params: { path: { id: reversing!.id } },
              body: { reason, reference: reference.trim() },
            }),
          );
          await client.invalidateQueries({ queryKey: [ADMIN] });
        }}
      />
    </Section>
  );
}
