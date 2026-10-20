import { useQueries } from "@tanstack/react-query";
import { uploadInfo, type Session, type Template } from "@/api/queries";
import { sessionInputs, type InputFact } from "@/lib/inputs";

/**
 * Every input of a session as the screen shows it (src/lib/inputs.ts): its label from the template, and for an upload its
 * name, size and files, read from the upload (held back until `enabled`, which is after the first paint). One upload's
 * size is never taken for another's: a fine-tune's 4 GB model and its 68 KB examples are two lines, and one total.
 */
export function useInputFacts(
  session: Session | undefined,
  template: Template | undefined,
  enabled: boolean,
): InputFact[] {
  const given = session ? sessionInputs(session) : [];
  const uploads = useQueries({
    queries: given.map(({ uploadId }) => ({
      ...uploadInfo(uploadId ?? ""),
      enabled: enabled && uploadId !== null,
    })),
  });
  return given.map((input, index) => {
    const upload = uploads[index]?.data?.upload;
    return {
      name: input.name,
      label: template?.inputs.find((candidate) => candidate.name === input.name)?.label ?? input.name,
      link: input.url !== null,
      filename: upload?.name ?? null,
      bytes: upload?.sizeBytes ?? null,
      files: upload?.fileCount ?? null,
    };
  });
}
