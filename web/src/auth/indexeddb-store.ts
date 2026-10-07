import type { StateStore } from "oidc-client-ts";

/**
 * oidc-client-ts's user store, in IndexedDB rather than web storage: the same place the Firebase SDK keeps its
 * session, shared by the tabs of one origin and never in localStorage (ADR 0018).
 */
export class IndexedDbStore implements StateStore {
  private db: Promise<IDBDatabase> | null = null;
  private readonly name = "idleo-auth";
  private readonly table = "oidc";

  private open(): Promise<IDBDatabase> {
    this.db ??= new Promise((resolve, reject) => {
      const request = indexedDB.open(this.name, 1);
      request.onupgradeneeded = () => request.result.createObjectStore(this.table);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error("IndexedDB wouldn't open"));
    });
    return this.db;
  }

  private async run<T>(mode: IDBTransactionMode, work: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    const db = await this.open();
    return new Promise((resolve, reject) => {
      const request = work(db.transaction(this.table, mode).objectStore(this.table));
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error ?? new Error("IndexedDB request failed"));
    });
  }

  async set(key: string, value: string): Promise<void> {
    await this.run("readwrite", (store) => store.put(value, key));
  }

  async get(key: string): Promise<string | null> {
    return ((await this.run("readonly", (store) => store.get(key))) as string | undefined) ?? null;
  }

  async remove(key: string): Promise<string | null> {
    const value = await this.get(key);
    await this.run("readwrite", (store) => store.delete(key));
    return value;
  }

  async getAllKeys(): Promise<string[]> {
    return (await this.run("readonly", (store) => store.getAllKeys())).map(String);
  }
}
