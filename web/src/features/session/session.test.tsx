import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { sessionTitle } from "@/features/flow/headline";
import { progressStops } from "@/features/flow/progress";
import { data, session } from "@/test/fixtures";
import { GpuCard } from "./Figures";
import { LastCheckpoint } from "./Outputs";

const catalog = data.templates;
const now = data.NOW.getTime();
const titles = (stops: ReturnType<typeof progressStops>) =>
  stops.map((stop) => `${String(stop.title)} · ${String(stop.detail)} · ${stop.state}`);

describe("a session's title", () => {
  it("says what it's doing on which GPU while it runs, and what it's doing before that", () => {
    expect(sessionTitle(data.running, catalog)).toBe("Fine-tuning on an RTX 4080 Laptop");
    expect(sessionTitle(data.finding, catalog)).toBe("Finding your GPU");
    expect(sessionTitle(data.found, catalog)).toBe("GPU found");
  });
});

describe("a session's route", () => {
  it("is at finding its GPU, with its data done", () => {
    expect(titles(progressStops(data.finding, { now, uploadBytes: 48_213_504 }))).toEqual([
      "Data uploaded · 46 MB · done",
      "Finding your GPU · Usually under a minute · current",
      "Running · It saves checkpoints as it runs · next",
      "Outputs · When it's done · end",
    ]);
  });

  it("says how long finding its host took, its checkpoints, and when its outputs are due", () => {
    expect(titles(progressStops(data.running, { now, typicalRuntimeSeconds: 2_700 }))).toEqual([
      "Data uploaded · Ready · done",
      "Host found · in 2 s · done",
      "Running · 4 checkpoints so far · current",
      "Outputs · In about 23 min · end",
    ]);
    const late = progressStops(data.running, { now, typicalRuntimeSeconds: 600 });
    expect(late.at(-1)?.detail).toBe("Any minute now");
  });

  it("is done to its outputs once completed, and never reached a host when none took it", () => {
    expect(titles(progressStops(data.completed, { now })).slice(2)).toEqual([
      "Running · Ran 22 s · done",
      "Outputs · Ready to download · done",
    ]);
    expect(titles(progressStops(data.noHostFree, { now }))[0]).toBe("Finding your GPU · No GPU took it · next");
  });
});

describe("the live session", () => {
  it("shows the GPU's use and temperature, and says when they aren't known", () => {
    const { rerender } = render(<GpuCard gpu={data.running.gpu!} laptop={false} />);
    expect(screen.getByText("RTX 4080 Laptop · 12 GB")).toBeInTheDocument();
    expect(screen.getByText("97% busy · 71°C")).toBeInTheDocument();
    rerender(<GpuCard gpu={{ ...data.running.gpu!, utilPct: null, tempC: null }} laptop={false} />);
    expect(screen.getByText("Reading its use…")).toBeInTheDocument();
  });

  it("names its newest checkpoint, or says there's none yet", () => {
    const { rerender } = render(<LastCheckpoint session={data.running} />);
    expect(screen.getByText(/^Checkpoint 4 saved/)).toBeInTheDocument();
    rerender(<LastCheckpoint session={session({ checkpoints: [] })} />);
    expect(screen.getByText("No checkpoint saved yet")).toBeInTheDocument();
  });
});
