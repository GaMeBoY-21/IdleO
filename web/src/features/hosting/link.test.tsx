import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/api/errors";
import type { HostLink } from "@/api/hosting";
import { LinkApproval } from "./LinkApproval";

const mocks = vi.hoisted(() => ({
  link: {
    data: undefined as HostLink | undefined,
    isError: false,
    error: null as Error | null,
    isFetching: false,
    refetch: vi.fn(),
  },
  band: {
    data: undefined as { lowPaisePerMin: number; highPaisePerMin: number; sampleSize: number } | undefined,
    isError: false,
    isLoading: false,
  },
  approve: { mutate: vi.fn(), reset: vi.fn(), isPending: false, isError: false, error: null },
  deny: { mutate: vi.fn(), reset: vi.fn(), isPending: false, isError: false, error: null },
}));

vi.mock("@/api/hosting", () => ({
  useHostLink: () => mocks.link,
  useGpuPriceBand: () => mocks.band,
  useApproveHostLink: () => mocks.approve,
  useDenyHostLink: () => mocks.deny,
}));

const now = new Date("2026-10-17T10:00:00Z").getTime();

const pending: HostLink = {
  id: "01a14b00-0000-7000-8000-000000000001",
  machineName: "ASUS ROG Strix",
  os: "windows",
  agentVersion: "0.1.0",
  gpus: [{ model: "NVIDIA GeForce RTX 4080 Laptop GPU", vramMb: 12282 }],
  state: "pending",
  expiresAt: new Date(now + 9 * 60_000 + 41_000).toISOString(),
  hostId: null,
  awaitsActivation: true,
};

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(now);
  mocks.link.data = pending;
  mocks.link.isError = false;
  mocks.link.error = null;
  mocks.band.data = { lowPaisePerMin: 12, highPaisePerMin: 20, sampleSize: 0 };
  mocks.band.isError = false;
  mocks.approve.mutate.mockReset();
  mocks.deny.mutate.mockReset();
});
afterEach(() => vi.useRealTimers());

const user = () => userEvent.setup({ advanceTimers: () => undefined });

describe("the approval page", () => {
  it("shows exactly which laptop is asking, as it described itself, and how long the link has left", () => {
    render(<LinkApproval id={pending.id} />);
    expect(
      screen.getByRole("heading", { level: 1, name: "Link this laptop to your account?" }),
    ).toBeInTheDocument();
    expect(screen.getByText("ASUS ROG Strix", { selector: "dd" })).toBeInTheDocument();
    expect(screen.getByText(/RTX 4080 Laptop/)).toBeInTheDocument();
    expect(screen.getByText("Windows")).toBeInTheDocument();
    expect(screen.getByText("0.1.0")).toBeInTheDocument();
    expect(screen.getByText(/Expires in 09:41/)).toBeInTheDocument();
  });

  it("prefills the name and a price from the middle of the suggested band, with what the owner earns", () => {
    render(<LinkApproval id={pending.id} />);
    expect(screen.getByLabelText("Name")).toHaveValue("ASUS ROG Strix");
    // 16 paise a minute is ₹9.60 an hour; the owner keeps 80% of it.
    expect(screen.getByText(/You'll earn/)).toHaveTextContent(
      "You'll earn ₹7.68 an hour (renters pay ₹9.60)",
    );
  });

  it("approves in one click with the name, the price and the default limits", async () => {
    render(<LinkApproval id={pending.id} />);
    await user().click(screen.getByRole("button", { name: "Approve and start earning" }));
    expect(mocks.approve.mutate).toHaveBeenCalledWith({
      name: "ASUS ROG Strix",
      batchPricePaisePerMin: 16,
      gpuTempCapC: 85,
      keepFreeGb: 20,
    });
  });

  it("lets the price be changed before approving, in the form a machine has", async () => {
    render(<LinkApproval id={pending.id} />);
    await user().click(screen.getByRole("button", { name: "Change" }));
    await user().click(screen.getByRole("button", { name: /Raise the price by/ }));
    expect(screen.getByText(/You'll earn/)).toHaveTextContent("₹8.16 an hour (renters pay ₹10.20)");
    await user().clear(screen.getByLabelText("Name"));
    await user().type(screen.getByLabelText("Name"), "  Nikki's laptop ");
    await user().click(screen.getByRole("button", { name: "Approve and start earning" }));
    expect(mocks.approve.mutate).toHaveBeenCalledWith(
      expect.objectContaining({ name: "Nikki's laptop", batchPricePaisePerMin: 17 }),
    );
  });

  it("won't approve without a name, or before it knows the price", async () => {
    render(<LinkApproval id={pending.id} />);
    await user().clear(screen.getByLabelText("Name"));
    expect(screen.getByRole("button", { name: "Approve and start earning" })).toBeDisabled();
    expect(screen.getByText("Give it a name.")).toBeInTheDocument();
  });

  it("holds the button while the price is looked up", () => {
    mocks.band.data = undefined;
    render(<LinkApproval id={pending.id} />);
    expect(screen.getByRole("button", { name: "Approve and start earning" })).toBeDisabled();
  });

  it("falls back to ₹7.20 an hour when no price can be suggested", () => {
    mocks.band.data = undefined;
    mocks.band.isError = true;
    render(<LinkApproval id={pending.id} />);
    expect(screen.getByText(/You'll earn/)).toHaveTextContent("renters pay ₹7.20");
  });

  it("denies with 'This isn't my laptop'", async () => {
    render(<LinkApproval id={pending.id} />);
    await user().click(screen.getByRole("button", { name: "This isn't my laptop" }));
    expect(mocks.deny.mutate).toHaveBeenCalled();
    expect(mocks.approve.mutate).not.toHaveBeenCalled();
  });
});

describe("a link that is over", () => {
  it("says an expired one has run out, and where to start again", () => {
    mocks.link.data = { ...pending, state: "expired" };
    render(<LinkApproval id={pending.id} />);
    expect(screen.getByText("This link has expired")).toBeInTheDocument();
    expect(screen.getByText(/Start again from the app on your laptop/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve and start earning" })).not.toBeInTheDocument();
  });

  it("says a denied one linked nothing", () => {
    mocks.link.data = { ...pending, state: "denied" };
    render(<LinkApproval id={pending.id} />);
    expect(screen.getByText("Nothing was linked")).toBeInTheDocument();
  });

  it("says an approved one is linked, that new laptops are checked first, and opens it", () => {
    mocks.link.data = { ...pending, state: "approved", hostId: "01a14b00-0000-7000-8000-0000000000aa" };
    render(<LinkApproval id={pending.id} />);
    expect(screen.getByText("Linked")).toBeInTheDocument();
    expect(screen.getByText(/We check every new laptop before it goes live/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open your laptop" })).toHaveAttribute(
      "href",
      "/hosting/machine/?id=01a14b00-0000-7000-8000-0000000000aa",
    );
  });

  it("says plainly when the link isn't there", () => {
    mocks.link.data = undefined;
    mocks.link.isError = true;
    mocks.link.error = ApiError.of(404, { type: "/problems/not-found", title: "Not found", status: 404 });
    render(<LinkApproval id={pending.id} />);
    expect(screen.getByText("We can't find that link")).toBeInTheDocument();
  });
});
