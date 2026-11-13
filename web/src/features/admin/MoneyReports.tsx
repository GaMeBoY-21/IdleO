import { useQuery } from "@tanstack/react-query";
import { FilterBar } from "@/components/ui/FilterBar";
import { useState } from "react";
import { KeyValueList } from "@/components/ui/KeyValues";
import { Link } from "wouter";
import { Money as Paise } from "@/components/ui/Money";
import type { components } from "@/api/schema.gen";
import { ADMIN, adminApi, adminData } from "./api";
import { word } from "./words";
import { ledgerHref } from "./Ledger";
import { AdminPage, Cell, Empty, QueryView, Row, Section, SmallField, SmallSelect, Table } from "./ui";

type Figure = components["schemas"]["AdminFigure"];

/** Each headline figure's name on screen. */
export const FIGURES: Record<string, string> = {
  gmv: "GMV",
  take: "Take",
  refunds: "Refunds",
  chargebacks: "Chargebacks",
  promotions: "Promotions",
  gatewayFees: "Razorpay fees",
  net: "Net",
};

/** The first and last instant of a bucket that starts on `start` (an India date), as the ledger's filters take them. */
export function bucketRange(start: string, interval: string): { from: string; to: string } {
  const [year, month, day] = start.split("-").map(Number) as [number, number, number];
  const first = Date.UTC(year, month - 1, day) - 5.5 * 3_600_000;
  const next =
    interval === "month"
      ? Date.UTC(year, month, 1) - 5.5 * 3_600_000
      : first + (interval === "week" ? 7 : 1) * 86_400_000;
  return { from: new Date(first).toISOString(), to: new Date(next).toISOString() };
}

/**
 * Money reports (ADR 0055): the headline figures by day, week or month, each a link to the ledger entries it sums; and
 * the liabilities: renters' credit, what hosts are owed and how long, and free credit's breakage.
 */
export function MoneyReports() {
  return (
    <AdminPage title="Money reports">
      <Headlines />
      <Liabilities />
    </AdminPage>
  );
}

function Headlines() {
  const [form, setForm] = useState({ interval: "day", from: "", to: "" });
  const [asked, setAsked] = useState(form);
  const report = useQuery({
    queryKey: [ADMIN, "money-report", asked],
    queryFn: () =>
      adminData(
        adminApi.GET("/admin/v1/money/report", {
          params: {
            query: {
              interval: asked.interval as "day" | "week" | "month",
              ...(asked.from ? { from: asked.from } : {}),
              ...(asked.to ? { to: asked.to } : {}),
            },
          },
        }),
      ),
  });
  return (
    <Section title="Headline figures">
      <FilterBar onSubmit={() => setAsked({ ...form, from: form.from.trim(), to: form.to.trim() })}>
        <SmallSelect
          label="By"
          value={form.interval}
          onChange={(interval) => setForm({ ...form, interval })}
          options={[
            ["day", "Day"],
            ["week", "Week"],
            ["month", "Month"],
          ]}
        />
        <SmallField
          label="From (YYYY-MM-DD)"
          value={form.from}
          onChange={(from) => setForm({ ...form, from })}
        />
        <SmallField label="To (YYYY-MM-DD)" value={form.to} onChange={(to) => setForm({ ...form, to })} />
      </FilterBar>
      <QueryView
        query={report}
        what="the money report"
        isEmpty={(found) => found.rows.length === 0}
        empty={<Empty title="Nothing in these days">No entry is dated between these days.</Empty>}
      >
        {(found) => (
          <Table
            caption="Headline figures"
            columns={["Starting", ...found.figures.map((figure) => FIGURES[figure.name] ?? figure.name)]}
          >
            {found.rows.map((row) => (
              <Row key={row.start}>
                <Cell className="tabular">{row.start}</Cell>
                {found.figures.map((figure) => (
                  <Cell key={figure.name} className="tabular">
                    <FigureLinks
                      figure={figure}
                      paise={row.figures[figure.name] ?? 0}
                      range={bucketRange(row.start, found.interval)}
                    />
                  </Cell>
                ))}
              </Row>
            ))}
          </Table>
        )}
      </QueryView>
    </Section>
  );
}

/** A figure, and a link to each set of entries it sums: one for most figures, several for GMV and the net. */
function FigureLinks({
  figure,
  paise,
  range,
}: {
  figure: Figure;
  paise: number;
  range: { from: string; to: string };
}) {
  if (figure.terms.length === 1) {
    const [term] = figure.terms;
    return (
      <Link
        href={ledgerHref({ account: term!.account, refType: term!.refType ?? undefined, ...range })}
        className="underline"
      >
        <Paise paise={paise} />
      </Link>
    );
  }
  return (
    <span className="flex flex-col">
      <Paise paise={paise} />
      <span className="text-caption text-ink-subtle">
        {figure.terms.map((term, index) => (
          <span key={index}>
            {index > 0 ? ", " : ""}
            <Link
              href={ledgerHref({ account: term.account, refType: term.refType ?? undefined, ...range })}
              className="underline"
            >
              {term.sign < 0 ? "−" : "+"}
              {word(term.account)}
            </Link>
          </span>
        ))}
      </span>
    </span>
  );
}

function Liabilities() {
  const liabilities = useQuery({
    queryKey: [ADMIN, "liabilities"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/money/liabilities")),
  });
  return (
    <Section title="Liabilities, now">
      <QueryView query={liabilities} what="the liabilities">
        {(found) => (
          <>
            <KeyValueList
              items={[
                [
                  "Renters' bought credit",
                  <Link key="bought" href={ledgerHref({ account: "renter_purchased" })} className="underline">
                    <Paise paise={found.renterPurchasedPaise} />
                  </Link>,
                ],
                [
                  "Renters' free credit",
                  <Link key="free" href={ledgerHref({ account: "renter_wallet" })} className="underline">
                    <Paise paise={found.renterFreePaise} />
                  </Link>,
                ],
                [
                  "What hosts are owed",
                  <Link key="hosts" href={ledgerHref({ account: "host_payable" })} className="underline">
                    <Paise paise={found.hostPayablePaise} />
                  </Link>,
                ],
                [
                  "Earned in the last 7 days",
                  <Paise key="d7" paise={found.hostPayableAged.upTo7DaysPaise} />,
                ],
                ["8 to 14 days ago", <Paise key="d14" paise={found.hostPayableAged.days8To14Paise} />],
                ["15 to 30 days ago", <Paise key="d30" paise={found.hostPayableAged.days15To30Paise} />],
                [
                  "More than 30 days ago",
                  <Paise key="older" paise={found.hostPayableAged.over30DaysPaise} />,
                ],
              ]}
            />
            <Table
              caption="Free credit's breakage, by month"
              columns={["Month", "Expired unspent", "Still to expire"]}
            >
              {found.breakage.map((month) => (
                <Row key={month.month}>
                  <Cell className="tabular">{month.month}</Cell>
                  <Cell className="tabular">
                    <Paise paise={month.expiredPaise} />
                  </Cell>
                  <Cell className="tabular">
                    <Paise paise={month.toExpirePaise} />
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
