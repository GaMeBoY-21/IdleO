import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/api/errors";
import type { BillingDetails, Invoice, RefundRequests, TopUp } from "@/api/wallet";
import { queryResult, wallet } from "@/test/fixtures";
import { AddCredit } from "./AddCredit";
import { BillingDetailsTile } from "./BillingDetailsTile";
import { Documents } from "./Documents";
import { RefundTile } from "./RefundTile";
import { SLOW_AFTER_MS } from "./usePay";

const api = vi.hoisted(() => ({
  orderTopUp: vi.fn(),
  confirmTopUp: vi.fn(),
  fetchTopUp: vi.fn(),
  refreshAfterTopUp: vi.fn(() => Promise.resolve()),
  downloadInvoice: vi.fn(),
  ask: { mutate: vi.fn(), isPending: false, isError: false, error: null },
  save: { mutate: vi.fn(), isPending: false, error: null },
}));

vi.mock("@/api/wallet", async (original) => ({
  ...(await original<typeof import("@/api/wallet")>()),
  orderTopUp: api.orderTopUp,
  confirmTopUp: api.confirmTopUp,
  fetchTopUp: api.fetchTopUp,
  refreshAfterTopUp: api.refreshAfterTopUp,
  downloadInvoice: api.downloadInvoice,
  useAskRefund: () => api.ask,
  useSaveBillingDetails: () => api.save,
}));

const checkout = vi.hoisted(() => ({ pay: vi.fn(), available: true }));

vi.mock("./checkout", async (original) => ({
  ...(await original<typeof import("./checkout")>()),
  pay: checkout.pay,
  checkoutAvailable: () => checkout.available,
}));

const { CheckoutClosed, PaymentFailed } = await import("./checkout");

const STATES = [
  { code: "29", name: "Karnataka" },
  { code: "27", name: "Maharashtra" },
];
const withState: BillingDetails = {
  details: { stateCode: "29", gstin: null, businessName: null },
  gstPercent: 0,
  pricesIncludeGst: true,
  states: STATES,
};
const withoutState: BillingDetails = { ...withState, details: null };

const topUp = (status: string, amountPaise = 50_000): TopUp => ({
  id: "01a12ffa-0000-7000-8000-0000000000a1",
  amountPaise,
  currency: "INR",
  orderId: "order_Fk000000000001",
  keyId: "rzp_test_idleofake00001",
  status,
  createdAt: "2026-10-23T10:00:00Z",
  paidAt: status === "paid" ? "2026-10-23T10:00:05Z" : null,
  method: "upi",
  cardNetwork: null,
  cardLast4: null,
  failureReason: status === "failed" ? "Payment failed because the UPI ID is invalid." : null,
});

const answer = {
  razorpay_payment_id: "pay_Fk000000000002",
  razorpay_order_id: "order_Fk000000000001",
  razorpay_signature: "signature",
};

function renderWith(element: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{element}</QueryClientProvider>);
}

beforeEach(() => {
  for (const mock of [
    api.orderTopUp,
    api.confirmTopUp,
    api.fetchTopUp,
    checkout.pay,
    api.ask.mutate,
    api.save.mutate,
  ]) {
    mock.mockReset();
  }
  checkout.available = true;
});

afterEach(() => {
  vi.useRealTimers();
});

