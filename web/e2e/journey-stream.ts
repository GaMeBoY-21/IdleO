import { expect, type APIRequestContext, type Page } from "@playwright/test";
import { createServer, request as forward } from "node:http";
import type { AddressInfo, Socket } from "node:net";
import { book, startAt, stopAndSettle } from "./journey-support";
import { API, expectAccessible } from "./support";

/**
 * The event stream dropping and coming back (web/e2e/journey.spec.ts): the session screen says so, keeps polling,
 * and follows the stream again once it's back.
 */
export async function streamDropJourney({ page, request }: { page: Page; request: APIRequestContext }) {
  // The event stream goes through a proxy in this test, which can cut it and refuse it, as a dropped connection
  // would; everything else goes straight to the API.
  const api = new URL(API);
  const sockets = new Set<Socket>();
  let refuse = false;
  const proxy = createServer((incoming, outgoing) => {
    if (refuse) {
      incoming.socket.destroy();
      return;
    }
    const upstream = forward(
      {
        host: api.hostname,
        port: api.port,
        path: incoming.url,
        method: incoming.method,
        headers: { ...incoming.headers, host: api.host },
      },
      (response) => {
        outgoing.writeHead(response.statusCode ?? 502, response.headers);
        response.pipe(outgoing);
      },
    );
    upstream.on("error", () => outgoing.destroy());
    incoming.pipe(upstream);
  });
  proxy.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  await new Promise<void>((resolve) => proxy.listen(0, "127.0.0.1", resolve));
  const proxied = `http://127.0.0.1:${(proxy.address() as AddressInfo).port}`;
  await page.route("**/v1/sessions/*/events", (route) =>
    route.continue({ url: route.request().url().replace(api.origin, proxied) }),
  );

  const session = await book(request, "long-run", "web-stream-drop");
  try {
    await startAt(page, `/session/?id=${session.id}`);
    await expect(page.getByTestId("state-chip").first()).toHaveText("Running", { timeout: 90_000 });
    await expect(page.getByText(/Live, updated/)).toBeVisible();

    refuse = true;
    sockets.forEach((socket) => socket.destroy());
    await expect(page.getByText(/Live updates stopped/)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/Live, updated/)).toHaveCount(0);
    let polls = 0;
    page.on("request", (r) => {
      if (r.url().endsWith(`/v1/sessions/${session.id}`) && r.method() === "GET") {
        polls++;
      }
    });
    await expect.poll(() => polls, { timeout: 20_000 }).toBeGreaterThanOrEqual(2);
    await expectAccessible(page);

    refuse = false;
    await expect(page.getByText(/Live updates stopped/)).toHaveCount(0, { timeout: 45_000 });
    await expect(page.getByText(/Live, updated/)).toBeVisible();
  } finally {
    proxy.close();
    sockets.forEach((socket) => socket.destroy());
    await stopAndSettle(request, session.id);
  }
}
