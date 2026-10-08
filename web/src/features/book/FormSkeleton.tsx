import { SkeletonLine } from "@/components/ui/Skeleton";

/** The form before it can be shown: a card of labelled fields. */
export function FormSkeleton() {
  return (
    <div
      role="status"
      aria-label="Loading the form"
      className="rounded-card border border-border bg-surface p-4 md:p-6"
    >
      <SkeletonLine size="title" width="w-1/4" />
      <div className="mt-4 flex flex-col gap-5">
        {[0, 1, 2].map((key) => (
          <div key={key}>
            <SkeletonLine size="small" width="w-1/4" />
            <div className="mt-2 h-touch rounded-control bg-sunken" />
          </div>
        ))}
      </div>
    </div>
  );
}
