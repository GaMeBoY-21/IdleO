/** The part of XMLHttpRequest an upload uses, so tests can stand in for it. */
export interface PartRequest {
  open(method: string, url: string): void;
  send(body: Blob): void;
  abort(): void;
  getResponseHeader(name: string): string | null;
  readonly status: number;
  upload: { onprogress: ((event: { loaded: number }) => void) | null };
  onload: (() => void) | null;
  onerror: (() => void) | null;
  onabort: (() => void) | null;
}

export class Cancelled extends Error {}

export class PartFailed extends Error {
  readonly status: number;
  constructor(status: number) {
    super(`part upload failed with ${status}`);
    this.status = status;
  }
}

/**
 * PUTs one part straight to storage through its pre-signed link, with XMLHttpRequest, because fetch can't report
 * upload progress. Resolves with the ETag storage gave it; `onSent` hears the bytes storage has taken so far.
 */
export function putPart(
  request: PartRequest,
  url: string,
  body: Blob,
  onSent: (bytes: number) => void,
): Promise<string> {
  return new Promise((resolve, reject) => {
    request.upload.onprogress = (event) => onSent(Math.min(event.loaded, body.size));
    request.onload = () => {
      const etag = request.getResponseHeader("ETag");
      if (request.status >= 200 && request.status < 300 && etag) {
        onSent(body.size);
        resolve(etag);
      } else {
        reject(new PartFailed(request.status));
      }
    };
    request.onerror = () => reject(new PartFailed(0));
    request.onabort = () => reject(new Cancelled("aborted"));
    request.open("PUT", url);
    request.send(body);
  });
}
