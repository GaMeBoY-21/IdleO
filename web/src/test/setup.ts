import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";
import { loadProblemWords } from "@/lib/problems";

afterEach(() => cleanup());

// The app fetches them before an error reaches a screen (src/api/client.ts); a test may make an ApiError itself.
await loadProblemWords();

// jsdom has no matchMedia; components ask it for the layout (src/lib/media.ts), as a phone.
if (typeof window !== "undefined" && typeof window.matchMedia !== "function") {
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    }),
  });
}
