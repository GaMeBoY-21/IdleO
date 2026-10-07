import type { AuthAdapter, AuthState, SignedOutReason } from "./types";

/**
 * The signed-in state for the whole app, over whichever adapter the build has. Components read it with
 * useAuth(); the API client asks it for ID tokens, and signs out through it when a token is refused twice.
 */
export class AuthStore {
  private state: AuthState = { status: "loading" };
  private readonly listeners = new Set<() => void>();
  private pendingReason: SignedOutReason | null = null;
  private adapter: AuthAdapter | null = null;
  private started: Promise<AuthAdapter> | null = null;

  private readonly load: () => Promise<AuthAdapter>;

  constructor(load: () => Promise<AuthAdapter>) {
    this.load = load;
  }

  /** Loads the adapter once and starts watching the user. */
  start(): Promise<AuthAdapter> {
    this.started ??= this.load().then((adapter) => {
      this.adapter = adapter;
      adapter.watch((next) => {
        if (next.status === "signed-out" && next.reason === null && this.pendingReason !== null) {
          next = { status: "signed-out", reason: this.pendingReason };
        }
        if (next.status === "signed-in") {
          this.pendingReason = null;
        }
        this.set(next);
      });
      return adapter;
    });
    return this.started;
  }

  getState = (): AuthState => this.state;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  async getIdToken(forceRefresh = false): Promise<string | null> {
    const adapter = await this.start();
    return adapter.getIdToken(forceRefresh);
  }

  /** Signs out; `expired` when the server stopped accepting the session, so the sign-in page can say so. */
  async signOut(reason: SignedOutReason = "signed-out"): Promise<void> {
    const adapter = await this.start();
    this.pendingReason = reason;
    await adapter.signOut();
    this.set({ status: "signed-out", reason });
  }

  adapterNow(): Promise<AuthAdapter> {
    return this.start();
  }

  private set(next: AuthState): void {
    this.state = next;
    this.listeners.forEach((listener) => listener());
  }
}
