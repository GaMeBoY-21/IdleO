import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { StatusChip } from "@/components/ui/StatusChip";
import { Button } from "@/components/ui/Button";
import { RelativeTime } from "@/components/ui/Time";
import { ActionSheet, SheetProblem } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import { AdminPage, Cell, Empty, PagedView, Row, Section, SmallField, SmallSelect, Table } from "./ui";
import { ActionButton } from "./permissions";
import { ListBar } from "./Lists";
import { useListParam, useListView } from "./listView";

/**
 * Invite codes (ADR 0024, ADR 0027): made by the same service as `create-invites` and shown once, here, in this page's
 * memory only; listed afterwards with what each unlocks and who used it, never the code. A code unlocks renting, hosting
 * or both (ADR 0058).
 */
const KINDS: [Unlocks, string][] = [
  ["both", "Renting and hosting"],
  ["renting", "Renting"],
  ["hosting", "Hosting"],
];
type Unlocks = "both" | "renting" | "hosting";
const kindOf = (unlocks: string) => KINDS.find(([kind]) => kind === unlocks)?.[1] ?? unlocks;

export function Invites() {
  const client = useQueryClient();
  const [making, setMaking] = useState(false);
  const [count, setCount] = useState("1");
  const [days, setDays] = useState("30");
  const [unlocks, setUnlocks] = useState<Unlocks>("both");
  const [made, setMade] = useState<{ codes: string[]; note: string; unlocks: Unlocks } | null>(null);
  const [used, setUsed] = useListParam("used", "");
  const [kind, setKind] = useListParam("unlocks", "");
  const view = useListView();
  const invites = useInfiniteQuery({
    queryKey: [ADMIN, "invites", used, kind, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/invites", {
          params: {
            query: {
              ...(used ? { used: used === "used" } : {}),
              ...(kind ? { unlocks: kind as Unlocks } : {}),
              ...view,
              ...(pageParam ? { cursor: pageParam } : {}),
            },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const howMany = Number.parseInt(count, 10);
  const expiry = days.trim() === "" ? null : Number.parseInt(days, 10);
  return (
    <AdminPage title="Invites">
      <ActionButton
        permission="invites.create"
        variant="ghost"
        className="self-start"
        onClick={() => {
          setMaking(true);
        }}
      >
        Make invite codes
      </ActionButton>
      {made !== null && (
        <Section
          title="New codes"
          action={
            <Button variant="quiet" onClick={() => setMade(null)}>
              Done
            </Button>
          }
        >
          <p className="text-small text-ink-subtle">
            For &ldquo;{made.note}&rdquo;, unlocking {kindOf(made.unlocks).toLowerCase()}. Shown only now:
            only their hashes are kept. Each works once.
          </p>
          <ul
            className="tabular grid grid-cols-1 gap-2 rounded-card border border-hairline bg-surface-1 p-4 text-body md:grid-cols-3"
            aria-label="New invite codes"
          >
            {made.codes.map((code) => (
              <li key={code}>{code}</li>
            ))}
          </ul>
        </Section>
      )}
      <ActionSheet
        open={making}
        onOpenChange={setMaking}
        title="Make invite codes"
        change={`${Number.isInteger(howMany) && howMany > 0 ? howMany : "Some"} single-use code${howMany === 1 ? "" : "s"} unlocking ${kindOf(unlocks).toLowerCase()}, ${expiry ? `each valid for ${expiry} days` : "with no expiry"}. The reason is the note that says who they're for, and the codes are shown once.`}
        confirm="Make the codes"
        extra={
          <div className="flex flex-wrap gap-2">
            <SmallSelect
              label="Unlocks"
              value={unlocks}
              onChange={(value) => setUnlocks(value as Unlocks)}
              options={KINDS}
            />
            <SmallField
              label="How many (1 to 200)"
              value={count}
              onChange={setCount}
              inputMode="numeric"
              width="w-32"
            />
            <SmallField
              label="Valid for, in days"
              value={days}
              onChange={setDays}
              inputMode="numeric"
              width="w-32"
            />
          </div>
        }
        onConfirm={async (reason) => {
          if (!Number.isInteger(howMany) || howMany < 1 || howMany > 200) {
            throw new SheetProblem("Make from 1 to 200 codes at a time.");
          }
          if (expiry !== null && (!Number.isInteger(expiry) || expiry < 1 || expiry > 365)) {
            throw new SheetProblem("A code can last from 1 to 365 days, or leave it empty for no expiry.");
          }
          const created = await adminData(
            adminApi.POST("/admin/v1/invites", {
              body: {
                count: howMany,
                note: reason,
                unlocks,
                ...(expiry === null ? {} : { expiresInDays: expiry }),
              },
            }),
          );
          // More than 20 goes to another admin, and the codes are fetched from Approvals once it's approved.
          if ("codes" in created) {
            setMade({ codes: created.codes, note: reason, unlocks });
          }
          await client.invalidateQueries({ queryKey: [ADMIN, "invites"] });
        }}
      />
      <Section title="Codes">
        <div className="flex flex-wrap gap-2">
          <ListBar
            list="invites"
            tags={false}
            extra={{ ...(used ? { used: String(used === "used") } : {}), ...(kind ? { unlocks: kind } : {}) }}
          />
          <SmallSelect
            label="Show"
            value={used}
            onChange={setUsed}
            options={[
              ["", "All"],
              ["unused", "Not used yet"],
              ["used", "Used"],
            ]}
          />
          <SmallSelect label="Unlocks" value={kind} onChange={setKind} options={[["", "Any"], ...KINDS]} />
        </div>
        <PagedView
          query={invites}
          what="invite codes"
          items={(page) => page.invites}
          empty={<Empty title="No codes here">Make some, and they show here with who used each.</Empty>}
        >
          {(rows) => (
            <Table
              caption="Invite codes"
              list="invites"
              sorts={{ Made: "createdAt" }}
              columns={["For", "Unlocks", "Made by", "Made", "Lapses", "Used by"]}
            >
              {rows.map((invite) => (
                <Row key={invite.id}>
                  <Cell>{invite.note ?? "Erased account"}</Cell>
                  <Cell>{kindOf(invite.unlocks)}</Cell>
                  <Cell>{invite.createdBy}</Cell>
                  <Cell>
                    <RelativeTime value={invite.createdAt} />
                  </Cell>
                  <Cell>{invite.expiresAt ? <RelativeTime value={invite.expiresAt} /> : "never"}</Cell>
                  <Cell>
                    {invite.usedAt ? (
                      <span>
                        {invite.usedByEmail ?? invite.usedBy}{" "}
                        <RelativeTime value={invite.usedAt} className="text-ink-subtle" />
                      </span>
                    ) : (
                      <StatusChip size="sm" domain="invite" state="unused" />
                    )}
                  </Cell>
                </Row>
              ))}
            </Table>
          )}
        </PagedView>
      </Section>
    </AdminPage>
  );
}
