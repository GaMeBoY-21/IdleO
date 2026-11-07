import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { RelativeTime } from "@/components/ui/Time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import { AdminPage, Cell, Empty, QueryView, Row, Section, SmallField, Table } from "./ui";
import { ActionButton } from "./permissions";

/** The month before now, as the API takes it: `2026-10` in November 2026. */
export function lastMonth(now: Date): string {
  const month = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  return `${month.getFullYear()}-${String(month.getMonth() + 1).padStart(2, "0")}`;
}

const EXPORT_COLUMNS = ["File", "What it is", "In the pack", ""];
const PACK_COLUMNS = ["Month", "Files", "Manifest SHA-256", "Asked by", "When"];

/**
 * Tax (ADR 0055): the CA's files for a month (the invoice register, credit notes, GSTR-1's data, the GST summary, the
 * TDS a quarter or the month deducted, 26Q's and Form 16A's data, the payouts, the journal, the trial balance and the
 * settlement reconciliation) and the month's pack, one zip with a manifest of every file's hash. Each holds whole PANs
 * or GSTINs, so another admin approves it first; the asker then fetches it from Approvals, once. Every export is audited.
 */
export function Tax() {
  const [month, setMonth] = useState(() => lastMonth(new Date()));
  const [asking, setAsking] = useState<string | null>(null);
  const exports = useQuery({
    queryKey: [ADMIN, "tax-exports"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/tax/exports")),
  });
  return (
    <AdminPage title="Tax">
      <Section
        title="The CA's files"
        action={
          <ActionButton permission="tax.export" onClick={() => setAsking("ca-pack")}>
            Ask for the month&apos;s CA pack
          </ActionButton>
        }
      >
        <p className="max-w-prose text-small text-text-2">
          A quarterly file covers the quarter that holds the month. Send them to the CA through the
          company&apos;s secure channel, never email. The formats wait on the CA&apos;s confirmation
          (CA(confirm)).
        </p>
        <div className="flex">
          <SmallField label="Month (YYYY-MM)" value={month} onChange={setMonth} />
        </div>
        <QueryView query={exports} what="the CA's files">
          {(found) => (
            <Table caption="The CA's files" columns={EXPORT_COLUMNS}>
              {found.exports.map((file) => (
                <Row key={file.name}>
                  <Cell>{file.name}</Cell>
                  <Cell className="max-w-prose text-small">{file.description}</Cell>
                  <Cell>{file.inPack ? "yes" : "no"}</Cell>
                  <Cell>
                    <ActionButton
                      permission="tax.export"
                      variant="ghost"
                      onClick={() => setAsking(file.name)}
                    >
                      Ask for it
                    </ActionButton>
                  </Cell>
                </Row>
              ))}
            </Table>
          )}
        </QueryView>
      </Section>
      <Section title="Packs made">
        <QueryView
          query={exports}
          what="the packs made"
          isEmpty={(found) => found.packs.length === 0}
          empty={<Empty title="No pack yet">Ask for a month&apos;s CA pack above.</Empty>}
        >
          {(found) => (
            <Table caption="Packs made" columns={PACK_COLUMNS}>
              {found.packs.map((pack) => (
                <Row key={pack.manifestSha256 + pack.at}>
                  <Cell className="tabular">{pack.month}</Cell>
                  <Cell className="tabular">{pack.files}</Cell>
                  <Cell className="font-mono text-caption">{pack.manifestSha256.slice(0, 16)}…</Cell>
                  <Cell>{pack.actor}</Cell>
                  <Cell>
                    <RelativeTime value={pack.at} />
                  </Cell>
                </Row>
              ))}
            </Table>
          )}
        </QueryView>
      </Section>
      <ActionSheet
        open={asking !== null}
        onOpenChange={(open) => !open && setAsking(null)}
        title={
          asking === "ca-pack" ? `Ask for the CA pack of ${month}?` : `Ask for ${asking ?? ""} for ${month}?`
        }
        change="It holds whole PANs or GSTINs, so another admin approves it first. Once they have, fetch it from Approvals, once. The audit log records that you asked, why, and what the file adds up to."
        confirm="Ask for approval"
        onConfirm={async (reason) => {
          await adminData(
            adminApi.POST("/admin/v1/tax/exports", {
              body: { export: asking!, month: month.trim(), reason },
            }),
          );
        }}
      />
    </AdminPage>
  );
}
