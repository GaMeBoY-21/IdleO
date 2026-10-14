// @vitest-environment node
import { describe, expect, it } from "vitest";
import { render } from "@/prerender";
import { SCREENS } from "@/screens";

/**
 * Every screen's pre-rendered frame is whole (ADR 0018): React writes `<!--$!-->` where it couldn't finish a Suspense
 * boundary on the server, and the browser then throws that part away and draws it again (error 419 in the console,
 * and the first paint replaced). A screen in its own chunk must be loaded before it's pre-rendered.
 */
describe("the pre-rendered frames", () => {
  for (const path of Object.keys(SCREENS)) {
    it(`${path} has no boundary the browser must rebuild`, async () => {
      const html = await render(path);
      expect(html.length).toBeGreaterThan(0);
      expect(html).not.toContain("<!--$!-->");
    });
  }
});
