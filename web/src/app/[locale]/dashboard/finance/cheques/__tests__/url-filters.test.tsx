import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import ChequeRegisterPage from "../page";
import type { Cheque } from "@/lib/api/leasing";
import type { Page } from "@/lib/api/ledger";

/**
 * #85: `/finance/cheques?status=BOUNCED` opened the whole register. The page now
 * reads its initial filters from the query and writes them back on Apply.
 */

let query = new URLSearchParams();
vi.mock("next/navigation", () => ({
    useSearchParams: () => query,
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN", name: "Admin" } } }),
}));
vi.mock("@/components/cheques/UnappliedPaymentsTile", () => ({ default: () => null }));
vi.mock("@/components/finance/useNameLookup", () => ({
    useNameLookup: () => ({ options: [], byId: new Map() }),
}));

const list = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chequeApi: {
            ...m.chequeApi,
            list: (...a: unknown[]) => list(...(a as [])),
            summary: async () => null,
            aging: async () => null,
        },
    };
});

const empty: Page<Cheque> = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 };

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    list.mockReset();
    list.mockResolvedValue(empty);
    window.history.replaceState(null, "", "/en/dashboard/finance/cheques");
});

afterEach(() => {
    cleanup();
    query = new URLSearchParams();
});

const renderPage = () => render(
    <NextIntlClientProvider locale="en" messages={en}>
        <ChequeRegisterPage />
    </NextIntlClientProvider>,
);

describe("Cheque register — filters in the URL", () => {
    it("opens filtered by ?status=BOUNCED", async () => {
        query = new URLSearchParams("status=BOUNCED&mode=PDC");
        renderPage();
        await waitFor(() => expect(list).toHaveBeenCalled());
        expect(list.mock.calls[0][0]).toEqual(expect.objectContaining({ status: "BOUNCED", mode: "PDC" }));
        expect((screen.getByTestId("cheque-status-filter") as HTMLSelectElement).value).toBe("BOUNCED");
    });

    it("writes the applied filters back into the URL", async () => {
        window.history.replaceState(null, "", "/en/dashboard/finance/cheques?leaseId=l1");
        renderPage();
        await waitFor(() => expect(list).toHaveBeenCalled());
        fireEvent.change(screen.getByTestId("cheque-status-filter"), { target: { value: "DEPOSITED" } });
        fireEvent.click(screen.getByTestId("cheque-filter-apply"));

        await waitFor(() => expect(window.location.search).toContain("status=DEPOSITED"));
        expect(window.location.search).toContain("leaseId=l1");
        expect(window.location.pathname).toBe("/en/dashboard/finance/cheques");
        await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ status: "DEPOSITED" })));
    });
});
