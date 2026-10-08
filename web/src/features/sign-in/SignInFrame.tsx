import type { ReactNode } from "react";
import { Wordmark } from "@/brand/Wordmark";

/**
 * The signed-out screens, and the pages for a wrong link or a broken screen: the wordmark, one big question and
 * what answers it, in one narrow column, the same on a phone and a laptop (web/DESIGN.md "Sign-in, welcome").
 */
export function SignInFrame({ title, children }: { title: string; children: ReactNode }) {
  return (
    <main className="flex min-h-dvh flex-col items-center px-4 pt-6 pb-12 md:justify-center md:py-12">
      <div className="flex w-full max-w-narrow flex-col">
        <Wordmark className="mb-12 h-7 w-auto self-start md:mb-14" />
        <h1 className="mb-3 text-title">{title}</h1>
        {children}
      </div>
    </main>
  );
}
