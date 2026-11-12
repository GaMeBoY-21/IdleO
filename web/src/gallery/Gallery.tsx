import { Cpu, Layers } from "lucide-react";
import { useState, type ReactNode } from "react";
import { ApiError } from "@/api/errors";
import { Banner } from "@/components/ui/Banner";
import { Bars, BarsSkeleton } from "@/components/ui/Bars";
import { Button, type ButtonVariant } from "@/components/ui/Button";
import { Checklist } from "@/components/ui/Checklist";
import { CopyButton } from "@/components/ui/CopyButton";
import { Field } from "@/components/ui/Field";
import { PageHeading } from "@/components/ui/Heading";
import { RadioGroup, Select, Switch, TextArea } from "@/components/ui/Inputs";
import { Money } from "@/components/ui/Money";
import { IconTile, Option } from "@/components/ui/Option";
import { ProgressBar } from "@/components/ui/Progress";
import { Skeleton, SkeletonLine } from "@/components/ui/Skeleton";
import { StatusChip, Tag } from "@/components/ui/StatusChip";
import { EmptyState, ErrorState, StaleNotice } from "@/components/ui/States";
import { SectionTitle, Tile, TileLabel, type TileTone } from "@/components/ui/Tile";
import { Elapsed, RelativeTime } from "@/components/ui/Time";
import { motion, radii, spacing, themes, typeScale } from "@/design/tokens";
import { STATE_DOMAINS, type StateDomain } from "@/lib/states";
import { Sheet } from "@/features/admin/Sheet";
import { Empty, Fields, Filters, Id, Problem, Section, SmallField, TableSkeleton } from "@/features/admin/ui";

/**
 * The component gallery (web/README.md "The component gallery"): the tokens, then every shared component in each of its
 * variants and states, the renter's and host's set and the console's, in the theme chosen at the top, or both side by
 * side (`?both`). Development servers only (vite.config.ts, galleryPage); a build never holds it.
 */
const SESSION_STATES = [
  "REQUESTED",
  "PROVISIONING",
  "RUNNING",
  "HOST_LOST",
  "FINISHING",
  "COMPLETED",
  "FAILED",
  "CANCELLED",
  "NO_HOST_FREE",
  "ENDED_REFUNDED",
  "SOMETHING_NEW",
];
const VARIANTS: ButtonVariant[] = ["primary", "secondary", "ghost", "live", "ink", "quiet", "danger"];
const TONES: TileTone[] = ["panel", "night", "volt", "ion", "ember", "ion-deep"];
const NOW = new Date();
const minutesAgo = (minutes: number) => new Date(NOW.getTime() - minutes * 60_000).toISOString();

