// @vitest-environment node
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ending, ENDINGS } from "./endings";
import { CREDIT_KINDS, creditKind } from "@/features/credit/kinds";

const root = new URL("../../../", import.meta.url).pathname;

function sources(dir: string, extension: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory()
      ? sources(path, extension)
      : path.endsWith(extension)
        ? [readFileSync(path, "utf8")]
        : [];
  });
}

/**
 * Every code a session can end with, read from the code that sets it: the control plane's error codes and end
 * reasons, and the codes the agent reports when a job fails (passed through as the error code).
 */
function endingCodes(): Set<string> {
  const java = sources(join(root, "control-plane/src/main/java"), ".java").join("\n");
  const rust = sources(join(root, "agent/agent/src"), ".rs").join("\n");
  // The failure codes the job runner reports (ADR 0035), which the agent passes through as the error code.
  const runner = readFileSync(join(root, "agent/jobcore/src/protocol.rs"), "utf8");
  const patterns: [string, RegExp][] = [
    [java, /error_code = '([a-z_]+)'/g],
    [java, /\bend\([\w.()]+, "([a-z_]+)"\)/g],
    [java, /noHostFree\([\w.()]+, "([a-z_]+)"\)/g],
    [java, /endWithoutHost\([\w.()]+, "([a-z_]+)"\)/g],
    [java, /sessions\.cancel\(sessionId, "([a-z_]+)"\)/g],
    [java, /stop\(sessionId, "([a-z_]+)", StopReason/g],
    [java, /SessionEvent\.(?:START_FAILED|OUTPUTS_MISSING|LOST_REPEATEDLY|CANCEL), "([a-z_]+)"/g],
    [java, /code\.isEmpty\(\) \? "([a-z_]+)" : "([a-z_]+)"/g],
    [runner, /pub const [A-Z_]+: &str = "([a-z_]+)";/g],
    [rust, /code: "([a-z_]+)"/g],
    // The supervisor's own limits, as (code, words) pairs: `Some(("max_runtime_exceeded", "ran past max_runtime"))`.
    [rust, /Some\(\("([a-z_]+)", "/g],
    [rust, /let code = if [^{]+\{ "([a-z_]+)" \} else \{ "([a-z_]+)" \}/g],
  ];
  const codes = new Set<string>();
  for (const [text, pattern] of patterns) {
    for (const match of text.matchAll(pattern)) {
      match.slice(1).forEach((code) => code && codes.add(code));
    }
  }
  return codes;
}

describe("the renter's words", () => {
  it("name every kind of credit movement the API reports", () => {
    expect(creditKind("free_credit")).toBe("Free credit");
    expect(creditKind("session_charge")).toBe("Session charge");
    expect(creditKind("session_refund")).toBe("Refund for lost host time");
    expect(creditKind("dispute_refund")).toBe("Refund after your report");
  });

  it("have words for every kind the control plane names in a renter's history", () => {
    const wallet = readFileSync(
      join(root, "control-plane/src/main/java/com/idleo/controlplane/billing/internal/WalletController.java"),
      "utf8",
    );
    const named = [...wallet.matchAll(/case [\w.]+ -> "([a-z_]+)";/g)].map((match) => match[1]);
    expect(named.length).toBeGreaterThan(5);
    expect(CREDIT_KINDS).toEqual(expect.arrayContaining(named));
  });

  it("have a sentence and a next step for every code a session can end with, in the control plane and the agent", () => {
    const codes = endingCodes();
    // The reading of the sources must find what's known to be there, or it proves nothing.
    expect([...codes]).toEqual(
      expect.arrayContaining([
        "job_failed",
        "outputs_missing",
        "host_lost_repeatedly",
        "start_timeout",
        "offers_exhausted",
        "input_unavailable",
        "credit_exhausted",
        "max_spend_reached",
        "no_runner",
        "storage_failed",
      ]),
    );
    // Never a session's ending: the agent stopping its own old attempt when a newer one starts on the same host, which the
    // control plane has already moved past (it ignores a stale attempt's report).
    const internal = new Set(["superseded"]);
    const missing = [...codes].filter((code) => !(code in ENDINGS) && !internal.has(code));
    expect(missing, "codes without words in src/lib/labels.ts ENDINGS").toEqual([]);
    for (const words of Object.values(ENDINGS)) {
      expect(words.sentence).toMatch(/^[A-Z].*\.$/);
      expect(words.next).toMatch(/^[A-Z].*\.$/);
    }
  });

  it("read an exit code, and never hide a code they don't know", () => {
    expect(ending("exit_code_137")?.sentence).toBe("The job exited with code 137.");
    expect(ending("something_new")?.sentence).toBe("It ended: something new.");
    expect(ending(null)).toBeNull();
  });
});
