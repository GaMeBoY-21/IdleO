import { Clock, Cpu, Lock, Zap, type LucideIcon } from "lucide-react";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";

/** What the product is, in colour tiles, beside the sign-in tile (from 1200 px only). */
export function Collage() {
  return (
    <aside
      aria-label="About the product"
      className="hidden xl:col-span-7 xl:grid xl:grid-cols-2 xl:gap-4 2xl:gap-5"
    >
      <Tile
        tone="dark"
        size="lg"
        className="flex min-h-session-tile flex-col justify-between gap-3 xl:row-span-2 xl:p-8"
      >
        <Icon icon={Cpu} onDark />
        <div className="flex flex-col gap-3">
          <h2 className="text-question">Rent a GPU by the minute</h2>
          <p className="text-body text-dark-muted">
            Paid in rupees, and only for the minutes it ran. We provide the GPU; you bring your own model,
            data and code.
          </p>
        </div>
      </Tile>
      <Tile tone="lime" size="lg" className="flex flex-col justify-between gap-2">
        <Icon icon={Clock} />
        <div className="flex flex-col gap-2">
          <h2 className="text-title">Fine-tune a model</h2>
          <p className="text-body text-lime-fg">Upload your model and examples, and download the adapter.</p>
        </div>
      </Tile>
      <div className="grid grid-cols-2 gap-4 2xl:gap-5">
        <Tile tone="apricot" size="lg" className="flex flex-col justify-between gap-2 xl:p-6">
          <Icon icon={Zap} />
          <div className="flex flex-col gap-1">
            <h2 className="text-section">Earn with your GPU</h2>
            <p className="text-small text-apricot-fg">Host the one you aren&apos;t using.</p>
          </div>
        </Tile>
        <Tile tone="lavender" size="lg" className="flex flex-col justify-between gap-2 xl:p-6">
          <Icon icon={Lock} />
          <div className="flex flex-col gap-1">
            <h2 className="text-section">A locked box</h2>
            <p className="text-small text-lavender-fg">Jobs never touch a host&apos;s files.</p>
          </div>
        </Tile>
      </div>
    </aside>
  );
}

function Icon({ icon: Glyph, onDark = false }: { icon: LucideIcon; onDark?: boolean }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        "flex size-14 items-center justify-center rounded-icon",
        onDark ? "bg-dark-fg/10" : "bg-on-colour/10",
      )}
    >
      <Glyph className="size-6" strokeWidth={2} />
    </span>
  );
}