function Group({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  return (
    <section
      id={id}
      aria-labelledby={`${id}-title`}
      className="flex flex-col gap-4 border-t border-line pt-6"
    >
      <h2 id={`${id}-title`} className="text-section">
        {title}
      </h2>
      {children}
    </section>
  );
}

function Specimen({ name, children }: { name: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-2">
      <p className="text-caption text-text-3">{name}</p>
      <div className="flex flex-wrap items-start gap-4">{children}</div>
    </div>
  );
}

function ThemeSwitch() {
  // The theme drawn: one chosen here, or the device's.
  const [theme, setTheme] = useState(
    () =>
      document.documentElement.dataset.theme ??
      (matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark"),
  );
  return (
    <div role="group" aria-label="Theme" className="flex gap-2">
      {["dark", "light"].map((choice) => (
        <Button
          key={choice}
          variant={theme === choice ? "primary" : "ghost"}
          onClick={() => {
            document.documentElement.dataset.theme = choice;
            setTheme(choice);
          }}
        >
          {choice === "dark" ? "Night Shift" : "Day Shift"}
        </Button>
      ))}
      <a
        className="inline-flex min-h-touch items-center px-2 text-small font-semibold underline"
        href="?both"
      >
        Both side by side
      </a>
    </div>
  );
}

function Tokens() {
  return (
    <>
      <Specimen name="Colours (this theme)">
        {Object.keys(themes.dark).map((token) => (
          <div key={token} className="flex w-32 flex-col gap-1">
            <span
              className="h-10 rounded-icon border border-line"
              style={{ background: `var(--${token})` }}
            />
            <span className="text-caption">{token}</span>
          </div>
        ))}
      </Specimen>
      <Specimen name="Type scale (Inter)">
        <div className="flex flex-col gap-2">
          {Object.entries(typeScale).map(([name, [size, line]]) => (
            <p key={name} className={`text-${name}`}>
              {name}: {size}/{line} px, ₹1,23,456.78
            </p>
          ))}
        </div>
      </Specimen>
      <Specimen name="Page title (Archivo), PageHeading">
        <PageHeading>Book a GPU</PageHeading>
      </Specimen>
      <Specimen name="Spacing">
        {Object.entries(spacing)
          .filter(([, px]) => typeof px === "number" && px > 0 && px <= 112)
          .map(([name, px]) => (
            <div key={name} className="flex flex-col items-center gap-1">
              <span className="block bg-ion" style={{ width: px, height: px }} />
              <span className="text-caption tabular">
                {name} · {px}
              </span>
            </div>
          ))}
      </Specimen>
      <Specimen name="Radii">
        {Object.entries(radii)
          .filter(([, px]) => px < 100)
          .map(([name, px]) => (
            <div key={name} className="flex flex-col items-center gap-1">
              <span className="block size-16 bg-panel-2" style={{ borderRadius: px }} />
              <span className="text-caption">
                {name} · {px}
              </span>
            </div>
          ))}
      </Specimen>
      <Specimen name="Motion">
        <p className="text-small">
          {Object.entries(motion.durations)
            .map(([name, ms]) => `${name} ${ms} ms`)
            .join(" · ")}
          ; entering {motion.easing.enter}, leaving {motion.easing.leave}
        </p>
      </Specimen>
    </>
  );
}

function Buttons() {
  return (
    <>
      {VARIANTS.map((variant) => (
        <Specimen key={variant} name={`Button, ${variant}: plain, pending, disabled, large`}>
          <Button variant={variant}>Suspend user</Button>
          <Button variant={variant} busy>
            Suspending
          </Button>
          <Button variant={variant} disabled title="You can't suspend an admin">
            Suspend user
          </Button>
          <Button variant={variant} large>
            Request a GPU
          </Button>
        </Specimen>
      ))}
      <Specimen name="Copy button">
        <CopyButton text="idleo login" label="Copy the command" />
      </Specimen>
    </>
  );
}

function Forms() {
  const [on, setOn] = useState(true);
  return (
    <div className="grid gap-6 md:grid-cols-2">
      <Field label="Name" placeholder="Asha" />
      <Field label="UPI id" hint="Your bank's app shows it, like name@bank." defaultValue="asha@okbank" />
      <Field label="Amount" error="Enter at least ₹10." defaultValue="₹5" />
      <Field
        label="Email"
        disabled
        defaultValue="asha@example.com"
        hint="Your sign-in email can't be changed here."
      />
      <TextArea label="What happened" hint="5 to 500 characters." />
      <Select label="GPU memory">
        <option>8 GB</option>
        <option>12 GB</option>
        <option>16 GB</option>
      </Select>
      <Switch label="Email me when a session ends" checked={on} onChange={() => setOn(!on)} />
      <RadioGroup
        legend="Appearance"
        options={[
          { value: "device", label: "Match my device" },
          { value: "light", label: "Light" },
          { value: "dark", label: "Dark" },
        ]}
        inputProps={(value) => ({ name: "appearance", defaultChecked: value === "device" })}
      />
    </div>
  );
}

function Tiles() {
  return (
    <div className="grid gap-4 md:grid-cols-3">
      {TONES.map((tone) => (
        <Tile key={tone} tone={tone}>
          <TileLabel tone={tone}>{tone}</TileLabel>
          <SectionTitle>A panel's title</SectionTitle>
          <p className="text-small">A line of what it holds, and a figure: ₹1,234.50.</p>
          <Button variant={tone === "night" ? "live" : tone === "volt" ? "ink" : "ghost"}>Open</Button>
        </Tile>
      ))}
    </div>
  );
}

function States() {
  const failed = new ApiError(503, null, "Unavailable", null);
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Tile>
        <SectionTitle>Loading: skeleton lines and a tile</SectionTitle>
        <SkeletonLine size="section" width="60%" />
        <SkeletonLine size="body" width="90%" />
        <Skeleton className="h-24" />
      </Tile>
      <Tile>
        <SectionTitle>Empty</SectionTitle>
        <EmptyState title="No sessions yet" action={<Button>Book a GPU</Button>}>
          Book a GPU to run your first one. It shows here while it runs and after.
        </EmptyState>
      </Tile>
      <Tile>
        <SectionTitle>Error, with retry</SectionTitle>
        <ErrorState error={failed} what="your sessions" onRetry={() => undefined} />
      </Tile>
      <Tile>
        <SectionTitle>Error, retrying</SectionTitle>
        <ErrorState error={failed} what="your sessions" onRetry={() => undefined} retrying />
      </Tile>
      <div className="md:col-span-2">
        <StaleNotice updatedAt={NOW.getTime() - 5 * 60_000} />
      </div>
      <Banner tone="info">Payouts start with the paid pilot.</Banner>
      <Banner tone="warning" action={<Button variant="ghost">Add credit</Button>}>
        Your credit is running low.
      </Banner>
      <Banner tone="danger">We couldn't stop the session. Try again.</Banner>
    </div>
  );
}

function Chips() {
  return (
    <>
      <Specimen name="State chip: session (the renter's words)">
        {SESSION_STATES.map((state) => (
          <StatusChip key={state} domain="session" state={state} />
        ))}
      </Specimen>
      {Object.entries(STATE_DOMAINS)
        .filter(([domain]) => domain !== "session")
        .map(([domain, words]) => (
          <Specimen key={domain} name={`State chip: ${domain}`}>
            {Object.keys(words).map((state) => (
              <StatusChip key={state} domain={domain as StateDomain} state={state} size="sm" />
            ))}
          </Specimen>
        ))}
      <Specimen name="Tag (a label, not a state)">
        <Tag>Pinned</Tag>
        <Tag>A law sets this</Tag>
      </Specimen>
    </>
  );
}

function Pieces() {
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Option icon={Layers} title="Fine-tune a model" detail="Your model and data, about 40 min" />
      <Option icon={Cpu} title="Run your code" detail="Your folder, our GPU" selected />
      <div className="flex gap-2">
        <IconTile icon={Cpu} />
        <IconTile icon={Layers} size="lg" />
      </div>
      <ProgressBar percent={62} label="Uploaded 62%" />
      <Tile tone="night">
        <Bars
          label="Earned this week"
          tone="night"
          bars={["M", "T", "W", "T", "F", "S", "S"].map((day, index) => ({
            key: `${day}${index}`,
            value: [3, 0, 5, 8, 2, 0, 6][index]!,
            label: day,
            spoken: `${day}: ₹${[3, 0, 5, 8, 2, 0, 6][index]}`,
          }))}
        />
      </Tile>
      <Tile>
        <BarsSkeleton count={7} tone="panel" />
      </Tile>
      <Checklist
        id="gallery-checklist"
        title="Get started"
        steps={[
          { title: "Accept the Terms", detail: "Done.", done: true },
          {
            title: "Add credit",
            detail: "₹500 to start.",
            done: false,
            action: { href: "#", label: "Add credit" },
          },
          { title: "Run a first test job", detail: "Being checked.", done: null },
        ]}
      />
      <Specimen name="Money and time">
        <span className="text-figure">
          <Money paise={12345678} />
        </span>
        <RelativeTime value={minutesAgo(18)} prefix="started" />
        <Elapsed from={minutesAgo(62)} />
      </Specimen>
    </div>
  );
}

