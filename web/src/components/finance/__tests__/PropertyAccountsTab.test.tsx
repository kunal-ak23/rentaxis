import { cleanup, render, screen, waitFor, fireEvent } from "@testing-library/react";
import { afterEach, vi, describe, it, expect } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import PropertyAccountsTab from "../PropertyAccountsTab";

const mappings = [
  { role: "RENT_RECEIVABLE", accountId: "a1", accountCode: "100001", accountName: "Rent Receivable - Tulip 7", inherited: false },
  { role: "ADVANCE_RENT", accountId: null, accountCode: null, accountName: null, inherited: false },
  { role: "CASH", accountId: "c1", accountCode: "A-02-05-001", accountName: "Cash Account", inherited: true },
];

vi.mock("@/lib/api/ledger", () => ({
  ledgerApi: {
    propertyAccounts: { get: vi.fn(async () => mappings), generate: vi.fn(async () => mappings), set: vi.fn(), clear: vi.fn() },
    accounts: { list: vi.fn(async () => []) },
  },
}));

const wrap = (ui: React.ReactNode) => <NextIntlClientProvider locale="en" messages={en}>{ui}</NextIntlClientProvider>;

// Auto-cleanup is off (vitest runs without globals), so each render must be
// torn down or the next test sees both trees.
afterEach(cleanup);

describe("PropertyAccountsTab", () => {
  it("renders one row per role with mapped, unmapped and inherited states", async () => {
    render(wrap(<PropertyAccountsTab propertyId="p1" />));
    await waitFor(() => expect(screen.getByText("Rent Receivable - Tulip 7")).toBeInTheDocument());
    expect(screen.getByText("Not mapped")).toBeInTheDocument();
    expect(screen.getByText("Default")).toBeInTheDocument();
  });
  it("calls generate when the button is pressed", async () => {
    const { ledgerApi } = await import("@/lib/api/ledger");
    render(wrap(<PropertyAccountsTab propertyId="p1" />));
    await waitFor(() => screen.getByText("Generate missing accounts"));
    fireEvent.click(screen.getByText("Generate missing accounts"));
    await waitFor(() => expect(ledgerApi.propertyAccounts.generate).toHaveBeenCalledWith("p1"));
  });
});
