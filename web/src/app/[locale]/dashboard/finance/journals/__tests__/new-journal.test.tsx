import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import NewJournalPage from "../new/page";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("@/lib/api/ledger", async (orig) => {
  const m = await orig<typeof import("@/lib/api/ledger")>();
  return { ...m, ledgerApi: { ...m.ledgerApi,
    accounts: { list: vi.fn(async () => [
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
