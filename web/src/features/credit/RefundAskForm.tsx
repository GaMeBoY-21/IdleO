import { useState } from "react";
import { useAskRefund } from "@/api/wallet";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { TextArea } from "@/components/ui/Inputs";
import { formatPaise, parseRupees } from "@/lib/money";
import { problemMessage } from "@/lib/problems";

/** Asking for bought credit back (ADR 0039): a file of its own, loaded when the renter presses "Ask for a refund". */
export function AskForm({ refundablePaise, onDone }: { refundablePaise: number; onDone: () => void }) {
  const ask = useAskRefund();
  const [amount, setAmount] = useState("");
  const [note, setNote] = useState("");
  const amountPaise = amount === "" ? null : parseRupees(amount);
  const amountError =
    amount !== "" && (amountPaise === null || amountPaise <= 0)
      ? "An amount in rupees, or leave it empty."
      : amountPaise !== null && amountPaise > refundablePaise
        ? `At most ${formatPaise(refundablePaise)}.`
        : undefined;

  const submit = () => {
    if (amountError) {
      return;
    }
    ask.mutate(
      {
        ...(amountPaise !== null ? { amountPaise } : {}),
        ...(note.trim() !== "" ? { note: note.trim() } : {}),
      },
      { onSuccess: onDone },
    );
  };

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
      className="flex flex-col gap-3"
      noValidate
    >
      <Field
        label="How much, in ₹"
        type="number"
        inputMode="decimal"
        step="0.01"
        min="0.01"
        value={amount}
        error={amountError}
        hint={`Leave it empty for all of it, ${formatPaise(refundablePaise)} now.`}
        onChange={(event) => setAmount(event.target.value)}
      />
      <TextArea
        label="Anything we should know (optional)"
        value={note}
        maxLength={2000}
        rows={3}
        onChange={(event) => setNote(event.target.value)}
      />
      {ask.isError && (
        <p role="alert" className="text-small text-danger">
          {problemMessage(ask.error)}
        </p>
      )}
      <div className="flex gap-2">
        <Button type="submit" variant="ghost" busy={ask.isPending}>
          Send request
        </Button>
        <Button variant="ghost" onClick={onDone} disabled={ask.isPending}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
