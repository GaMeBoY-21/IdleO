import { ApiError, type ProblemType } from "@/api/errors";

/** A problem type's slug: `insufficient-credit` for `/problems/insufficient-credit`. */
export type ProblemSlug = ProblemType extends `/problems/${infer Slug}` ? Slug : never;

let words: typeof import("./problem-words").PROBLEM_MESSAGES | null = null;
let loading: Promise<void> | null = null;

/**
 * Fetches the words for problem types, once. Their file isn't in the main script, since no first screen needs it: the app asks for it
 * just after the first screen, and the API client waits for it before it hands on a refused request (src/api/client.ts),
 * so problemMessage has its words by the time it's asked. If it can't be fetched, the network is what's wrong, and a later try
 * may work.
 */
export function loadProblemWords(): Promise<void> {
  loading ??= import("./problem-words")
    .then((module) => {
      words = module.PROBLEM_MESSAGES;
    })
    .catch(() => {
      loading = null;
    });
  return loading;
}

const UNREACHABLE = "We couldn't reach IdleO. Check your connection and try again.";

/** The message for an error from an action: its problem type's words, or the network's. */
export function problemMessage(error: unknown): string {
  if (error instanceof ApiError && error.type !== null) {
    return words?.[error.type.slice("/problems/".length) as ProblemSlug] ?? UNREACHABLE;
  }
  if (error instanceof ApiError && error.status >= 500) {
    return words?.["internal-error"] ?? UNREACHABLE;
  }
  return UNREACHABLE;
}
