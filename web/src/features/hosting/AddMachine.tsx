import { Loader } from "lucide-react";
import { useMemo, useState } from "react";
import { Link, useLocation } from "wouter";
import {
  CONNECT_POLL_MS,
  useAddMachine,
  useEnrolmentCode,
  useHost,
  type EnrolmentCode,
  type Host,
} from "@/api/hosting";
import { ApiError } from "@/api/errors";
import { Tile } from "@/components/ui/Tile";
import { Banner } from "@/components/ui/Banner";
import { Button, buttonClasses } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { ErrorState } from "@/components/ui/States";
import { gigabytes, gpuName } from "@/lib/gpu";
import { rupeesAnHour } from "@/lib/hosting";
import { problemMessage } from "@/lib/problems";
import { useTitle } from "@/lib/title";
import { EnrolmentCodeCard } from "./EnrolmentCode";
import { HostFlow } from "@/components/shell/HostFlow";
import { PriceForm } from "./PriceForm";
import { Step } from "./Step";
import { useHostId } from "@/lib/use-host-id";

const MAX_NAME = 100;

const LEDE =
  "Four short steps: name it, install the host app with a one-time code, our checks, and set the price.";

/** The agent is online and has reported a GPU. */
const isConnected = (host: Host) => host.online && host.gpus.some((gpu) => !gpu.gone);

/**
 * Add a machine, in four steps (docs/design/hosting/add-machine-laptop.html): name it, install the host app with a one-time
 * code, our checks, and set the price. It follows the machine as its agent connects, reading it every 3 seconds, and
 * moves on by itself. The machine is made when it's named (with an Idempotency-Key), so a reload carries on from
 * `?id=`; the code is only ever in this page's memory, so after a reload New code issues another.
 */
export function AddMachine() {
  useTitle("Add a machine");
  const id = useHostId();
  const [, navigate] = useLocation();
  const [name, setName] = useState("");
  const [code, setCode] = useState<EnrolmentCode | null>(null);
  const [saved, setSaved] = useState(false);
  const add = useAddMachine();
  const issue = useEnrolmentCode();
  const trimmed = name.trim();
  // The same name is the same request, so pressing Next twice, or after a failure, never makes two machines.
  // eslint-disable-next-line react-hooks/exhaustive-deps -- a new key for each different name
  const idempotencyKey = useMemo(() => crypto.randomUUID(), [trimmed]);
  const host = useHost(id, { ms: CONNECT_POLL_MS, until: isConnected });
  const machine = host.data;
  const connected = machine !== undefined && isConnected(machine);
  const gpu = machine?.gpus.find((candidate) => !candidate.gone);
  const nameProblem =
    trimmed.length === 0
      ? "Give it a name."
      : trimmed.length > MAX_NAME
        ? `Use at most ${MAX_NAME} characters.`
        : undefined;

  const newCode = () => {
    if (id) {
      issue.mutate(id, { onSuccess: setCode });
    }
  };

  if (id !== null && !machine) {
    const gone = host.isError && host.error instanceof ApiError && [404, 410].includes(host.error.status);
    return (
      <HostFlow back="/hosting/" backLabel="Hosting" title="Add this machine" lede={LEDE}>
        {host.isError ? (
          gone ? (
            <Banner tone="warning">
              This machine isn&apos;t here any more. Start again from the Hosting tab.
            </Banner>
          ) : (
            <ErrorState
              error={host.error}
              what="this machine"
              onRetry={() => void host.refetch()}
              retrying={host.isFetching}
            />
          )
        ) : (
          <div
            role="status"
            aria-label="Loading this machine"
            className="skeleton h-24 w-full rounded-tile"
          />
        )}
      </HostFlow>
    );
  }

  return (
    <HostFlow back="/hosting/" backLabel="Hosting" title="Add this machine" lede={LEDE}>
      <Tile as="div">
        <ol className="flex flex-col">
          <Step number={1} title="Name it" state={machine ? "done" : "active"} summary={machine?.name}>
            <form
              className="flex flex-col gap-3"
              onSubmit={(event) => {
                event.preventDefault();
                if (nameProblem) {
                  return;
                }
                add.mutate(
                  { name: trimmed, idempotencyKey },
                  {
                    onSuccess: (created) => {
                      issue.mutate(created.id, { onSuccess: setCode });
                      navigate(`/hosting/add/?id=${created.id}`, { replace: true });
                    },
                  },
                );
              }}
            >
              <Field
                label="What do you call this machine?"
                value={name}
                onChange={(event) => {
                  setName(event.target.value);
                  add.reset();
                }}
                placeholder="Nikki's ROG Strix"
                error={name.length > 0 ? nameProblem : undefined}
                hint="Only you see this."
                autoComplete="off"
              />
              {add.isError && <Banner tone="danger">{problemMessage(add.error)}</Banner>}
              <Button type="submit" large block busy={add.isPending} disabled={nameProblem !== undefined}>
                Next
              </Button>
            </form>
          </Step>
          <Step
            number={2}
            title="Install"
            state={connected ? "done" : machine ? "active" : "later"}
            summary={
              connected && gpu ? `Connected · ${gpuName(gpu.model)} · ${gigabytes(gpu.vramMb)}` : undefined
            }
          >
            <EnrolmentCodeCard code={code} issuing={issue.isPending} onNewCode={newCode} />
            {issue.isError && <Banner tone="danger">{problemMessage(issue.error)}</Banner>}
            <p className="flex items-center gap-2 text-small font-semibold" role="status">
              <Loader aria-hidden="true" className="size-4 motion-safe:animate-spin" strokeWidth={2} />
              Waiting for your machine
            </p>
            <p className="-mt-2 text-small text-text-muted">
              This page moves on by itself once the host app connects.
            </p>
          </Step>
          <Step
            number={3}
            title="Quick checks"
            state={machine?.status === "active" ? "done" : "later"}
            summary={
              machine?.status === "active"
                ? "Passed. Your machine is ready for work."
                : connected
                  ? "Pending. We check your machine once you've set a price, by hand for now, so it can take a while."
                  : "We check your machine once it has connected."
            }
          />
          <Step
            number={4}
            title="Set your price"
            state={connected ? (saved ? "done" : "active") : "later"}
            summary={
              saved && machine?.listing
                ? `${rupeesAnHour(machine.listing.batchPricePaisePerMin)} an hour`
                : undefined
            }
          >
            {machine && (
              <PriceForm host={machine} saveLabel="Save and finish" onSaved={() => setSaved(true)} />
            )}
          </Step>
        </ol>
      </Tile>
      {saved && machine && (
        <div className="flex flex-col gap-3">
          <Banner tone="info">
            {machine.status === "active"
              ? "You're set. It will earn whenever it's online and idle."
              : "You're set. Your machine starts earning once it passes our checks."}
          </Banner>
          <Link href={`/hosting/machine/?id=${machine.id}`} className={buttonClasses("primary", true, true)}>
            Open your machine
          </Link>
        </div>
      )}
      <Tile as="div">
        <ul className="flex flex-col gap-2 text-small text-text-muted">
          <li>Renter work runs in a locked box. It can&apos;t see your files or your home network.</li>
          <li>
            Take your machine back any time from the tray icon. A session stops safely within 2 minutes.
          </li>
          <li>Keep it plugged in. If your laptop has a battery charge limit, turn it on.</li>
        </ul>
      </Tile>
    </HostFlow>
  );
}
