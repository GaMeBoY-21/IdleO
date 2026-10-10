import { useFormContext, useWatch } from "react-hook-form";
import { Field } from "@/components/ui/Field";
import { RadioGroup } from "@/components/ui/Inputs";
import type { BookValues, InputHow } from "./form";
import { FilePicker } from "./FilePicker";
import type { Chosen } from "./chosen-files";
import { MyFilesChoice } from "./MyFilesChoice";
import { StepTitle } from "./StepTitle";
import type { TemplateInput } from "./steps";
import { readyUpload, type InputUpload } from "./useUploads";

const HOWS: { value: InputHow; label: string }[] = [
  { value: "upload", label: "Upload" },
  { value: "files", label: "From My files" },
  { value: "link", label: "Link" },
];

/**
 * One input as a numbered step (docs/design/redesign/book-finetune-laptop.html "1. Your model"): its label and help
 * from the catalog, and the ways to give it the template allows: upload it now (a folder or a file, resumable),
 * pick it from My files, or paste a link the job fetches itself inside its sandbox (ADR 0019).
 */
export function StepInput({
  number,
  input,
  upload,
  missing,
  laptop,
  onChoose,
  onPick,
  onRemove,
  onRetry,
}: {
  number: number;
  input: TemplateInput;
  upload: InputUpload | null;
  missing: boolean;
  laptop: boolean;
  onChoose: (chosen: Chosen) => void;
  onPick: (ready: InputUpload) => void;
  onRemove: () => void;
  onRetry: () => void;
}) {
  const form = useFormContext<BookValues>();
  const how = useWatch({ control: form.control, name: `inputs.${input.name}.how` });
  const errors = form.formState.errors.inputs?.[input.name];
  const refused = errors?.message ?? errors?.url?.message;
  const offered = HOWS.filter(({ value }) => (value === "link" ? input.link : input.upload));
  const id = `input-${input.name}`;

  return (
    <section aria-labelledby={id} className="flex flex-col gap-3">
      <StepTitle id={id} number={number} laptop={laptop} optional={!input.required} help={input.help}>
        {input.label}
      </StepTitle>
      {offered.length > 1 && (
        <RadioGroup
          legend={`How you'll give ${input.label.toLowerCase()}`}
          options={offered}
          hideLegend
          quiet
          inputProps={() => form.register(`inputs.${input.name}.how`)}
        />
      )}
      {how === "link" ? (
        <Field
          label={input.kind === "folder" ? "Link to it" : "Link to the file"}
          type="url"
          inputMode="url"
          autoComplete="off"
          placeholder="https://"
          hint="The GPU downloads it itself. It must start with https://"
          error={refused ?? (missing ? "Enter a link, or choose another way." : undefined)}
          {...form.register(`inputs.${input.name}.url`)}
        />
      ) : how === "files" ? (
        <MyFilesChoice
          input={input}
          chosenId={upload?.progress.phase === "ready" ? upload.progress.uploadId : null}
          onPick={(picked) => onPick(readyUpload(picked.id, picked.name, picked.sizeBytes, picked.fileCount))}
        />
      ) : (
        <FilePicker
          input={input}
          upload={upload}
          onChoose={onChoose}
          onRemove={onRemove}
          onRetry={onRetry}
          missing={missing}
        />
      )}
      {how === "files" && (refused || (missing && !upload)) && (
        <p className="text-caption text-danger" role="alert">
          {refused ?? "Pick one, or upload it."}
        </p>
      )}
      {how === "upload" && refused && (
        <p className="text-caption text-danger" role="alert">
          {refused}
        </p>
      )}
    </section>
  );
}
