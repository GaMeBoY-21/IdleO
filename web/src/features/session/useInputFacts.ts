import { useQuery, useQueryClient } from "@tanstack/react-query";
import { uploadInfo, type Session, type Template } from "@/api/queries";
import { sessionInputs, type InputFact } from "@/lib/inputs";

/**
 * Every input of a session as the screen shows it (src/lib/inputs.ts): its label from the template, and for an upload its
 * name, size and files, read from the upload (held back until `enabled`, which is after the first paint). One upload's
 * size is never taken for another's: a fine-tune's 4 GB model and its 68 KB examples are two lines, and one total.
 *
 * One query for all of a session's uploads, each read through the cache that Book and My files share, rather than
 * `useQueries`: that hook is used only by Book's draft, and from here it would be a file of its own on the session screen's
 * way, another round trip on a slow connection (web/vite.config.ts).
 */
export function useInputFacts(
  session: Session | undefined,
  template: Template | undefined,
  enabled: boolean,
): InputFact[] {
  const client = useQueryClient();
  const given = session ? sessionInputs(session) : [];
  const ids = given.flatMap((input) => (input.uploadId === null ? [] : [input.uploadId]));
  const uploads = useQuery({
    queryKey: ["session-uploads", ids],
    enabled: enabled && ids.length > 0,
    queryFn: () => Promise.all(ids.map((id) => client.query({ ...uploadInfo(id), staleTime: 60_000 }))),
  });
  return given.map((input) => {
    const upload = uploads.data?.find((found) => found.upload.id === input.uploadId)?.upload;
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
