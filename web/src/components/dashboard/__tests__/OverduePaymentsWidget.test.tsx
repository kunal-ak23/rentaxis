import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("@/i18n/routing", () => ({
  Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>{children}</a>
  ),
}));

import OverduePaymentsWidget from "../OverduePaymentsWidget";

function mockFetch(body: unknown) {
  global.fetch = vi.fn(async () => ({ ok: true, json: async () => body })) as unknown as typeof fetch;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("OverduePaymentsWidget", () => {
  it("lists overdue payments and links to the filtered payments page", async () => {
    mockFetch({
      content: [
        { id: "p1", leaseId: "l1", renterName: "Jane Tenant", unitIdentifier: "A-101", amount: 5000, dueDate: "2026-05-01" },
      ],
      totalElements: 3,
    });

    render(<OverduePaymentsWidget />);

    await waitFor(() => expect(screen.getByText(/Jane Tenant/)).toBeTruthy());
    // header shows the total count, not just the rendered page size
    expect(screen.getByText(/Overdue payments \(3\)/)).toBeTruthy();
    // row links to the lease
    const rowLink = screen.getByText(/Jane Tenant/).closest("a");
    expect(rowLink).toHaveAttribute("href", "/dashboard/leases/l1");
    // "View all" links to the overdue-filtered payments page
    expect(screen.getByRole("link", { name: /view all/i }))
      .toHaveAttribute("href", "/dashboard/finance/payments?status=OVERDUE");
  });

  it("shows an empty state when there are no overdue payments", async () => {
    mockFetch({ content: [], totalElements: 0 });
    render(<OverduePaymentsWidget />);
    await waitFor(() => expect(screen.getByText(/no overdue payments/i)).toBeTruthy());
    expect(screen.queryByRole("link", { name: /view all/i })).toBeNull();
  });
});
