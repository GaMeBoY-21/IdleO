/** An RFC 9457 problem detail from the API (api/openapi.yaml, ProblemDetail), as an error the views can read. */
export class ApiError extends Error {
  readonly status: number;
  readonly title: string | null;
  readonly detail: string | null;

  constructor(status: number, title: string | null, detail: string | null) {
    super(detail ?? title ?? `HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.title = title;
    this.detail = detail;
  }

  /** From the body openapi-fetch parsed, which is a problem detail unless something in between answered. */
  static of(status: number, body: unknown): ApiError {
    const problem = typeof body === "object" && body !== null ? (body as Record<string, unknown>) : {};
    return new ApiError(
      status,
      typeof problem.title === "string" ? problem.title : null,
      typeof problem.detail === "string" ? problem.detail : null,
    );
  }
}
