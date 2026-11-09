import { describe, expect, it } from "vitest";
import { uncovered, writesInTheMatrix } from "../../scripts/admin-ui-coverage";

describe("the admin UI coverage check", () => {
  const writes = writesInTheMatrix(
    [
      "endpoint,permission,owner",
      "GET /admin/v1/users,users.read,allow",
      "POST /admin/v1/queue/bulk/preview,queues.bulk,allow",
      "POST /admin/v1/queue/{id}/claim,queues.claim,allow",
      "DELETE /admin/v1/views/{id},self,allow",
    ].join("\n"),
  );

  it("reads every write, and no read, from the matrix", () => {
    expect(writes).toEqual([
      "POST /admin/v1/queue/bulk/preview",
      "POST /admin/v1/queue/{id}/claim",
      "DELETE /admin/v1/views/{id}",
    ]);
  });

  it("lists what no test pressed, and a template counts any id", () => {
    expect(
      uncovered(writes, [
        { method: "POST", path: "/admin/v1/queue/0199/claim" },
        { method: "GET", path: "/admin/v1/views/1" },
      ]),
    ).toEqual(["POST /admin/v1/queue/bulk/preview", "DELETE /admin/v1/views/{id}"]);
  });

  it("doesn't count a literal path for a template it happens to fit", () => {
    expect(
      uncovered(
        ["POST /admin/v1/queue/{id}", "POST /admin/v1/queue/bulk"],
        [{ method: "POST", path: "/admin/v1/queue/bulk" }],
      ),
    ).toEqual(["POST /admin/v1/queue/{id}"]);
  });

  it("passes when everything was pressed", () => {
    expect(
      uncovered(
        ["PUT /admin/v1/table-settings/{table}"],
        [{ method: "PUT", path: "/admin/v1/table-settings/users" }],
      ),
    ).toEqual([]);
  });
});
