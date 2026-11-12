import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { StatusChip, Tag } from "@/components/ui/StatusChip";
import { useSearch } from "wouter";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { RelativeTime } from "@/components/ui/Time";
import { formatDateTime } from "@/lib/time";
import { ActionSheet } from "./ActionSheet";
import { ADMIN, adminApi, adminData, type Schemas } from "./api";
import { word } from "./words";
import { ListBar } from "./Lists";
import { useListParam, useListView } from "./listView";
import { ActionButton, useCan } from "./permissions";
import {
  AdminPage,
  Cell,
  Empty,
  Fields,
  Id,
  PagedView,
  QueryView,
  Row,
  RowLink,
  Section,
  SmallField,
  SmallSelect,
  Table,
} from "./ui";

type Step = Schemas["AdminEnforcement"];

const STEPS: [string, string][] = [
  ["warn", "Warn"],
  ["limit", "Limit"],
  ["suspend", "Suspend"],
  ["ban", "Ban"],
];

const LIMITS: [string, string][] = [
  ["no-custom-images", "No custom images"],
  ["session-hours-cap", "A cap on session hours a week"],
  ["payouts-held", "Payouts held (hosts only)"],
];

const STEP_COLUMNS = ["When", "As", "Step", "Why", "Until", "State", ""];

/** The ladder on a person's page: their strikes as a renter and as a host, each step taken, and the next. */
export function UserEnforcement({ id }: { id: string }) {
  const client = useQueryClient();
  const can = useCan();
  const steps = useQuery({
    queryKey: [ADMIN, "enforcements", id],
    queryFn: () =>
      adminData(adminApi.GET("/admin/v1/enforcements", { params: { query: { user: id, limit: 200 } } })),
  });
  const [taking, setTaking] = useState(false);
  const [role, setRole] = useState("renter");
  const [step, setStep] = useState("warn");
  const [limit, setLimit] = useState("no-custom-images");
  const [hours, setHours] = useState("");
  const [until, setUntil] = useState("");
  const [lifting, setLifting] = useState<Step | null>(null);
  const refresh = () => client.invalidateQueries({ queryKey: [ADMIN] });
  return (
    <Section
      title="Enforcement"
      action={
        <ActionButton permission="enforcement.apply" variant="ghost" onClick={() => setTaking(true)}>
          Take a step
        </ActionButton>
      }
    >
      <QueryView
        query={steps}
        what="the steps taken"
        isEmpty={(found) => found.enforcements.length === 0}
        empty={<Empty title="Nothing yet">Warnings, limits, suspensions and bans show here, with why.</Empty>}
      >
        {(found) => (
          <>
            <p className="text-small">
              Strikes that still count: {found.renterStrikes ?? 0} as a renter, {found.hostStrikes ?? 0} as a
              host.
            </p>
            <Table caption="Steps taken" list="enforcements" columns={STEP_COLUMNS}>
              {found.enforcements.map((each) => (
                <Row key={each.id}>
                  <Cell>
                    <RelativeTime value={each.createdAt} />
                  </Cell>
                  <Cell>{each.role}</Cell>
                  <Cell>
                    {each.step}
                    {each.limitKind && (
                      <span className="block text-caption text-text-2">{each.limitKind}</span>
                    )}
                  </Cell>
                  <Cell className="max-w-64">{each.reason ?? "—"}</Cell>
                  <Cell>{each.endsAt ? formatDateTime(each.endsAt) : "—"}</Cell>
                  <Cell>
                    <StatusChip size="sm" domain="enforcement" state={each.state} />
                    {each.appealed && <span className="block text-caption text-text-2">Appealed</span>}
                  </Cell>
                  <Cell>
                    {each.state === "active" && can("enforcement.lift") && (
                      <Button variant="quiet" onClick={() => setLifting(each)}>
                        Lift<span className="sr-only"> {each.step}</span>
                      </Button>
                    )}
                  </Cell>
                </Row>
              ))}
            </Table>
          </>
        )}
      </QueryView>
      <ActionSheet
        open={taking}
        onOpenChange={setTaking}
        title="Take this step?"
        change="It happens now, and the person is told why, with a link to appeal. Lifting it later goes to another admin."
        confirm="Take it"
        destructive
        extra={
          <div className="flex flex-wrap items-end gap-2">
            <SmallSelect
              label="As a"
              value={role}
              onChange={setRole}
              options={[
                ["renter", "Renter"],
                ["host", "Host"],
              ]}
            />
            <SmallSelect label="Step" value={step} onChange={setStep} options={STEPS} />
            {step === "limit" && (
              <SmallSelect label="Limit" value={limit} onChange={setLimit} options={LIMITS} />
            )}
            {step === "limit" && limit === "session-hours-cap" && (
              <SmallField label="Hours a week" value={hours} onChange={setHours} inputMode="numeric" />
            )}
            {(step === "limit" || step === "suspend") && (
              <SmallField
                label={step === "suspend" ? "Until" : "Until (optional)"}
                type="date"
                value={until}
                onChange={setUntil}
              />
            )}
          </div>
        }
        onConfirm={async (reason) => {
          await adminData(
            adminApi.POST("/admin/v1/enforcements", {
              body: {
                userId: id,
                template: `${role}-${step}`,
                reason,
                ...(step === "limit" ? { limitKind: limit } : {}),
                ...(step === "limit" && limit === "session-hours-cap" ? { limitHours: Number(hours) } : {}),
                ...(until ? { endsAt: new Date(until).toISOString() } : {}),
              },
            }),
          );
          setTaking(false);
          await refresh();
        }}
      />
      <ActionSheet
        open={lifting !== null}
        onOpenChange={(open) => {
          if (!open) {
            setLifting(null);
          }
        }}
        title="Lift this?"
        change="Another admin approves it first."
        confirm="Ask to lift it"
        {...(lifting ? { record: lifting } : {})}
        onConfirm={async (reason) => {
          if (lifting) {
            await adminData(
              adminApi.POST("/admin/v1/enforcements/{id}/lift", {
                params: { path: { id: lifting.id } },
                body: { reason },
              }),
            );
            await refresh();
          }
        }}
      />
    </Section>
  );
}

