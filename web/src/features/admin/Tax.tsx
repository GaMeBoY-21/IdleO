import { useState } from "react";
import { ActionSheet } from "./ActionSheet";
import { adminApi, adminData } from "./api";
import { AdminPage, Section, SmallField } from "./ui";
import { ActionButton } from "./permissions";

/** The quarter now, as the export names it: `2026-27-Q3` for October to December 2026. */
export function quarterOf(date: Date): string {
  const month = date.getMonth() + 1;
  const startYear = month >= 4 ? date.getFullYear() : date.getFullYear() - 1;
  const quarter = month >= 4 ? Math.floor((month - 4) / 3) + 1 : 4;
  return `${startYear}-${String((startYear + 1) % 100).padStart(2, "0")}-Q${quarter}`;
}

/**
 * Tax (ADR 0040): the quarter's tax deducted at source as CSV, for the CA to file, with each host's whole PAN. Asked
 * for with a reason, audited; the file goes straight to the admin's downloads and nowhere else.
 */
export function Tax() {
  const [quarter, setQuarter] = useState(() => quarterOf(new Date()));
  const [open, setOpen] = useState(false);
  return (
    <AdminPage title="Tax">
      <Section title="Tax deducted at source, by quarter">
        <p className="max-w-prose text-small text-text-2">
          Each payout that didn&apos;t come back, deducted in the quarter: the host&apos;s whole PAN and name
          as on it, the section and rate, the gross, the tax, the net and the dates. Send it to the CA through
          the company&apos;s secure channel, never email.
        </p>
        <div className="flex flex-wrap items-end gap-3">
          <SmallField
            label="Quarter (2026-27-Q3 is October to December 2026)"
            value={quarter}
            onChange={setQuarter}
          />
          <ActionButton permission="export.create" variant="ghost" onClick={() => setOpen(true)}>
            Export CSV
          </ActionButton>
        </div>
      </Section>
      <ActionSheet
        open={open}
        onOpenChange={setOpen}
        title={`Export ${quarter}?`}
        change="The file holds every host's whole PAN, so another admin approves it first. Once they have, fetch it from Approvals, once. The audit log records that you exported it and why."
        confirm="Ask to export"
        onConfirm={async (reason) => {
          await adminData(
            adminApi.POST("/admin/v1/payouts/tds-export", { body: { quarter: quarter.trim(), reason } }),
          );
        }}
      />
    </AdminPage>
  );
}
