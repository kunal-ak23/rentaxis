import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";

vi.mock("@/i18n/routing", () => ({
  Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>{children}</a>
  ),
}));

const toDeposit = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
  const m = await orig<typeof import("@/lib/api/leasing")>();
  return { ...m, chequeApi: { ...m.chequeApi, toDeposit: (...a: unknown[]) => toDeposit(...(a as [])) } };
});

import ChequesToDepositWidget from "../ChequesToDepositWidget";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("ChequesToDepositWidget", () => {
  it("lists cheques due for deposit, from the collection endpoint", async () => {
    toDeposit.mockResolvedValue({
      content: [
        { id: "p1", leaseId: "l1", renterName: "Omar R", unitIdentifier: "B-204", amount: 7000, chequeDate: "2026-06-04", chequeNumber: "100123" },
      ],
      totalElements: 1,
    });

    render(
            <NextIntlClientProvider locale="en" messages={en}>
                <ChequesToDepositWidget />
            </NextIntlClientProvider>,
        );

    await waitFor(() => expect(screen.getByText(/Omar R/)).toBeTruthy());
    expect(screen.getByText(/Cheques to deposit \(1\)/)).toBeTruthy();
    expect(screen.getByText(/#100123/)).toBeTruthy();
    expect(screen.getByText(/Omar R/).closest("a")).toHaveAttribute("href", "/dashboard/leases/l1");
    expect(toDeposit).toHaveBeenCalledWith({ page: 0, size: 5 });
    expect(screen.getByRole("link", { name: /view all/i })).toHaveAttribute("href", "/dashboard/finance/cheques/collection");
  });

  it("shows an empty state when nothing is due for deposit", async () => {
    toDeposit.mockResolvedValue({ content: [], totalElements: 0 });
    render(
            <NextIntlClientProvider locale="en" messages={en}>
                <ChequesToDepositWidget />
            </NextIntlClientProvider>,
        );
    await waitFor(() => expect(screen.getByText(/no cheques due for deposit/i)).toBeTruthy());
  });
});
