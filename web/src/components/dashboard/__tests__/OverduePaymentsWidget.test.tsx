import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";

vi.mock("@/i18n/routing", () => ({
  Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>{children}</a>
  ),
}));

const due = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
  const m = await orig<typeof import("@/lib/api/leasing")>();
  return { ...m, chequeApi: { ...m.chequeApi, due: (...a: unknown[]) => due(...(a as [])) } };
});

import OverduePaymentsWidget from "../OverduePaymentsWidget";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("OverduePaymentsWidget", () => {
  it("keeps only the overdue rows from the due list and links to the register", async () => {
    due.mockResolvedValue({
      content: [
        { id: "p1", leaseId: "l1", renterName: "Jane Tenant", unitIdentifier: "A-101", amount: 5000, overdue: true },
        // Due today but not yet overdue — must be excluded from the widget.
        { id: "p2", leaseId: "l2", renterName: "Not Overdue", unitIdentifier: "A-102", amount: 1000, overdue: false },
        { id: "p3", leaseId: "l3", renterName: "Second Overdue", unitIdentifier: "A-103", amount: 2000, overdue: true },
      ],
    });

    render(
            <NextIntlClientProvider locale="en" messages={en}>
                <OverduePaymentsWidget />
            </NextIntlClientProvider>,
        );

    await waitFor(() => expect(screen.getByText(/Jane Tenant/)).toBeTruthy());
    // header shows only the truly-overdue count (2), not every due row (3)
    expect(screen.getByText(/Overdue payments \(2\)/)).toBeTruthy();
    expect(screen.queryByText(/Not Overdue/)).toBeNull();
    // row links to the lease
    const rowLink = screen.getByText(/Jane Tenant/).closest("a");
    expect(rowLink).toHaveAttribute("href", "/dashboard/leases/l1");
    // "View all" links to the cheque register
    expect(screen.getByRole("link", { name: /view all/i }))
      .toHaveAttribute("href", "/dashboard/finance/cheques");
  });

  it("shows an empty state when there are no overdue payments", async () => {
    due.mockResolvedValue({ content: [] });
    render(
            <NextIntlClientProvider locale="en" messages={en}>
                <OverduePaymentsWidget />
            </NextIntlClientProvider>,
        );
    await waitFor(() => expect(screen.getByText(/no overdue payments/i)).toBeTruthy());
    expect(screen.queryByRole("link", { name: /view all/i })).toBeNull();
  });
});
