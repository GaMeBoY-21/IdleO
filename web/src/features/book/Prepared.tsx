import { useQueries } from "@tanstack/react-query";
import { useMemo, type ReactNode } from "react";
import { uploadInfo, useSession, type Template } from "@/api/queries";
import { readDraft } from "./draft";
import { defaultValues, valuesFrom, type BookValues } from "./form";
import { ServiceForm, type ServiceFormProps } from "./ServiceForm";
import { readyUpload, type InputUpload } from "./useUploads";

/** A draft's answers over the template's defaults, so an input or question added since still has its own. */
function fromDraft(template: Template, kept: BookValues): BookValues {
  const defaults = defaultValues(template);
  return {
    ...defaults,
    ...kept,
    inputs: { ...defaults.inputs, ...kept.inputs },
    presets: { ...defaults.presets, ...kept.presets },
    params: { ...defaults.params, ...kept.params },
  };
}

/**
 * What the form starts with (src/features/book/Book.tsx): an earlier session (Run again and Book again, `&from=`), the
 * draft kept in this tab, or the template's defaults. Each input's earlier upload is used again only while it's still
 * ready. This and the form are a chunk of their own, so Book's frame and the services paint without them.
 */
export function Prepared({
  template,
  from,
  fallback,
  ...props
}: Omit<ServiceFormProps, "initial" | "initialUploads"> & {
  from: string | null;
  fallback: ReactNode;
}) {
  const earlier = useSession(from);
  const draft = useMemo(() => (from === null ? readDraft(template.id) : null), [from, template.id]);
  const source = from !== null && earlier.data?.templateId === template.id ? earlier.data : null;
  const kept = template.inputs.flatMap((input) => {
    const id = source ? source.inputs[input.name]?.uploadId : draft?.uploads[input.name]?.id;
    return id ? [{ name: input.name, id }] : [];
  });
  const infos = useQueries({ queries: kept.map(({ id }) => uploadInfo(id)) });

  if ((from !== null && earlier.isPending) || infos.some((info) => info.isPending)) {
    return fallback;
  }
  const initial = source
    ? valuesFrom(template, source)
    : draft
      ? fromDraft(template, draft.values)
      : defaultValues(template);
  const initialUploads: Record<string, InputUpload | null> = Object.fromEntries(
    template.inputs.map((input) => [input.name, null]),
  );
  kept.forEach(({ name }, i) => {
    const upload = infos[i]?.data?.upload;
    if (upload?.status === "ready") {
      initialUploads[name] = readyUpload(upload.id, upload.name, upload.sizeBytes, upload.fileCount);
    }
  });
  return <ServiceForm template={template} initial={initial} initialUploads={initialUploads} {...props} />;
}
