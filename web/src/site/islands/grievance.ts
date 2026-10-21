/**
 * The grievance officer's block on Contact and Privacy (ADR 0037): asks GET /v1/public/contact once the page has
 * painted and fills in the name, email and postal address the control plane is configured with. Without an answer the
 * block says how to reach us instead. Plain script: nothing of the app is loaded here.
 */
export interface PublicContact {
  topics: { code: string; label: string }[];
  grievanceOfficer: { name: string; email: string; postalAddress: string };
  responseDays: number;
}

/** The contact details, once per page. */
let asked: Promise<PublicContact | null> | null = null;

export function contact(api: string): Promise<PublicContact | null> {
  asked ??= fetch(`${api}/v1/public/contact`, {
    headers: { Accept: "application/json" },
    signal: AbortSignal.timeout(10_000),
  })
    .then((response) => (response.ok ? (response.json() as Promise<PublicContact>) : null))
    .catch(() => null);
  return asked;
}

export function showGrievance(root: HTMLElement): void {
  const api = root.dataset.api;
  if (!api) {
    return;
  }
  const set = (role: string, text: string) => {
    root.querySelector<HTMLElement>(`[data-role="${role}"]`)!.textContent = text;
  };
  requestAnimationFrame(() =>
    setTimeout(
      () =>
        void contact(api).then((details) => {
          if (!details) {
            set("name", "We couldn't load the officer's details.");
            set("email", "Write to us with the form on the Contact page.");
            return;
          }
          set("name", details.grievanceOfficer.name);
          set("email", details.grievanceOfficer.email);
          set("address", details.grievanceOfficer.postalAddress);
          set("days", `We answer a grievance within ${details.responseDays} days.`);
        }),
      0,
    ),
  );
}

const block = document.querySelector<HTMLElement>("[data-grievance]");
if (block) {
  showGrievance(block);
}