function ConsoleSet() {
  const [open, setOpen] = useState(false);
  return (
    <div className="flex flex-col gap-6">
      <Section title="A section's header" action={<Button variant="ghost">Export</Button>}>
        <Fields
          items={[
            ["Name", "Asha Rao"],
            ["Joined", <RelativeTime key="joined" value={minutesAgo(60 * 24 * 40)} />],
            ["Id", <Id key="id" value="01a1340a-0000-7000-8000-000000000a5a" />],
            ["Credit", <Money key="credit" paise={46212} />],
          ]}
        />
      </Section>
      <Filters onSubmit={() => undefined}>
        <SmallField label="Email" value="" onChange={() => undefined} />
        <SmallField label="Phone" value="" onChange={() => undefined} />
      </Filters>
      <TableSkeleton what="users" rows={3} />
      <Empty title="Nobody matches">Try a shorter part of their email, or their phone number.</Empty>
      <Problem
        error={new ApiError(409, null, "Conflict", "Someone else changed this user. Open it again.")}
      />
      <div>
        <Button variant="ghost" onClick={() => setOpen(true)}>
          Open a sheet
        </Button>
        <Sheet
          open={open}
          onOpenChange={setOpen}
          title="Suspend Asha Rao"
          description="She can't book until you lift it."
        >
          <p className="text-small">A sheet's body.</p>
        </Sheet>
      </div>
    </div>
  );
}

const GROUPS: [string, string, () => ReactNode][] = [
  ["tokens", "Tokens", Tokens],
  ["buttons", "Buttons", Buttons],
  ["forms", "Form fields", Forms],
  ["tiles", "Tiles and panels", Tiles],
  ["states", "Loading, empty, error, stale and banners", States],
  ["chips", "State chips and pills", Chips],
  ["pieces", "Options, progress, bars, checklist, money and time", Pieces],
  ["console", "The console's own set", ConsoleSet],
];

export function Gallery() {
  if (new URLSearchParams(location.search).has("both")) {
    return (
      <div className="grid h-screen grid-cols-2">
        {["dark", "light"].map((theme) => (
          <iframe
            key={theme}
            title={`The gallery, ${theme}`}
            src={`?theme=${theme}`}
            className="h-full w-full border-0"
          />
        ))}
      </div>
    );
  }
  return (
    <main className="flex flex-col gap-8 px-4 py-8 md:px-8">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <PageHeading>Component gallery</PageHeading>
        <ThemeSwitch />
      </header>
      <nav aria-label="Sections" className="flex flex-wrap gap-x-4 text-small">
        {GROUPS.map(([id, title]) => (
          <a key={id} href={`#${id}`} className="inline-flex min-h-touch items-center underline">
            {title}
          </a>
        ))}
      </nav>
      {GROUPS.map(([id, title, Body]) => (
        <Group key={id} id={id} title={title}>
          <Body />
        </Group>
      ))}
    </main>
  );
}
