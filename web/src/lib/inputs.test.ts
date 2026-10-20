import { describe, expect, it } from "vitest";
import { inputLine, inputsTotal, sessionInputs, type InputFact } from "./inputs";
import { data } from "@/test/fixtures";

const upload = (name: string, bytes: number | null, files: number | null): InputFact => ({
  name,
  label: `Your ${name}`,
  link: false,
  filename: name,
  bytes,
  files,
});
const link = (name: string): InputFact => ({
  name,
  label: `Your ${name}`,
  link: true,
  filename: null,
  bytes: null,
  files: null,
});
const GB = 1024 ** 3;

describe("a session's inputs", () => {
  it("are all read, in the session's order, never only the first", () => {
    expect(
      sessionInputs(data.foundWithTwoInputs).map((input) => [input.name, input.uploadId !== null]),
    ).toEqual([
      ["model", true],
      ["data", true],
    ]);
    expect(sessionInputs(data.noHostFree)).toEqual([]);
  });

  it("each have a line: its size and files, or a link, or Uploaded until it's read", () => {
    expect(inputLine(upload("model", 4.2 * GB, 9))).toBe("4.2 GB · 9 files");
    expect(inputLine(upload("data", 68 * 1024, 1))).toBe("68 KB");
    expect(inputLine(link("data"))).toBe("From your link");
    expect(inputLine(upload("data", null, null))).toBe("Uploaded");
  });

  it("come to one total over every upload, with its links named, and nothing until all are read", () => {
    expect(inputsTotal([upload("model", 4.2 * GB, 9), upload("data", 68 * 1024, 1)])).toBe(
      "4.2 GB · 10 files",
    );
    expect(inputsTotal([upload("model", 4.2 * GB, 9), link("data")])).toBe("4.2 GB · 9 files · and a link");
    expect(inputsTotal([link("model"), link("data")])).toBe("From your links");
    expect(inputsTotal([link("data")])).toBe("From your link");
    expect(inputsTotal([upload("model", null, null), upload("data", 68 * 1024, 1)])).toBeNull();
    expect(inputsTotal([])).toBeNull();
  });
});
