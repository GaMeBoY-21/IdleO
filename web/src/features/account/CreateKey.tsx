import { Check, Copy } from "lucide-react";
import { useRef, useState } from "react";
import { useCreateKey, type CreatedApiKey } from "@/api/keys";
import { Tile } from "@/components/ui/Tile";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { RadioGroup } from "@/components/ui/Inputs";
import { problemMessage } from "@/lib/problems";

const MAX_NAME = 60;

/** What's wrong with a key's name before it's sent, or undefined: 1 to 60 characters once the spaces are gone. */
export function keyNameProblem(name: string): string | undefined {
  const trimmed = name.trim();
  if (trimmed.length === 0) {
    return "Give the key a name, so you know what it's for.";
  }
  return trimmed.length > MAX_NAME ? `Use at most ${MAX_NAME} characters.` : undefined;
}

/**
 * "Create a key": a name and a scope. The new key is shown once, here, with Copy and a plain warning that it won't be
 * shown again. It lives only in this component's state: not in the query cache, not in storage, and gone when
 * "Done" is pressed or the page is left.
 */
export function CreateKey() {
  const create = useCreateKey();
  const [name, setName] = useState("");
  const [scope, setScope] = useState<"full" | "read">("full");
  const [flagged, setFlagged] = useState(false);
  const [made, setMade] = useState<CreatedApiKey | null>(null);
  const [copied, setCopied] = useState(false);
  // The key belongs to the form as it stands: pressing again, or a retry after a lost answer, sends the same one.
  const attempt = useRef<{ body: string; key: string } | null>(null);
  const problem = flagged ? keyNameProblem(name) : undefined;

  const submit = async () => {
    setFlagged(true);
    if (keyNameProblem(name) !== undefined) {
      return;
    }
    const body = JSON.stringify({ name: name.trim(), scope });
    if (attempt.current?.body !== body) {
      attempt.current = { body, key: crypto.randomUUID() };
    }
    try {
      const created = await create.mutateAsync({
        name: name.trim(),
        scope,
        idempotencyKey: attempt.current.key,
      });
      setMade(created);
      attempt.current = null;
      setName("");
      setFlagged(false);
      setCopied(false);
      // The mutation holds its answer too, which is the key: it isn't kept.
      create.reset();
    } catch {
      // The error shows from the mutation's state; the same key is kept for the next try.
    }
  };

  if (made) {
    return (
      <Tile as="section" aria-label="Your new key" className="flex flex-col gap-3">
        <p className="text-option font-semibold">Made &ldquo;{made.name}&rdquo;</p>
        <Banner tone="warning">Copy it now. You won&apos;t see it again.</Banner>
        <p
          className="rounded-control bg-ground p-4 text-small break-all select-all tabular"
          data-testid="new-key"
        >
          {made.key}
        </p>
        <div className="flex flex-col gap-2 md:flex-row">
          <Button
            variant="secondary"
            block
            onClick={() => void navigator.clipboard.writeText(made.key).then(() => setCopied(true))}
          >
            {copied ? (
              <Check aria-hidden="true" className="size-4" strokeWidth={2} />
            ) : (
              <Copy aria-hidden="true" className="size-4" strokeWidth={1.75} />
            )}
            {copied ? "Copied" : "Copy key"}
          </Button>
          <Button block onClick={() => setMade(null)}>
            Done
          </Button>
        </div>
      </Tile>
    );
  }

  return (
    <form
      noValidate
      aria-label="Create a key"
      data-tile=""
      className="flex flex-col gap-2 rounded-tile bg-fill p-4.5"
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      <p className="text-option font-semibold">Create a key</p>
      <Field
        label="Name"
        value={name}
        maxLength={MAX_NAME + 20}
        placeholder="CI on my laptop"
        autoComplete="off"
        error={problem}
        onChange={(event) => {
          setName(event.target.value);
          create.reset();
        }}
      />
      <RadioGroup
        legend="Access"
        quiet
        options={[
          { value: "full", label: "Full access" },
          { value: "read", label: "Read only" },
        ]}
        inputProps={(value) => ({
          name: "scope",
          checked: scope === value,
          onChange: () => setScope(value as "full" | "read"),
        })}
      />
      <p className="text-caption text-text-muted">
        Full access can book, stop and cancel sessions and revoke keys. Read only can only look.
      </p>
      {create.isError && <Banner tone="danger">{problemMessage(create.error)}</Banner>}
      <div>
        <Button type="submit" busy={create.isPending}>
          Create key
        </Button>
      </div>
    </form>
  );
}
