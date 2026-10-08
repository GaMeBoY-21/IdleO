import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { aboutRuntime, bookHref, offeredServices, serviceOf } from "@/lib/catalog";
import { data, session } from "@/test/fixtures";
import { BookAgain } from "./BookAgain";
import { RunningBar } from "./RunningBar";
import { ServiceTiles } from "./ServiceTiles";

const catalog = data.templates;
const templateName = (id: string) => catalog.templates.find((template) => template.id === id)?.name ?? id;

describe("the catalog", () => {
  it("offers the services that aren't hidden and have a template", () => {
    const empty = {
      id: "empty",
      name: "Empty",
      tagline: "",
      runningLabel: "",
      icon: "cube",
      hidden: false,
    } as const;
    expect(offeredServices({ ...catalog, services: [...catalog.services, empty] }).map((s) => s.id)).toEqual([
      "fine-tune",
      "run-code",
    ]);
  });

  it("finds a template's service, and links Book to it", () => {
    expect(serviceOf(catalog, "echo-input")?.id).toBe("run-code");
    expect(serviceOf(catalog, "gone")).toBeUndefined();
    expect(bookHref("run-code", "echo-input")).toBe("/book/?service=run-code&template=echo-input");
  });

  it("says a typical runtime roughly", () => {
    expect(aboutRuntime(20)).toBe("about 20 s");
    expect(aboutRuntime(2_700)).toBe("about 45 min");
    expect(aboutRuntime(9_000)).toBe("about 3 h");
    expect(aboutRuntime(null)).toBeNull();
  });
});

describe("Home", () => {
  it("shows a running session as a bar: what it's doing, for how long, its GPU and its cost so far", () => {
    render(<RunningBar session={session({ chargedPaise: 300, accruedPaise: 42 })} catalog={catalog} />);
    const bar = screen.getByRole("link");
    expect(bar).toHaveAttribute("href", `/session/?id=${data.running.id}`);
    expect(bar).toHaveTextContent(/^Fine-tuning · \d+:\d\d/);
    expect(bar).toHaveTextContent("RTX 4080 Laptop · ₹3.42 so far");
  });

  it("says a session that's still finding its GPU costs nothing yet", () => {
    render(<RunningBar session={data.finding} catalog={catalog} />);
    expect(screen.getByRole("link")).toHaveTextContent("Finding your GPU");
    expect(screen.getByRole("link")).toHaveTextContent("Nothing is charged until it runs");
  });

  it("shows each service as a tile that opens Book at it", () => {
    render(<ServiceTiles services={offeredServices(catalog)} laptop={false} />);
    const tiles = within(screen.getByRole("list", { name: "Services" })).getAllByRole("link");
    expect(tiles.map((tile) => tile.getAttribute("href"))).toEqual([
      "/book/?service=fine-tune",
      "/book/?service=run-code",
    ]);
    expect(tiles[0]).toHaveTextContent("Fine-tune a modela model on your data");
  });

  it("offers the last three distinct setups that ended, each with what it cost", () => {
    render(<BookAgain sessions={data.asha.sessions} templateName={templateName} />);
    const rows = within(screen.getByRole("region", { name: "Book again" })).getAllByRole("link");
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("Echo an input");
    expect(rows[0]).toHaveTextContent("₹0.12");
    expect(rows[0]?.getAttribute("href")).toMatch(/^\/book\/\?template=echo-input&from=/);
    expect(screen.queryByText("₹3.08")).not.toBeInTheDocument();
  });

  it("offers nothing to book again to a new renter", () => {
    const { container } = render(<BookAgain sessions={[]} templateName={templateName} />);
    expect(container).toBeEmptyDOMElement();
  });
});
