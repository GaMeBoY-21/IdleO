import { lazy, Suspense, useEffect, useState, type ComponentProps } from "react";
import type { EmailForm as Form } from "./EmailForm";
import { SkeletonLine } from "@/components/ui/Skeleton";

/**
 * The email form (React Hook Form and Zod) is fetched just after the screen around it paints, so the sign-in screen
 * never waits for it (ADR 0018). Until then, a placeholder of exactly its height holds its place.
 */
const EmailForm = lazy(() => import("./EmailForm").then((module) => ({ default: module.EmailForm })));

export function LazyEmailForm(props: ComponentProps<typeof Form>) {
  const [painted, setPainted] = useState(false);
  useEffect(() => {
    const frame = requestAnimationFrame(() => setPainted(true));
    return () => cancelAnimationFrame(frame);
  }, []);
  if (!painted) {
    return <EmailFormSkeleton />;
  }
  return (
    <Suspense fallback={<EmailFormSkeleton />}>
      <EmailForm {...props} />
    </Suspense>
  );
}

/** Label, input, the line kept for its message, and the button: the form's own heights. */
function EmailFormSkeleton() {
  return (
    <div className="flex flex-col gap-2" role="status" aria-label="Loading the email form">
      <div className="flex flex-col gap-2">
        <SkeletonLine size="small" width="w-1/6" />
        <div className="h-control pointer-coarse:h-touch rounded-control border border-hairline bg-surface-1" />
        <div className="min-h-4.5" />
      </div>
      {/* The large button's height as drawn (Button's min-h-10): 40 px, 44 on a touch screen. */}
      <div className="h-10 pointer-coarse:h-touch rounded-control border border-hairline bg-surface-1" />
    </div>
  );
}
