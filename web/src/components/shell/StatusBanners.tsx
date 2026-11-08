import { useServiceStatus } from "@/api/status";
import { Banner } from "@/components/ui/Banner";
import { useAfterFirstScreen } from "@/lib/after-first-screen";

/**
 * What's paused, and maintenance (ADR 0056), in the frame under the header, so it pushes the screen down and never
 * covers it. The words are the server's, which say when we expect to be back; running sessions carry on, so it never
 * says otherwise. Nothing is asked before the first screen has loaded.
 */
export function StatusBanners() {
  return useAfterFirstScreen() ? <Paused /> : null;
}

function Paused() {
  const status = useServiceStatus();
  const paused = status.data?.paused ?? [];
  if (paused.length === 0) {
    return null;
  }
  return (
    <div className="flex flex-col gap-2 px-4 pt-2 md:px-8" aria-label="Service status" role="region">
      {paused.map((item) => (
        <Banner key={item.flag} tone="warning">
          {item.message}
          {item.flag === "maintenance" && (
            <span className="block">You can look at everything; changes wait until we're done.</span>
          )}
        </Banner>
      ))}
    </div>
  );
}
