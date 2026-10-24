import type { Request } from "@playwright/test";
import * as hosting from "./fixtures-hosting";
import * as payouts from "./fixtures-payouts";
import type { Answer } from "./support";

const idOf = (request: Request) => new URL(request.url()).pathname.split("/")[3]!;
const machine = (request: Request) => hosting.hostById(idOf(request));

/**
 * How a fake API answers the hosting screens (ADR 0023, ADR 0040): the machines, their telemetry and sessions,
 * earnings and payouts.
 */
export function hostingApi(): Record<string, Answer> {
  return {
    "/v1/hosts": { json: hosting.hosts },
    "/v1/host-links/[^/]+": { json: hosting.hostLink },
    "/v1/price-band": { json: hosting.priceBandDefault },
    // No release until a screen says otherwise: the Download button is "coming soon".
    "/v1/agent-releases/latest": { status: 404, problem: "no-agent-release" },
    "/v1/earnings": { json: hosting.earnings },
    // Payouts (ADR 0040): the summary, the history and where they go.
    "/v1/payouts/summary": { json: payouts.summary },
    "/v1/payouts": { json: { payouts: payouts.payouts, nextCursor: null } },
    "/v1/payout-details": { json: payouts.detailsActive },
    "/v1/hosts/[^/]+/telemetry": (_call, request) => ({ json: hosting.telemetryOf(machine(request)!) }),
    "/v1/hosts/[^/]+/price-band": { json: hosting.priceBand },
    "/v1/hosts/[^/]+/sessions": (_call, request) => ({ json: hosting.hostSessionsPage(idOf(request)) }),
    "/v1/hosts/[^/]+": (_call, request) => {
      const found = machine(request);
      return found ? { json: found } : { status: 404 };
    },
  };
}
