/**
 * Every API request carries the caller's ID token (ADR 0009). When the server refuses it (401), the token is
 * refreshed and the request tried once more; refused again, the person is signed out cleanly, with a reason the
 * sign-in screen can show. A request that never had a token signs out the same way.
 */
export interface TokenSource {
  getIdToken(forceRefresh: boolean): Promise<string | null>;
  signOut(reason: "expired"): Promise<void>;
}

export class SignedOutError extends Error {
  constructor() {
    super("signed out");
    this.name = "SignedOutError";
  }
}

export function authedFetch(tokens: TokenSource, fetcher: (request: Request) => Promise<Response> = fetch) {
  const withToken = (request: Request, token: string) => {
    const headers = new Headers(request.headers);
    headers.set("Authorization", `Bearer ${token}`);
    return new Request(request, { headers });
  };

  return async (input: Request): Promise<Response> => {
    const retry = input.clone();
    const token = await tokens.getIdToken(false);
    if (token === null) {
      await tokens.signOut("expired");
      throw new SignedOutError();
    }
    const first = await fetcher(withToken(input, token));
    if (first.status !== 401) {
      return first;
    }
    const fresh = await tokens.getIdToken(true);
    if (fresh === null) {
      await tokens.signOut("expired");
      throw new SignedOutError();
    }
    const second = await fetcher(withToken(retry, fresh));
    if (second.status === 401) {
      await tokens.signOut("expired");
      throw new SignedOutError();
    }
    return second;
  };
}
