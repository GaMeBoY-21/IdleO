import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { aboutRuntime, bookHref, offeredServices, serviceOf } from "@/lib/catalog";
import { data, session } from "@/test/fixtures";
import { BookAgainTile, latestSetup } from "./BookAgain";
import { RunningTile } from "./RunningTile";
import { MainServiceTile, QuestionTile, WideServiceTile } from "./ServiceTiles";

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
    expect(serviceOf(catalog, "run-python")?.id).toBe("run-code");
    expect(serviceOf(catalog, "gone")).toBeUndefined();
    expect(bookHref("run-code", "run-python")).toBe("/book/?service=run-code&template=run-python");
  });

  it("says a typical runtime roughly", () => {
    expect(aboutRuntime(20)).toBe("about 20 s");
    expect(aboutRuntime(2_700)).toBe("about 45 min");
    expect(aboutRuntime(9_000)).toBe("about 3 h");
    expect(aboutRuntime(null)).toBeNull();
  });
});

describe("Home", () => {
  it("shows a running session as the dark tile: what it's doing, its GPU, the time and its cost so far", () => {
    render(<RunningTile session={session({ chargedPaise: 300, accruedPaise: 42 })} catalog={catalog} />);
    const tile = screen.getByRole("link");
    expect(tile).toHaveAttribute("href", `/session/?id=${data.running.id}`);
    expect(tile).toHaveTextContent("Running now");
    expect(tile).toHaveTextContent("Fine-tuning on RTX 4080 Laptop");
    expect(tile).toHaveTextContent(/Running for \d+:\d\d/);
    expect(tile).toHaveTextContent("₹3.42 so far");
  });

  it("says a session that's still finding its GPU costs nothing yet", () => {
    render(<RunningTile session={data.finding} catalog={catalog} />);
    expect(screen.getByRole("link")).toHaveTextContent("Finding your GPU");
    expect(screen.getByRole("link")).toHaveTextContent("Nothing is charged until it runs");
  });

  it("shows the first service as the tall lime tile and any other as a wide one, each opening Book at it", () => {
    const [first, second] = offeredServices(catalog);
    render(
      <>
        <MainServiceTile service={first!} />
        <WideServiceTile service={second!} />
      </>,
    );
    const tiles = screen.getAllByRole("link");
    expect(tiles.map((tile) => tile.getAttribute("href"))).toEqual([
      "/book/?service=fine-tune",
      "/book/?service=run-code",
    ]);
    expect(tiles[0]).toHaveTextContent("Fine-tune a modelA model on your data");
  });

  it("puts the services inside the question when nothing is running, the first as the main button", () => {
    render(<QuestionTile services={offeredServices(catalog)} />);
    expect(screen.getByRole("heading", { name: "What do you want to run?" })).toBeInTheDocument();
    expect(screen.getAllByRole("link").map((link) => link.getAttribute("href"))).toEqual([
      "/book/?service=fine-tune",
      "/book/?service=run-code",
    ]);
  });

  it("offers the last setup that ended, with how long it ran", () => {
    const setup = latestSetup(data.asha.sessions, catalog);
    expect(setup).toBeDefined();
    render(<BookAgainTile session={setup!} name={templateName(setup!.templateId)} />);
    const tile = screen.getByRole("link");
    expect(tile).toHaveTextContent("Book again");
    expect(tile).toHaveTextContent("Fine-tune your model (LoRA) · 6 min 10 s");
    expect(tile.getAttribute("href")).toMatch(/^\/book\/\?template=qlora-finetune&from=/);
  });

  it("never offers to book again a service that isn't offered, such as the test jobs", () => {
    const echoes = data.asha.sessions.filter((session) => session.templateId === "echo-input");
    expect(echoes.length).toBeGreaterThan(0);
    expect(latestSetup(echoes, catalog)).toBeUndefined();
  });

  it("offers nothing to book again to a new renter", () => {
    expect(latestSetup([], catalog)).toBeUndefined();
  });
});
