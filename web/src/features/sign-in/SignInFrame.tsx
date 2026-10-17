import type { ReactNode } from "react";
import { Wordmark } from "@/brand/Wordmark";
import { Tile } from "@/components/ui/Tile";

/**
 * The signed-out screens, and the pages for a wrong link or a broken screen: the name, and one white tile on the page
 * colour holding one big question and what answers it, in one narrow column, the same on a phone and a laptop
 * (web/DESIGN.md "Account, sign-in and the rest").
 */
export function SignInFrame({ title, children }: { title: string; children: ReactNode }) {
  return (
    <main className="flex min-h-dvh flex-col items-center px-3.5 pt-4 pb-12 md:justify-center md:py-12">
      <div className="flex w-full max-w-narrow flex-col gap-4">
        <Wordmark className="self-start px-1.5" />
        <Tile as="div" className="flex flex-col p-6">
          <h1 className="mb-3 text-title">{title}</h1>
          {children}
        </Tile>
      </div>
    </main>
  );
}
