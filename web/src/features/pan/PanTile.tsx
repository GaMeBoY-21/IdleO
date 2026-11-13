import { useState, type ReactNode } from "react";
import { ApiError } from "@/api/errors";
import { useSubmitPan } from "@/api/pan";
import type { components } from "@/api/schema.gen";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { SectionHeader } from "@/components/ui/Headers";
import { Tile } from "@/components/ui/Tile";
import { maskedPan, PAN_SHAPE } from "@/lib/pan";
import { problemMessage } from "@/lib/problems";

type PanView = components["schemas"]["PanView"];

const PAN_STATE: Record<string, string> = {
  verified: "Verified",
  review: "We're checking it. You'll be paid once it's verified.",
  rejected: "Not accepted",
};

/**
 * The PAN tile (ADR 0040, ADR 0042): the PAN on file, its last four only, and whether it's verified; the form to give it
 * while there's none or it wasn't accepted. A host's payouts need it, and so do a renter's own images: the host site's
 * Payout details and the renter app's Your PAN both draw this one.
 */
export function PanTile({ pan, why }: { pan: PanView | null | undefined; why?: ReactNode }) {
  const submit = useSubmitPan();
  const [number, setNumber] = useState("");
  const [name, setName] = useState("");
  const [shown, setShown] = useState(false);
  const typed = number.trim().toUpperCase();
  const shapeError =
    shown && !PAN_SHAPE.test(typed) ? "A PAN is five letters, four digits and a letter." : undefined;
  const fieldError = (field: string) =>
    submit.error instanceof ApiError
      ? submit.error.errors.find((error) => error.field === field)?.detail
      : undefined;
  const editable = pan == null || pan.state === "rejected";
  return (
    <Tile as="section" aria-labelledby="pan-title" className="flex flex-col gap-4">
      <SectionHeader id="pan-title" description={why}>
        PAN
      </SectionHeader>
      {pan && (
        <div className="flex flex-col gap-1">
          <p className="text-card font-semibold tabular">
            {maskedPan(pan.last4)} · {pan.name}
          </p>
          <p className={pan.state === "verified" ? "text-small text-live" : "text-small text-ink-subtle"}>
            {PAN_STATE[pan.state] ?? pan.state}
            {pan.state === "rejected" && pan.reason && `: ${pan.reason}`}
          </p>
        </div>
      )}
      {editable && (
        <form
          noValidate
          className="flex flex-col gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            setShown(true);
            if (PAN_SHAPE.test(typed) && name.trim() !== "") {
              submit.mutate({ pan: typed, name: name.trim() });
            }
          }}
        >
          <Field
            label="PAN"
            value={number}
            autoComplete="off"
            spellCheck={false}
            maxLength={10}
            error={shapeError ?? fieldError("pan")}
            hint="10 characters, like ABCPE1234F. Only its last 4 are ever shown."
            onChange={(event) => setNumber(event.target.value)}
          />
          <Field
            label="Name exactly as on your PAN"
            value={name}
            autoComplete="name"
            maxLength={85}
            error={shown && name.trim() === "" ? "Write the name as it is on the card." : fieldError("name")}
            hint="Your UPI ID must be registered in this name."
            onChange={(event) => setName(event.target.value)}
          />
          <div className="flex items-center gap-2">
            <Button type="submit" variant="ghost" busy={submit.isPending}>
              Save PAN
            </Button>
            <p role="alert" className="text-small text-danger">
              {submit.error && !fieldError("pan") && !fieldError("name") ? problemMessage(submit.error) : ""}
            </p>
          </div>
        </form>
      )}
    </Tile>
  );
}
