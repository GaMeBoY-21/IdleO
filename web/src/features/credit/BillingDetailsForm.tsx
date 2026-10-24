import { useState } from "react";
import { ApiError } from "@/api/errors";
import { useSaveBillingDetails, type BillingDetails } from "@/api/wallet";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { Select } from "@/components/ui/Inputs";

/**
 * The billing details form (ADR 0039), a file of its own: only it on the Credit page needs `Inputs`, and it draws only
 * once the details have arrived, after the page's first paint, so its file never holds that paint back.
 */
export function DetailsForm({ details }: { details: BillingDetails }) {
  const save = useSaveBillingDetails();
  const [stateCode, setStateCode] = useState(details.details?.stateCode ?? "");
  const [gstin, setGstin] = useState(details.details?.gstin ?? "");
  const [businessName, setBusinessName] = useState(details.details?.businessName ?? "");
  const [saved, setSaved] = useState(false);
  // The server's words for a field start with its verb: "isn't a valid GSTIN: check its 15 characters."
  const fieldError = (field: string) => {
    const found =
      save.error instanceof ApiError ? save.error.errors.find((error) => error.field === field) : undefined;
    return found ? `It ${found.detail}` : undefined;
  };
  const other =
    save.error && !fieldError("stateCode") && !fieldError("gstin") && !fieldError("businessName")
      ? save.error instanceof ApiError
        ? save.error.message
        : "We couldn't save them. Try again."
      : null;

  const submit = () => {
    setSaved(false);
    save.mutate(
      {
        stateCode,
        gstin: gstin.trim() === "" ? null : gstin.trim().toUpperCase(),
        businessName: businessName.trim() === "" ? null : businessName.trim(),
      },
      { onSuccess: () => setSaved(true) },
    );
  };

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
      className="flex flex-col gap-4"
      noValidate
    >
      <Select
        label="Your state"
        value={stateCode}
        required
        error={fieldError("stateCode")}
        hint="Where you are, for your invoices."
        onChange={(event) => setStateCode(event.target.value)}
      >
        <option value="" disabled>
          Choose a state
        </option>
        {details.states.map((state) => (
          <option key={state.code} value={state.code}>
            {state.name}
          </option>
        ))}
      </Select>
      <Field
        label="GSTIN (optional)"
        value={gstin}
        autoComplete="off"
        spellCheck={false}
        maxLength={15}
        error={fieldError("gstin")}
        hint="For a business that claims the GST back."
        onChange={(event) => setGstin(event.target.value)}
      />
      <Field
        label="Business name (optional)"
        value={businessName}
        autoComplete="organization"
        maxLength={200}
        error={fieldError("businessName")}
        hint="As it should appear on the invoice."
        onChange={(event) => setBusinessName(event.target.value)}
      />
      {/* Two lines, whichever it says, as its skeleton is. */}
      <p className="min-h-9 text-caption text-text-muted">
        {details.gstPercent > 0
          ? `What you pay includes GST at ${details.gstPercent}%; your invoice shows it.`
          : "We aren't registered for GST yet, so you get a receipt, not a tax invoice."}
      </p>
      <div className="flex items-center gap-3">
        <Button type="submit" variant="secondary" busy={save.isPending} disabled={stateCode === ""}>
          Save
        </Button>
        <p
          role={other ? "alert" : "status"}
          className={other ? "text-small text-danger" : "text-small text-text-muted"}
        >
          {other ?? (saved ? "Saved." : "")}
        </p>
      </div>
    </form>
  );
}
