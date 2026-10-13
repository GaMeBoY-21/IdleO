import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { RelativeTime } from "@/components/ui/Time";
import { ActionSheet, SheetProblem } from "./ActionSheet";
import { ADMIN, adminApi, adminData } from "./api";
import { AdminPage, Cell, Empty, PagedView, Pill, Row, Section, SmallField, SmallSelect, Table } from "./ui";

/**
 * Invite codes (ADR 0024, ADR 0027): made by the same service as `create-invites` and shown once, here, in this page's
 * memory only; listed afterwards with who used each, never the code.
 */
export function Invites() {
  const client = useQueryClient();
  const [making, setMaking] = useState(false);
  const [count, setCount] = useState("1");
  const [days, setDays] = useState("30");
  const [key, setKey] = useState("");
  const [made, setMade] = useState<{ codes: string[]; note: string } | null>(null);
  const [used, setUsed] = useState("");
  const invites = useInfiniteQuery({
    queryKey: [ADMIN, "invites", used],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/invites", {
          params: {
            query: {
              ...(used ? { used: used === "used" } : {}),
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
      <Button
        variant="secondary"
        className="self-start"
        onClick={() => {
          setKey(crypto.randomUUID());
          setMaking(true);
        }}
      >
        Make invite codes
      </Button>
      {made !== null && (
        <Section
          title="New codes"
          action={
            <Button variant="quiet" onClick={() => setMade(null)}>
              Done
            </Button>
          }
        >
          <p className="text-small text-text-muted">
            For &ldquo;{made.note}&rdquo;. Shown only now: only their hashes are kept. Each works once.
          </p>
          <ul
            className="tabular grid grid-cols-1 gap-2 rounded-control bg-fill p-4 text-body md:grid-cols-3"
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
        change={`${Number.isInteger(howMany) && howMany > 0 ? howMany : "Some"} single-use code${howMany === 1 ? "" : "s"}, ${expiry ? `each valid for ${expiry} days` : "with no expiry"}. The reason is the note that says who they're for, and the codes are shown once.`}
        confirm="Make the codes"
        extra={
          <div className="flex gap-3">
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
              params: { header: { "Idempotency-Key": key } },
              body: { count: howMany, note: reason, ...(expiry === null ? {} : { expiresInDays: expiry }) },
            }),
          );
          setMade({ codes: created.codes, note: reason });
          await client.invalidateQueries({ queryKey: [ADMIN, "invites"] });
        }}
      />
      <Section title="Codes">
        <div className="flex">
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
        </div>
        <PagedView
          query={invites}
          what="invite codes"
          items={(page) => page.invites}
          empty={<Empty title="No codes here">Make some, and they show here with who used each.</Empty>}
        >
          {(rows) => (
            <Table caption="Invite codes" columns={["For", "Made by", "Made", "Lapses", "Used by"]}>
              {rows.map((invite) => (
                <Row key={invite.id}>
                  <Cell>{invite.note}</Cell>
                  <Cell>{invite.createdBy}</Cell>
                  <Cell>
                    <RelativeTime value={invite.createdAt} />
                  </Cell>
                  <Cell>{invite.expiresAt ? <RelativeTime value={invite.expiresAt} /> : "never"}</Cell>
                  <Cell>
                    {invite.usedAt ? (
                      <span>
                        {invite.usedByEmail ?? invite.usedBy}{" "}
                        <RelativeTime value={invite.usedAt} className="text-text-muted" />
                      </span>
                    ) : (
                      <Pill>unused</Pill>
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
