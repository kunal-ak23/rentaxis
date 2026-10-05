import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import NewJournalPage from "../new/page";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("@/lib/api/ledger", async (orig) => {
  const m = await orig<typeof import("@/lib/api/ledger")>();
  return { ...m, ledgerApi: { ...m.ledgerApi,
    // The pickers read `pickable` (the chart a role may pick from); for finance roles it is the whole chart.
    accounts: { pickable: vi.fn(async () => [
      { id: "b", code: "100001", name: "ENBD Main", accountType: "ASSET", group: false, active: true },
      { id: "c", code: "F-01", name: "Capital Account", accountType: "EQUITY", group: false, active: true } ]) },
    journals: { postManual: vi.fn(async () => ({ id: "j1", entryNumber: "JV-26/1", lines: [] })) } } };
});

describe("New journal voucher", () => {
  it("keeps Post disabled until debits equal credits", async () => {
    render(<NextIntlClientProvider locale="en" messages={en}><NewJournalPage /></NextIntlClientProvider>);
    const post = await screen.findByRole("button", { name: "Post" });
    expect(post).toBeDisabled();
    const debits = screen.getAllByLabelText("Debit");
    const credits = screen.getAllByLabelText("Credit");
    fireEvent.change(debits[0], { target: { value: "5000" } });
    fireEvent.change(credits[1], { target: { value: "4000" } });
    expect(screen.getByText("Debits and credits must be equal")).toBeInTheDocument();
    fireEvent.change(credits[1], { target: { value: "5000" } });
    await waitFor(() => expect(screen.queryByText("Debits and credits must be equal")).not.toBeInTheDocument());
    // still disabled: accounts not chosen
    expect(post).toBeDisabled();
  });
});

/**
 * Break-it round 1 (money): F1/F3 amounts go through the shared money parse (the
 * browser's step check stopped 3 decimals, but "1e12" and overflow reached the
 * server as a "conflict"); F4 a JV dated 2126 collided with 2026's numbers.
 */
describe("New journal voucher — money guards", () => {
  beforeEach(cleanup);
  afterEach(cleanup);
  function renderIt() {
    render(<NextIntlClientProvider locale="en" messages={en}><NewJournalPage /></NextIntlClientProvider>);
  }

  it("says why an amount is refused, and reads grouping exactly", async () => {
    renderIt();
    await screen.findByRole("button", { name: "Post" });
    const debits = screen.getAllByLabelText("Debit");
    const credits = screen.getAllByLabelText("Credit");
    fireEvent.change(debits[0], { target: { value: "100.004" } });
    expect(screen.getByTestId("money-input-error")).toHaveTextContent(en.MoneyInput.decimals);
    fireEvent.change(debits[0], { target: { value: "1e12" } });
    expect(screen.getByTestId("money-input-error")).toHaveTextContent(en.MoneyInput.format);
    fireEvent.change(debits[0], { target: { value: "1000000000000" } });
    expect(screen.getByTestId("money-input-error")).toHaveTextContent(en.MoneyInput.max);
    fireEvent.change(debits[0], { target: { value: "1,000" } });
    fireEvent.change(credits[1], { target: { value: "1000" } });
    expect(screen.queryByTestId("money-input-error")).toBeNull();
    expect(screen.queryByText("Debits and credits must be equal")).not.toBeInTheDocument();
  });

  it("refuses a date more than a year ahead", async () => {
    renderIt();
    await screen.findByRole("button", { name: "Post" });
    const date = screen.getByLabelText(en.Ledger.docDate) as HTMLInputElement;
    expect(date.max).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    fireEvent.change(date, { target: { value: "2126-09-28" } });
    // Batch 4 review #5: the limit is shown dd/mm/yyyy, like VoucherForm — not raw ISO.
    expect(screen.getByTestId("jv-date-error").textContent).toMatch(/\(\d{2}\/\d{2}\/\d{4}\)/);
  });
});