describe("adding credit", () => {
  it("waits for the renter's state before it lets them pay, and says where to give it", () => {
    renderWith(<AddCredit details={queryResult({ data: withoutState })} />);
    expect(screen.getByRole("button", { name: "Pay ₹500.00" })).toBeDisabled();
    expect(screen.getByRole("link", { name: "Billing details" })).toHaveAttribute("href", "#billing-details");
  });

  it("says it's adding until the server has the captured payment, and only then that it's added", async () => {
    api.orderTopUp.mockResolvedValue(topUp("pending", 100_000));
    checkout.pay.mockResolvedValue(answer);
    let confirm: (value: TopUp) => void = () => undefined;
    api.confirmTopUp.mockReturnValue(new Promise<TopUp>((resolve) => (confirm = resolve)));
    api.fetchTopUp.mockResolvedValue(topUp("pending", 100_000));
    renderWith(<AddCredit details={queryResult({ data: withState })} />);

    await userEvent.click(screen.getByRole("button", { name: "₹1,000" }));
    await userEvent.click(screen.getByRole("button", { name: "Pay ₹1,000.00" }));

    expect(await screen.findByText("Adding ₹1,000.00…")).toBeInTheDocument();
    expect(screen.queryByText(/added\./)).not.toBeInTheDocument();
    expect(api.orderTopUp).toHaveBeenCalledWith(100_000, expect.any(String));
    expect(checkout.pay).toHaveBeenCalledWith(
      expect.objectContaining({
        key: "rzp_test_idleofake00001",
        order_id: "order_Fk000000000001",
        currency: "INR",
      }),
    );
    await act(async () => confirm(topUp("paid", 100_000)));
    expect(await screen.findByText("₹1,000.00 added.")).toBeInTheDocument();
    expect(api.refreshAfterTopUp).toHaveBeenCalled();
  });

  it("keeps checking, and says it's slow, while Razorpay hasn't confirmed", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    api.orderTopUp.mockResolvedValue(topUp("pending"));
    checkout.pay.mockResolvedValue(answer);
    api.confirmTopUp.mockRejectedValue(new ApiError(503, "/problems/payments-unavailable", null, "slow", []));
    api.fetchTopUp.mockResolvedValue(topUp("pending"));
    renderWith(<AddCredit details={queryResult({ data: withState })} />);

    await userEvent.click(screen.getByRole("button", { name: "Pay ₹500.00" }));
    expect(await screen.findByText("Adding ₹500.00…")).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(SLOW_AFTER_MS + 2_000);
    });
    expect(
      screen.getByText(/taking longer than usual to confirm it. We'll keep checking/),
    ).toBeInTheDocument();
    expect(api.fetchTopUp.mock.calls.length).toBeGreaterThan(3);

    api.fetchTopUp.mockResolvedValue(topUp("paid"));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_500);
    });
    expect(screen.getByText("₹500.00 added.")).toBeInTheDocument();
  });

  it("says it's slow while the confirmation itself is slow, and adds it once the server has it", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    api.orderTopUp.mockResolvedValue(topUp("pending"));
    checkout.pay.mockResolvedValue(answer);
    api.confirmTopUp.mockReturnValue(new Promise<TopUp>(() => undefined));
    api.fetchTopUp.mockResolvedValue(topUp("pending"));
    renderWith(<AddCredit details={queryResult({ data: withState })} />);
    await userEvent.click(screen.getByRole("button", { name: "Pay ₹500.00" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(SLOW_AFTER_MS + 2_000);
    });
    expect(screen.getByText(/taking longer than usual to confirm it/)).toBeInTheDocument();
    // The webhook credited it meanwhile: the server says so, though the confirmation never answered.
    api.fetchTopUp.mockResolvedValue(topUp("paid"));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_500);
    });
    expect(screen.getByText("₹500.00 added.")).toBeInTheDocument();
  });

  it("says why a payment failed, in Razorpay's words, and adds nothing", async () => {
    api.orderTopUp.mockResolvedValue(topUp("pending"));
    checkout.pay.mockRejectedValue(new PaymentFailed("Payment failed because the UPI ID is invalid."));
    renderWith(<AddCredit details={queryResult({ data: withState })} />);
    await userEvent.click(screen.getByRole("button", { name: "Pay ₹500.00" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Payment failed because the UPI ID is invalid. No credit was added.",
    );
    expect(api.confirmTopUp).not.toHaveBeenCalled();
  });

  it("goes back to the start when Checkout is closed without paying", async () => {
    api.orderTopUp.mockResolvedValue(topUp("pending"));
    checkout.pay.mockRejectedValue(new CheckoutClosed());
    renderWith(<AddCredit details={queryResult({ data: withState })} />);
    await userEvent.click(screen.getByRole("button", { name: "Pay ₹500.00" }));
    expect(await screen.findByText(/Paid through Razorpay/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pay ₹500.00" })).toBeEnabled();
  });

  it("shows the server's own words for an amount outside the limits, and orders again with the same key", async () => {
    api.orderTopUp
      .mockRejectedValueOnce(
        new ApiError(422, "/problems/top-up-out-of-limits", null, "The largest top-up is ₹10,000.", []),
      )
      .mockRejectedValueOnce(new TypeError("lost"));
    renderWith(<AddCredit details={queryResult({ data: withState })} />);
    await userEvent.click(screen.getByRole("button", { name: "Another amount" }));
    await userEvent.type(screen.getByLabelText("Amount, in ₹"), "20000");
    await userEvent.click(screen.getByRole("button", { name: "Pay ₹20,000.00" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The largest top-up is ₹10,000.");
    await userEvent.click(screen.getByRole("button", { name: "Pay ₹20,000.00" }));
    const [first, second] = api.orderTopUp.mock.calls;
    expect(second?.[1]).toBe(first?.[1]);
  });

  it("says so when the build takes no payments", () => {
    checkout.available = false;
    renderWith(<AddCredit details={queryResult({ data: withState })} />);
    expect(screen.getByText("Payments aren't set up on this server.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pay ₹500.00" })).toBeDisabled();
  });
});

describe("billing details", () => {
  it("saves the state, a GSTIN in capitals and a business name", async () => {
    renderWith(<BillingDetailsTile details={queryResult({ data: withoutState })} />);
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    await userEvent.selectOptions(screen.getByLabelText("Your state"), "29");
    await userEvent.type(screen.getByLabelText("GSTIN (optional)"), "29aagcb7383j1z4");
    await userEvent.type(screen.getByLabelText("Business name (optional)"), "Studio Asha");
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(api.save.mutate).toHaveBeenCalledWith(
      { stateCode: "29", gstin: "29AAGCB7383J1Z4", businessName: "Studio Asha" },
      expect.anything(),
    );
    expect(screen.getByText(/We aren't registered for GST yet/)).toBeInTheDocument();
  });

  it("has a skeleton while loading, and an error with a retry", () => {
    const { unmount } = renderWith(<BillingDetailsTile details={queryResult({ isPending: true })} />);
    expect(screen.getByLabelText("Loading your billing details")).toBeInTheDocument();
    unmount();
    renderWith(
      <BillingDetailsTile
        details={queryResult({ isError: true, error: new ApiError(500, null, null, null, []) })}
      />,
    );
    expect(screen.getByRole("button", { name: /Try again|Retry/ })).toBeInTheDocument();
  });
});

describe("refunding unused credit", () => {
  const refunds = (overrides: Partial<RefundRequests> = {}): RefundRequests => ({
    refundablePaise: 20_000,
    requests: [],
    ...overrides,
  });

  it("isn't drawn when there's nothing bought, asked for or held", () => {
    const { container } = renderWith(
      <RefundTile
        wallet={queryResult({ data: wallet })}
        refunds={queryResult({ data: refunds({ refundablePaise: 0 }) })}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("asks for an amount or all of it, with a note", async () => {
    renderWith(
      <RefundTile
        wallet={queryResult({ data: { ...wallet, boughtPaise: 20_000 } })}
        refunds={queryResult({ data: refunds() })}
      />,
    );
    expect(screen.getByText(/₹200.00 of the credit you bought can go back/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Ask for a refund" }));
    await userEvent.type(screen.getByLabelText("How much, in ₹"), "250");
    expect(screen.getByText("At most ₹200.00.")).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText("How much, in ₹"));
    await userEvent.type(screen.getByLabelText(/Anything we should know/), "I bought too much");
    await userEvent.click(screen.getByRole("button", { name: "Send request" }));
    expect(api.ask.mutate).toHaveBeenCalledWith({ note: "I bought too much" }, expect.anything());
  });

  it("says where a request stands and what's held for a disputed payment", () => {
    renderWith(
      <RefundTile
        wallet={queryResult({ data: { ...wallet, boughtPaise: 20_000, frozenPaise: 1_500 } })}
        refunds={queryResult({
          data: refunds({
            requests: [
              {
                id: "r1",
                status: "requested",
                requestedPaise: 5_000,
                requestedAt: "2026-10-20T10:00:00Z",
                ticketNumber: 42,
              },
            ],
          }),
        })}
      />,
    );
    expect(
      screen.getByText(/You asked for ₹50.00 back on .* \(message #42\). We'll answer by email./),
    ).toBeInTheDocument();
    expect(
      screen.getByText("₹15.00 is held while your bank looks at a payment you disputed."),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Ask for a refund" })).not.toBeInTheDocument();
  });
});

describe("invoices and receipts", () => {
  const receipt: Invoice = {
    id: "i1",
    number: "IDL2627-000001",
    kind: "receipt",
    title: "Receipt",
    issuedAt: "2026-10-23T10:00:05Z",
    taxablePaise: 50_000,
    taxPaise: 0,
    totalPaise: 50_000,
    topUpId: "t1",
    creditNoteFor: null,
  };

  it("lists each document with its number, date and total, and downloads it when asked", async () => {
    api.downloadInvoice.mockResolvedValue(undefined);
    renderWith(<Documents invoices={queryResult({ data: { invoices: [receipt] } })} />);
    const row = screen.getByText("Receipt").closest("li")!;
    expect(within(row).getByText("IDL2627-000001")).toBeInTheDocument();
    expect(within(row).getByText("₹500.00")).toBeInTheDocument();
    await userEvent.click(within(row).getByRole("button", { name: "Download receipt IDL2627-000001" }));
    expect(api.downloadInvoice).toHaveBeenCalledWith(receipt);
  });

  it("says what will be here when there's nothing yet, and has a skeleton while loading", () => {
    const { unmount } = renderWith(<Documents invoices={queryResult({ data: { invoices: [] } })} />);
    expect(screen.getByText(/its receipt is here to download/)).toBeInTheDocument();
    unmount();
    renderWith(<Documents invoices={queryResult({ isPending: true })} />);
    expect(screen.getByLabelText("Loading your invoices")).toBeInTheDocument();
  });
});
