import { TileLabel, TileLink } from "@/components/ui/Tile";

/** Hosting, for gamers (docs/design/bento/home-phone.html): the apricot tile that opens the Hosting tab. */
export function EarnTile() {
  return (
    <TileLink href="/hosting/" tone="ember" className="col-span-2 row-span-2">
      <TileLabel tone="ember">For gamers</TileLabel>
      <span className="flex flex-col gap-1">
        <span className="text-headline">
          Your GPU sits idle.
          <br />
          Let it earn.
        </span>
        <TileLabel tone="ember">Rent out your gaming laptop →</TileLabel>
      </span>
    </TileLink>
  );
}
