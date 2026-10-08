import { useFormContext, useWatch } from "react-hook-form";
import type { Template } from "@/api/queries";
import { Field } from "@/components/ui/Field";
import { RadioGroup } from "@/components/ui/Inputs";
import type { BookValues } from "./form";
import { FilePicker } from "./FilePicker";
import type { ChosenFile } from "./useUpload";

const MIB = 1024 * 1024;

/**
 * "Your data" (docs/design/redesign/book-laptop.html): the session's input, as the template takes it: a file,
 * uploaded as soon as it's chosen; a link the job fetches itself; or a choice of the two. The template's own words
 * for it ("Dataset", and its help) are the line under the title.
 */
export function InputSection({
  template,
  file,
  onChoose,
  onRemove,
  missing,
  laptop,
}: {
  template: Template;
  file: ChosenFile | null;
  onChoose: (file: File) => void;
  onRemove: () => void;
  missing: boolean;
  laptop: boolean;
}) {
  const form = useFormContext<BookValues>();
  const kind = useWatch({ control: form.control, name: "inputKind" });
  const { input } = template;
  const about = [input.label, input.help].filter(Boolean).join(": ");

  return (
    <section aria-labelledby="input-title" className="flex flex-col gap-3">
      <div className="flex flex-col gap-0.5">
        <h2 id="input-title" className={laptop ? "text-section font-bold" : "text-option font-semibold"}>
          Your data
          {input.accepts === "optional" && <span className="font-medium text-text-muted"> (optional)</span>}
        </h2>
        {about && <p className="text-small text-text-muted">{about}</p>}
      </div>
      {input.upload && input.url && (
        <RadioGroup
          legend="How you'll give it"
          options={[
            { value: "upload", label: "Upload a file" },
            { value: "url", label: "Give a link" },
          ]}
          inputProps={() => form.register("inputKind")}
        />
      )}
      {kind === "upload" && input.upload ? (
        <FilePicker
          maxBytes={input.maxSizeMb === null || input.maxSizeMb === undefined ? null : input.maxSizeMb * MIB}
          templateName={template.name}
          file={file}
          onChoose={onChoose}
          onRemove={onRemove}
          missing={missing}
        />
      ) : (
        <Field
          label="Link to the file"
          type="url"
          inputMode="url"
          autoComplete="off"
          placeholder="https://"
          hint="The session fetches it itself. It must start with https://"
          error={
            form.formState.errors.inputUrl?.message ??
            (missing ? "Enter a link, or upload a file." : undefined)
          }
          {...form.register("inputUrl")}
        />
      )}
    </section>
  );
}
