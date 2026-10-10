import { useSearch } from "wouter";

/** The machine a hosting screen is about: `?id=` in its address, as the session screen has. */
export function useHostId(): string | null {
  return new URLSearchParams(useSearch()).get("id");
}
