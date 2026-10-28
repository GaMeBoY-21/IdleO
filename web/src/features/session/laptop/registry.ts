import { useEffect, useState } from "react";

/**
 * The laptop's session tiles (steps, request, the live tile and the GPU's stats) are drawn only from 768 px, so they are
 * a chunk of their own: a phone's first load, where the session screen's speed is measured, doesn't carry them. A
 * laptop loads them as the screen opens, and the screen shows its skeleton until they and the session are both there,
 * so nothing is ever drawn and then replaced (no layout shift). `laptopTiles()` is only called once `useLaptopTiles` is true.
 */
const load = () =>
  Promise.all([
    import("./StepsTile"),
    import("./YourRequestTile"),
    import("./LiveTile"),
    import("./SideTiles"),
  ]).then(([steps, request, live, side]) => ({
    StepsTile: steps.StepsTile,
    YourRequestTile: request.YourRequestTile,
    LiveTile: live.LiveTile,
    StatsPanel: side.StatsPanel,
  }));

type Tiles = Awaited<ReturnType<typeof load>>;
let loaded: Tiles | null = null;
let loading: Promise<Tiles> | null = null;

function loadTiles(): Promise<Tiles> {
  loading ??= load().then((tiles) => (loaded = tiles));
  return loading;
}

/** True when the tiles are there to draw; on a phone, always (it never draws them). */
export function useLaptopTiles(laptop: boolean): boolean {
  const [ready, setReady] = useState(loaded !== null);
  useEffect(() => {
    if (laptop && loaded === null) {
      void loadTiles().then(() => setReady(true));
    }
  }, [laptop]);
  return !laptop || ready || loaded !== null;
}

export function laptopTiles(): Tiles {
  if (loaded === null) {
    throw new Error("the laptop's tiles are drawn before they are loaded");
  }
  return loaded;
}
