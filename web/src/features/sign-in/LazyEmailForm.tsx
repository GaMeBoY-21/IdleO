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
        <div className="h-14 rounded-control bg-panel" />
        <div className="min-h-4.5" />
      </div>
      {/* The large button's height as drawn (Button's min-h-14 md:min-h-15): 56 px on a phone, 60 on a laptop. */}
      <div className="h-14 rounded-control bg-panel md:h-15" />
    </div>
  );
}
