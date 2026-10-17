import { Clock, Cpu, Lock, Zap, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { Wordmark } from "@/brand/Wordmark";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";

/**
 * The signed-out screens, and the pages for a wrong link or a broken screen: the name, and one white tile on the page
 * colour holding one big question and what answers it. On a phone and a tablet a narrow column. From 1200 px the tile
 * is 7 columns of the grid, centred (web/DESIGN.md "Laptop layout"); with `collage`, as on sign-in, it is the left 5
 * with a collage of what the product is, in colour tiles, in the 7 beside it. The collage says only what the public
 * site says, with no numbers.
 */
export function SignInFrame({
  title,
  children,
  collage = false,
}: {
  title: string;
  children: ReactNode;
  collage?: boolean;
}) {
  return (
    <main className="flex min-h-dvh flex-col items-center px-3.5 pt-4 pb-12 md:justify-center md:py-12 xl:items-stretch xl:px-0 xl:py-0">
      <div className="flex w-full max-w-narrow flex-col gap-4 xl:max-w-none xl:flex-1 xl:px-8 xl:py-8 2xl:px-10">
        <Wordmark className="self-start px-1.5 xl:px-0" />
        <div
          className={cn(
            "flex flex-col gap-4 xl:flex-1",
            collage
              ? "xl:grid xl:grid-cols-12 xl:items-center xl:gap-4 2xl:gap-5"
              : "xl:items-center xl:justify-center",
          )}
        >
          <Tile
            as="div"
            size="lg"
            className={cn("flex flex-col", collage ? "xl:col-span-5 xl:justify-center" : "xl:w-7/12")}
          >
            <h1 className="mb-3 text-title xl:text-question">{title}</h1>
            {children}
          </Tile>
          {collage && <Collage />}
        </div>
      </div>
    </main>
  );
}

/** What the product is, in colour tiles, beside the sign-in tile (from 1200 px only). */
function Collage() {
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
            Paid in rupees, and only for the minutes it ran. We provide the GPU; you bring your own model, data and code.
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
