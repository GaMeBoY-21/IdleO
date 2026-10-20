import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { sessionTitle } from "@/features/flow/headline";
import { progressStops } from "@/features/flow/progress";
import { data, session } from "@/test/fixtures";
import { GpuTile } from "./Figures";
import { CheckpointTile } from "./Outputs";

const catalog = data.templates;
const now = data.NOW.getTime();
const titles = (stops: ReturnType<typeof progressStops>) =>
  stops.map((stop) => `${stop.title as string} · ${stop.detail as string} · ${stop.state}`);

describe("a session's title", () => {
  it("says what it's doing on which GPU while it runs, and what it's doing before that", () => {
    expect(sessionTitle(data.running, catalog)).toBe("Fine-tuning on an RTX 4080 Laptop");
    expect(sessionTitle(data.finding, catalog)).toBe("Finding your GPU");
    expect(sessionTitle(data.found, catalog)).toBe("GPU found");
  });
});

describe("a session's route", () => {
  it("is at finding its GPU, with its data done", () => {
    const inputs = [
      { name: "data", label: "Your data", link: false, filename: "d.jsonl", bytes: 48_213_504, files: 1 },
    ];
    expect(titles(progressStops(data.finding, { now, inputs }))).toEqual([
      "Uploaded · 46 MB · done",
      "Finding your GPU · Usually under a minute · current",
      "Running · It saves checkpoints as it runs · next",
      "Outputs · When it's done · end",
    ]);
  });

  it("says what every upload comes to together: a fine-tune's 4.2 GB model and its 68 KB examples", () => {
    const session = data.foundWithTwoInputs;
    expect(Object.keys(session.inputs)).toEqual(["model", "data"]);
    const inputs = [
      {
        name: "model",
        label: "Your model",
        link: false,
        filename: "my-llm",
        bytes: data.MODEL_FOLDER_BYTES,
        files: data.MODEL_FOLDER_FILES,
      },
      {
        name: "data",
        label: "Your training data",
        link: false,
        filename: "train.jsonl",
        bytes: data.EXAMPLES_BYTES,
        files: 1,
      },
    ];
    expect(progressStops(session, { now, inputs })[0]!.detail).toBe("4.2 GB · 10 files");
    // Until the uploads are read, no size is guessed from the first one.
    expect(
      progressStops(session, { now, inputs: inputs.map((i) => ({ ...i, bytes: null, files: null })) })[0]!
        .detail,
    ).toBe("Ready");
    expect(progressStops(session, { now })[0]!.detail).toBe("Ready");
  });

  it("says how long finding its host took, its checkpoints, and when its outputs are due", () => {
    expect(titles(progressStops(data.running, { now, typicalRuntimeSeconds: 2_700 }))).toEqual([
      "Uploaded · Ready · done",
      "GPU found · in 2 s · done",
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
    expect(titles(progressStops(data.noHostFree, { now }))[0]).toBe(
      "Finding your GPU · No GPU took it · next",
    );
  });
});

describe("the live session", () => {
  it("shows the GPU's use and temperature, and says when they aren't known", () => {
    const { rerender } = render(<GpuTile gpu={data.running.gpu!} />);
    expect(screen.getByText("RTX 4080 Laptop · 12 GB")).toBeInTheDocument();
    expect(screen.getByText("97% busy · 71°C")).toBeInTheDocument();
    rerender(<GpuTile gpu={{ ...data.running.gpu!, utilPct: null, tempC: null }} />);
    expect(screen.getByText("Reading its use…")).toBeInTheDocument();
  });

  it("names its newest checkpoint, or says there's none yet", () => {
    const { rerender } = render(<CheckpointTile session={data.running} />);
    expect(screen.getByText(/^Last checkpoint saved/)).toBeInTheDocument();
    rerender(<CheckpointTile session={session({ checkpoints: [] })} />);
    expect(screen.getByText("No checkpoint saved yet")).toBeInTheDocument();
  });

  it("says a checkpoint newer than the clock's last tick was saved just now", () => {
    const fresh = { attempt: 1, seq: 5, completedAt: new Date(Date.now() + 20_000).toISOString() };
    const { container } = render(
      <CheckpointTile session={session({ checkpoints: [...data.running.checkpoints, fresh] })} />,
    );
    expect(container).toHaveTextContent("Last checkpoint saved just now");
  });
});