const APPEAL_COLUMNS = ["Sent", "Person", "State", "Who decided", ""];

/** Appeals (ADR 0053): each with the step it's about and the person's history, decided by someone else. */
export function Appeals() {
  const id = new URLSearchParams(useSearch()).get("id");
  return id ? <AppealRecord id={id} /> : <AppealList />;
}

function AppealList() {
  const [state, setState] = useListParam("state", "open");
  const view = useListView();
  const appeals = useInfiniteQuery({
    queryKey: [ADMIN, "appeals", state, view],
    queryFn: ({ pageParam }) =>
      adminData(
        adminApi.GET("/admin/v1/appeals", {
          params: { query: { state, ...view, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  return (
    <AdminPage title="Appeals">
      <div className="flex">
        <SmallSelect
          label="Show"
          value={state}
          onChange={setState}
          options={[
            ["open", "Waiting for a decision"],
            ["upheld", "Kept"],
            ["reversed", "Reversed"],
          ]}
        />
      </div>
      <ListBar list="appeals" tags={false} extra={{ state }} />
      <PagedView
        query={appeals}
        what="the appeals"
        items={(page) => page.appeals}
        empty={<Empty title="No appeals here">A person appeals from the link we send with a decision.</Empty>}
      >
        {(rows) => (
          <Table
            caption="Appeals"
            list="appeals"
            sorts={{ Sent: "createdAt", State: "state" }}
            columns={APPEAL_COLUMNS}
          >
            {rows.map((appeal) => (
              <Row key={appeal.id}>
                <Cell>
                  <RelativeTime value={appeal.createdAt} />
                </Cell>
                <Cell>
                  <Id value={appeal.userId} page="user" />
                </Cell>
                <Cell>
                  <StatusChip size="sm" domain="appeal" state={appeal.state} />
                  {appeal.sameReviewer && <Tag>Same reviewer</Tag>}
                </Cell>
                <Cell>{appeal.decidedBy ? <Id value={appeal.decidedBy} page="user" /> : "—"}</Cell>
                <Cell>
                  <RowLink href={`/admin/appeals/?id=${appeal.id}`}>
                    Open<span className="sr-only"> this appeal</span>
                  </RowLink>
                </Cell>
              </Row>
            ))}
          </Table>
        )}
      </PagedView>
    </AdminPage>
  );
}

function AppealRecord({ id }: { id: string }) {
  const client = useQueryClient();
  const record = useQuery({
    queryKey: [ADMIN, "appeal", id],
    queryFn: () => adminData(adminApi.GET("/admin/v1/appeals/{id}", { params: { path: { id } } })),
  });
  const [outcome, setOutcome] = useState<"uphold" | "reverse" | null>(null);
  const [opening, setOpening] = useState<string | null>(null);
  return (
    <AdminPage title="Appeal" trail={[{ label: "Appeals", href: "/admin/appeals/" }]}>
      <QueryView query={record} what="this appeal">
        {(found) => (
          <>
            <Section title="What they said">
              <p className="text-body whitespace-pre-wrap">{found.appeal.body ?? "—"}</p>
              {found.appeal.attachments.length > 0 && (
                <div className="flex flex-col gap-1 text-small">
                  <p className="text-text-2">
                    Files they attached. They aren&apos;t checked for malware: each opens as a download, and
                    each opening is logged.
                  </p>
                  <div className="flex flex-wrap gap-2">
                    {found.appeal.attachments.map((file, index) => (
                      <ActionButton
                        key={file}
                        permission="appeals.decide"
                        variant="quiet"
                        onClick={() => setOpening(file)}
                      >
                        Open file {index + 1}
                      </ActionButton>
                    ))}
                  </div>
                </div>
              )}
            </Section>
            <Section title="The decision they're appealing">
              <Fields
                items={[
                  ["Step", found.enforcement.step],
                  ["Why", found.enforcement.reason ?? "—"],
                  ["Under", found.enforcement.clause],
                  ["By", <Id key="by" value={found.enforcement.enforcedBy} page="user" />],
                  ["When", formatDateTime(found.enforcement.createdAt)],
                ]}
              />
            </Section>
            <Section title="Everything else on this account">
              <Table caption="History" columns={["When", "As", "Step", "Why", "State"]}>
                {found.history.map((each) => (
                  <Row key={each.id}>
                    <Cell>
                      <RelativeTime value={each.createdAt} />
                    </Cell>
                    <Cell>{each.role}</Cell>
                    <Cell>{each.step}</Cell>
                    <Cell className="max-w-64">{each.reason ?? "—"}</Cell>
                    <Cell>{word(each.state)}</Cell>
                  </Row>
                ))}
              </Table>
            </Section>
            {found.appeal.sameReviewer && (
              <Banner tone="warning">Same reviewer: whoever took this step also decided its appeal.</Banner>
            )}
            {found.appeal.state === "open" ? (
              <div className="flex flex-wrap gap-2">
                <ActionButton
                  permission="appeals.decide"
                  variant="ghost"
                  onClick={() => setOutcome("uphold")}
                >
                  Keep the decision
                </ActionButton>
                <ActionButton
                  permission="appeals.decide"
                  variant="ghost"
                  onClick={() => setOutcome("reverse")}
                >
                  Reverse it
                </ActionButton>
              </div>
            ) : (
              <p className="text-small">
                Decided: {found.appeal.state}. {found.appeal.decisionReason}
              </p>
            )}
            <ActionSheet
              open={opening !== null}
              onOpenChange={(open) => {
                if (!open) {
                  setOpening(null);
                }
              }}
              title="Open this file?"
              change="It downloads, through a link that works for 5 minutes. Opening it goes in the access log with your reason."
              confirm="Open it"
              onConfirm={async (reason) => {
                if (opening) {
                  const link = await adminData(
                    adminApi.POST("/admin/v1/appeals/{id}/attachments/{upload}/open", {
                      params: { path: { id, upload: opening } },
                      body: { reason },
                    }),
                  );
                  window.location.assign(link.url);
                  setOpening(null);
                }
              }}
            />
            <ActionSheet
              open={outcome !== null}
              onOpenChange={(open) => {
                if (!open) {
                  setOutcome(null);
                }
              }}
              title={outcome === "reverse" ? "Reverse this?" : "Keep this decision?"}
              change={
                outcome === "reverse"
                  ? "Another admin approves the lift; then the person is told."
                  : "The person is told, with your reason."
              }
              confirm={outcome === "reverse" ? "Ask to reverse it" : "Keep it"}
              record={found.appeal}
              onConfirm={async (reason) => {
                await adminData(
                  adminApi.POST("/admin/v1/appeals/{id}/decide", {
                    params: { path: { id } },
                    body: { outcome: outcome ?? "uphold", reason },
                  }),
                );
                setOutcome(null);
                await client.invalidateQueries({ queryKey: [ADMIN] });
              }}
            />
          </>
        )}
      </QueryView>
    </AdminPage>
  );
}
