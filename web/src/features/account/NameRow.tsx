import { useEffect, useRef, useState } from "react";
import { useRenameMe } from "@/api/account";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { problemMessage } from "@/lib/problems";

export const MAX_NAME = 60;

/** What's wrong with a name before it's sent, or undefined: 1 to 60 characters once the spaces around it are gone. */
export function nameProblem(name: string): string | undefined {
  const trimmed = name.trim();
  if (trimmed.length === 0) {
    return "Enter a name.";
  }
  return trimmed.length > MAX_NAME ? `Use at most ${MAX_NAME} characters.` : undefined;
}

/**
 * The display name, with its Edit button; editing opens in place, with one Save, and the name shown is only ever
 * what the server last answered.
 */
export function NameRow({ name }: { name: string }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(name);
  const [flagged, setFlagged] = useState(false);
  const rename = useRenameMe();
  const field = useRef<HTMLInputElement>(null);
  const problem = flagged ? nameProblem(draft) : undefined;

  // Opening the edit is the person's own action, so the cursor goes straight into it.
  useEffect(() => {
    if (editing) {
      field.current?.focus();
    }
  }, [editing]);

  const close = () => {
    setEditing(false);
    setFlagged(false);
    rename.reset();
  };

  if (!editing) {
    return (
      <div className={ROW}>
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <p className="text-caption text-text-2">Name</p>
          <p className="truncate text-option font-semibold" title={name}>
            {name}
          </p>
        </div>
        <Button
          variant="ghost"
          aria-label="Edit your name"
          onClick={() => {
            setDraft(name);
            setEditing(true);
          }}
        >
          Edit
        </Button>
      </div>
    );
  }

  return (
    <form
      noValidate
      className="flex flex-col gap-2 py-4"
      onSubmit={(event) => {
        event.preventDefault();
        setFlagged(true);
        const trimmed = draft.trim();
        if (nameProblem(draft) !== undefined) {
          return;
        }
        if (trimmed === name) {
          close();
          return;
        }
        rename.mutate(trimmed, { onSuccess: close });
      }}
    >
      <Field
        label="Name"
        value={draft}
        autoComplete="name"
        ref={field}
        error={problem}
        onChange={(event) => {
          setDraft(event.target.value);
          rename.reset();
        }}
      />
      {rename.isError && <Banner tone="danger">{problemMessage(rename.error)}</Banner>}
      <div className="flex gap-2">
        <Button type="submit" busy={rename.isPending}>
          Save
        </Button>
        <Button variant="quiet" onClick={close} disabled={rename.isPending}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

/** A row of the account's facts: a caption and the value. Edit rows share its height. */
export const ROW = "flex min-h-18 items-center gap-3 py-3";
