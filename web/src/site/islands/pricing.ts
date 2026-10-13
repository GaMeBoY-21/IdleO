import { formatPaise } from "@/lib/money";
import { formatRange, priceExample, shown, type ClassRow, type PublicAvailability } from "../pricing";
import { timeOfDay, watchAvailability, type Outcome } from "./availability";

/** The pricing page's live figures (ADR 0029). Every row and line is already on the page at its final size. */
const root = document.querySelector<HTMLElement>("[data-live-prices]");
const api = root?.dataset.api;

function part(parent: Element, role: string): HTMLElement {
  return parent.querySelector<HTMLElement>(`[data-role="${role}"]`)!;
}

function show(element: HTMLElement, text: string | null): void {
  element.hidden = text === null;
  if (text !== null) {
    element.textContent = text;
  }
}

function fillRow(row: HTMLElement, data: ClassRow): void {
  const { range, kind } = shown(data);
  part(row, "skeleton").hidden = true;
  show(part(row, "online"), data.online ? "Online now" : "None online now");
  show(part(row, "price"), formatRange(range));
  part(row, "kind").textContent =
    kind === "live"
      ? "Right now, across the hosts online"
      : "Typical: too few hosts online to show today's range";
  row.dataset.state = "loaded";
}

function render(outcome: Outcome): void {
  if (!root) {
    return;
  }
  const status = part(root, "status");
  const retry = part(root, "retry");
  if (outcome.kind === "failed") {
    show(status, "We couldn't load today's prices. Check your connection and try again.");
    retry.hidden = false;
    return;
  }
  const data: PublicAvailability = outcome.data;
  for (const row of root.querySelectorAll<HTMLElement>("[data-class-row]")) {
    const found = data.classes.find((candidate) => candidate.gpuMemoryGb === Number(row.dataset.classRow));
    if (found) {
      fillRow(row, found);
    }
  }
  for (const example of root.querySelectorAll<HTMLElement>("[data-example]")) {
    const priced = priceExample(
      {
        label: example.dataset.label ?? "",
        minutes: Number(example.dataset.minutes),
        gpuMemoryGb: Number(example.dataset.gb),
      },
      data.classes,
    );
    if (priced) {
      part(example, "skeleton").hidden = true;
      show(
        part(example, "cost"),
        priced.lowPaise === priced.highPaise
          ? `${formatPaise(priced.lowPaise)}, typical`
          : `${formatPaise(priced.lowPaise)}–${formatPaise(priced.highPaise).slice(1)}, typical`,
      );
    }
  }
  if (outcome.kind === "stale") {
    show(
      status,
      `Couldn't refresh. Showing prices from ${timeOfDay(new Date(data.asOf))}. Trying again soon.`,
    );
    retry.hidden = false;
  } else {
    show(status, `Prices as of ${timeOfDay(new Date(data.asOf))}, refreshed every minute.`);
    retry.hidden = true;
  }
  // Its place is reserved on the page, so it stays and only its words come and go.
  part(root, "credit").textContent =
    data.startingCreditPaise === null
      ? ""
      : `New accounts start with ${formatPaise(data.startingCreditPaise)} of credit.`;
}

if (root && api) {
  const watcher = watchAvailability(api, render);
  part(root, "retry").addEventListener("click", () => {
    part(root, "retry").hidden = true;
    show(part(root, "status"), "Looking up today's prices…");
    watcher.retry();
  });
}
