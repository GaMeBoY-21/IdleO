import { useMemo, type ReactNode } from "react";
import { useSession, useUploadInfo, type Template } from "@/api/queries";
import { BookForm, type BookFormProps } from "./BookForm";
import { defaultValues, readDraft, valuesFrom } from "./form";
import { readyFile } from "./useUpload";
import { sessionInput } from "@/lib/inputs";

/**
 * What the form starts with (src/features/book/Book.tsx): an earlier session (Run again and Book again, `&from=`), the
 * draft kept in this tab, or the template's defaults. A kept upload is used again only while it's still ready. This
 * and the form are a chunk of their own, so Book's frame and the services paint without them.
 */
export function Prepared({
  template,
  from,
  fallback,
  ...props
}: Omit<BookFormProps, "initial" | "initialFile"> & {
  template: Template;
  from: string | null;
  fallback: ReactNode;
}) {
  const earlier = useSession(from);
  const draft = useMemo(() => (from === null ? readDraft(template.id) : null), [from, template.id]);
  const source = from !== null && earlier.data?.templateId === template.id ? earlier.data : null;
  const uploadId = source ? (sessionInput(source)?.uploadId ?? null) : (draft?.upload?.id ?? null);
  const upload = useUploadInfo(uploadId);

  if ((from !== null && earlier.isPending) || (uploadId !== null && upload.isPending)) {
    return fallback;
  }
  const initial = source ? valuesFrom(template, source) : (draft?.values ?? defaultValues(template));
  const initialFile =
    upload.data?.status === "ready"
      ? readyFile(upload.data.id, upload.data.filename, upload.data.sizeBytes)
      : null;
  return <BookForm template={template} initial={initial} initialFile={initialFile} {...props} />;
}
