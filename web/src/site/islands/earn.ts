import { formatPaise } from "@/lib/money";
import { COMMISSION_PERCENT } from "@/lib/hosting";
import { earnings } from "../calculator";
import { formatRange, shown, type PublicAvailability } from "../pricing";
import { watchAvailability, type Outcome } from "./availability";

/**
 * The earnings calculator's page (ADR 0029): works the arithmetic of src/site/calculator.ts on what the person
 * chooses. The price starts empty and is filled once from the live (or typical) range of the chosen GPU memory, unless
 * the person has already typed their own. Nothing is counted on the server and nothing is kept.
 */
const root = document.querySelector<HTMLElement>("[data-calculator]");

if (root) {
  const gpu = document.getElementById("calc-class") as HTMLSelectElement;
  const price = document.getElementById("calc-price") as HTMLInputElement;
  const hours = document.getElementById("calc-hours") as HTMLInputElement;
  const occupancy = document.getElementById("calc-occupancy") as HTMLSelectElement;
  const hint = root.querySelector<HTMLElement>('[data-role="hint"]')!;
  const perDay = root.querySelector<HTMLElement>('[data-role="per-day"]')!;
  const perMonth = root.querySelector<HTMLElement>('[data-role="per-month"]')!;
  const detail = root.querySelector<HTMLElement>('[data-role="detail"]')!;
  let data: PublicAvailability | null = null;
  let typed = false;

  const suggestion = () => {
    const row = data?.classes.find((candidate) => candidate.gpuMemoryGb === Number(gpu.value));
    return row ? { row, ...shown(row) } : null;
  };

  const update = () => {
    const result = earnings({
      priceRupeesPerHour: price.value,
      hoursFreePerDay: Number(hours.value),
      occupancyPercent: Number(occupancy.value),
    });
    if (!result) {
      perDay.textContent = "–";
      perMonth.textContent = "–";
      detail.textContent = "Enter a price, 1 to 24 hours, and a share to see an estimate.";
      return;
    }
    perDay.textContent = formatPaise(result.hostPerDay);
    perMonth.textContent = formatPaise(result.hostPerMonth);
    detail.textContent = `Renters would pay ${formatPaise(result.paidByRentersPerDay)} a day; we keep ${COMMISSION_PERCENT}% of it, ${formatPaise(result.feePerDay)}. Priced at ${formatPaise(result.pricePaisePerMin * 60)} an hour.`;
  };

  const suggest = () => {
    const found = suggestion();
    if (!found) {
      return;
    }
    hint.textContent = `${found.kind === "live" ? "Hosts with a GPU this size ask" : "Typically asked for a GPU this size"}: ${formatRange(found.range)}`;
    if (!typed) {
      // The middle of the range, as whole paise a minute.
      price.value = ((Math.round((found.range.min + found.range.max) / 2 / 60) * 60) / 100).toFixed(2);
      update();
    }
  };

  price.addEventListener("input", () => {
    typed = true;
    update();
  });
  gpu.addEventListener("change", suggest);
  hours.addEventListener("input", update);
  occupancy.addEventListener("change", update);

  const api = root.dataset.api;
  if (api) {
    watchAvailability(api, (outcome: Outcome) => {
      if (outcome.kind !== "failed") {
        data = outcome.data;
        suggest();
      }
    });
  }
  update();
}
