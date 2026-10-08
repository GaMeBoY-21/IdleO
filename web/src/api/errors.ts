import type { components } from "./schema.gen";

/**
 * A problem type from the catalog (docs/api/problems.md, ADR 0019): what the views branch on, never the title or
 * detail.
 */
export type ProblemType = components["schemas"]["ProblemDetail"]["type"];

/** One invalid field of a `validation-failed` problem: `params.epochs`, and what's wrong with it. */
export interface FieldError {
  field: string;
  detail: string;
}

/** An RFC 9457 problem detail from the API (api/openapi.yaml, ProblemDetail), as an error the views can read. */
export class ApiError extends Error {
  readonly status: number;
  /** The catalog type, or null when something in between answered instead of the API. */
  readonly type: ProblemType | null;
  readonly title: string | null;
  readonly detail: string | null;
  /** For `validation-failed`: each field the request got wrong. */
  readonly errors: FieldError[];

  constructor(
    status: number,
    type: ProblemType | null,
    title: string | null,
    detail: string | null,
    errors: FieldError[] = [],
  ) {
    super(detail ?? title ?? `HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.type = type;
    this.title = title;
    this.detail = detail;
    this.errors = errors;
  }

  /** From the body openapi-fetch parsed, which is a problem detail unless something in between answered. */
  static of(status: number, body: unknown): ApiError {
    const problem = typeof body === "object" && body !== null ? (body as Record<string, unknown>) : {};
    return new ApiError(
      status,
      typeof problem.type === "string" && problem.type.startsWith("/problems/")
        ? (problem.type as ProblemType)
        : null,
      typeof problem.title === "string" ? problem.title : null,
      typeof problem.detail === "string" ? problem.detail : null,
      Array.isArray(problem.errors)
        ? problem.errors.flatMap((entry: unknown) => {
            const { field, detail } = (entry ?? {}) as Record<string, unknown>;
            return typeof field === "string" && typeof detail === "string" ? [{ field, detail }] : [];
          })
        : [],
    );
  }
}
