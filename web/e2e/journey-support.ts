import { expect, type APIRequestContext, type Page } from "@playwright/test";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { API, signIn } from "./support";

/**
 * What the journeys share (web/e2e/journey.spec.ts): the renter scripts/e2e/agent-online.sh creates and funds,
 * tokens as its operator tooling gets them, and the API calls a journey makes around what it does in the browser.
 */
export const user = process.env.E2E_WEB_USER ?? "";
const signInServer = process.env.SIGN_IN_URL ?? "http://localhost:8099/idleo-local";
export const MIB = 1024 * 1024;

export const laptop = {
  viewport: { width: 1280, height: 800 },
  isMobile: false,
  hasTouch: false,
  deviceScaleFactor: 1,
};

/** The same invented user, as the script's operator tooling signs in. */
export async function token(request: APIRequestContext, who = user): Promise<string> {
  const response = await request.post(`${signInServer}/token`, {
    form: { grant_type: "client_credentials", client_id: who, client_secret: "unused", scope: "openid" },
  });
  return ((await response.json()) as { access_token: string }).access_token;
}

export const authorised = async (request: APIRequestContext) => ({
  Authorization: `Bearer ${await token(request)}`,
});

export interface Session {
  id: string;
  state: string;
  settled: boolean;
  hostId: string | null;
  chargedPaise: number;
  reservedPaise: number;
  maxSpendPaise: number;
  maxPricePaisePerMin: number;
  pricePaisePerMin: number | null;
  vramNeededMb: number;
  presets: Record<string, string>;
  inputs: Record<string, { uploadId: string | null; url: string | null }>;
  createdAt: string;
  events: { state: string; reason: string; at: string }[];
}

export async function getSession(request: APIRequestContext, id: string): Promise<Session> {
  const response = await request.get(`${API}/v1/sessions/${id}`, { headers: await authorised(request) });
  return (await response.json()) as Session;
}

/** Books through the API, as the CLI would, for journeys about what comes after booking; the server sets the ceiling. */
export async function book(request: APIRequestContext, templateId: string, key: string): Promise<Session> {
  const response = await request.post(`${API}/v1/sessions`, {
    headers: { ...(await authorised(request)), "Idempotency-Key": `${user}-${key}` },
    data: { templateId, maxSpendPaise: 10_000 },
  });
  expect(response.status()).toBe(201);
  return (await response.json()) as Session;
}

/** The highest price among the GPUs free now with at least this much memory, as availability says. */
export async function highestFitting(request: APIRequestContext, vramMb: number): Promise<number | null> {
  const response = await request.get(`${API}/v1/availability`, { headers: await authorised(request) });
  const { gpus } = (await response.json()) as {
    gpus: { vramMb: number; hostsIdle: number; pricePaisePerMin: { max: number } }[];
  };
  const fitting = gpus.filter((gpu) => gpu.hostsIdle > 0 && gpu.vramMb >= vramMb);
  return fitting.length === 0 ? null : Math.max(...fitting.map((gpu) => gpu.pricePaisePerMin.max));
}

/** Seconds from a session's request to the first time it reached `state`, as the control plane recorded it. */
export function secondsTo(session: Session, state: string): number | null {
  const at = session.events.find((event) => event.state === state)?.at;
  return at ? Number(((Date.parse(at) - Date.parse(session.createdAt)) / 1000).toFixed(1)) : null;
}

/** Stops a session through the API, so the next journey finds the host free; then waits until it's settled. */
export async function stopAndSettle(request: APIRequestContext, id: string): Promise<void> {
  await request.post(`${API}/v1/sessions/${id}/stop`, {
    headers: await authorised(request),
    data: { checkpointFirst: false },
  });
  await expect.poll(async () => (await getSession(request, id)).settled, { timeout: 120_000 }).toBe(true);
}

export async function startAt(page: Page, path: string, who = user) {
  await signIn(page, who);
  if (new URL(page.url()).pathname === "/welcome/") {
    await page.getByRole("button", { name: "Go to your home" }).click();
    await page.waitForURL((url) => url.pathname === "/");
  }
  await page.goto(path);
}

/**
 * A generated model folder on disk, as "Choose folder" reads it: config.json, tokenizer.json and 12 MiB of weights,
 * the shape of a real model's folder; never a real model (CLAUDE.md, real data).
 */
export function modelFolder(): { path: string; files: Record<string, number> } {
  const path = join(mkdtempSync(join(tmpdir(), "idleo-journey-")), "finetune-model");
  mkdirSync(path);
  const files: Record<string, Buffer> = {
    "config.json": Buffer.from('{"model_type": "llama", "hidden_size": 64, "num_hidden_layers": 2}\n'),
    "tokenizer.json": Buffer.from('{"version": "1.0", "model": {"type": "BPE", "vocab": {}}}\n'),
    "model-00001-of-00001.safetensors": randomBytes(12 * MIB),
  };
  Object.entries(files).forEach(([name, bytes]) => writeFileSync(join(path, name), bytes));
  return {
    path,
    files: Object.fromEntries(Object.entries(files).map(([name, bytes]) => [name, bytes.length])),
  };
}

/** Forty generated training examples, one JSON object a line, in a file of their own. */
export function examples(): string {
  const path = join(mkdtempSync(join(tmpdir(), "idleo-journey-")), "examples.jsonl");
  const lines = Array.from(
    { length: 40 },
    (_, i) =>
      `{"prompt": "Question ${i + 1}: what is ${i + 1} plus ${i + 1}?", "completion": "${2 * (i + 1)}"}`,
  );
  writeFileSync(path, `${lines.join("\n")}\n`);
  return path;
}

/** A generated file of `mib` MiB on disk, so choosing it again gives the browser the same file. */
export function fileOf(name: string, mib: number): string {
  const path = join(mkdtempSync(join(tmpdir(), "idleo-journey-")), name);
  writeFileSync(path, randomBytes(mib * MIB));
  return path;
}
