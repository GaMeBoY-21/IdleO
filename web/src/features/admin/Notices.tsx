import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { TextArea } from "@/components/ui/Inputs";
import { ActionSheet, SheetProblem } from "./ActionSheet";
import { adminApi, adminData } from "./api";
import { AdminPage, Section } from "./ui";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Notices (ADR 0037, docs/runbooks/breach.md): a written notice emailed to the people a breach touched, chosen by
 * account id, in words a person decided on. The audit log records who was told, by id, and why; never their addresses.
 */
export function Notices() {
  const [people, setPeople] = useState("");
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<{ sent: number; withoutAddress: string[] } | null>(null);
  const ids = people
    .split(/[\s,]+/)
    .map((id) => id.trim())
    .filter((id) => id !== "");

  return (
    <AdminPage title="Notices">
      <Section title="Tell people what happened">
        <p className="max-w-prose text-small text-text-muted">
          For a breach: what happened, what it means for them, what we've done and what they should do. Follow
          docs/runbooks/breach.md, and keep a copy of the words.
        </p>
        <TextArea
          label="Account ids, one per line"
          rows={4}
          value={people}
          onChange={(event) => setPeople(event.target.value)}
          hint={`${ids.length} ${ids.length === 1 ? "person" : "people"}`}
        />
        <Field
          label="Subject"
          value={subject}
          maxLength={200}
          onChange={(event) => setSubject(event.target.value)}
        />
        <TextArea
          label="The notice"
          rows={10}
          maxLength={10000}
          value={body}
          onChange={(event) => setBody(event.target.value)}
        />
        <Button className="self-start" onClick={() => setConfirming(true)}>
          Email the notice
        </Button>
        {result && (
          <p role="status" className="text-small">
            Emailed to {result.sent}.{" "}
            {result.withoutAddress.length > 0
              ? `${result.withoutAddress.length} have no address we can write to: ${result.withoutAddress.join(", ")}.`
              : ""}
          </p>
        )}
      </Section>
      <ActionSheet
        open={confirming}
        onOpenChange={setConfirming}
        title="Email the notice"
        change={`"${subject || "(no subject)"}" goes by email to ${ids.length} ${ids.length === 1 ? "person" : "people"}. It can't be taken back.`}
        confirm="Send it"
        destructive
        onConfirm={async (reason) => {
          if (ids.length === 0 || ids.some((id) => !UUID.test(id))) {
            throw new SheetProblem("Give each person's account id, one per line.");
          }
          if (subject.trim() === "" || body.trim() === "") {
            throw new SheetProblem("Write a subject and the notice.");
          }
          const sent = await adminData(
            adminApi.POST("/admin/v1/notices", { body: { userIds: ids, subject, body, reason } }),
          );
          setResult({ sent: sent.sent, withoutAddress: sent.withoutAddress });
        }}
      />
    </AdminPage>
  );
}
