// src/components/collections/__tests__/embedded.test.tsx
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";

const api = vi.hoisted(() => ({ toDeposit: vi.fn(), list: vi.fn(), bounced: vi.fn(), postDated: vi.fn(), penalties: vi.fn() }));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams("receive=1&leaseId=l1") }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock("@/components/cheques/ReceiveCashDialog", () => ({ default: ({ open }: { open: boolean }) => (open ? <div data-testid="cash-dialog-open" /> : null) }));
vi.mock("@/components/cheques/UnappliedPaymentsTile", () => ({ default: () => null }));
vi.mock("@/components/finance/useNameLookup", () => ({ useNameLookup: () => ({ options: [{ id: "p1", label: "Belle Vue" }], name: () => "", loading: false }) }));
vi.mock("@/lib/api/bankRec", () => ({ bankRecApi: { chequeEvidence: vi.fn().mockResolvedValue([]) } }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    const page = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 };
    return { ...m,
        chequeApi: { ...m.chequeApi, toDeposit: api.toDeposit.mockResolvedValue(page), list: api.list.mockResolvedValue(page),
            postDated: api.postDated.mockResolvedValue([]),
            summary: vi.fn().mockResolvedValue({}), aging: vi.fn().mockResolvedValue({ buckets: [], totalCount: 0 }) },
        penaltyApi: { ...m.penaltyApi, list: api.penalties.mockResolvedValue(page) } };
});
import ToDepositPanel from "../ToDepositPanel";
import ReturnReplacePanel from "../ReturnReplacePanel";
import PostDatedPanel from "../PostDatedPanel";
import PenaltiesPanel from "../PenaltiesPanel";
import ChequeRegisterPanel from "../ChequeRegisterPanel";

const wrap = (ui: React.ReactNode) => render(<NextIntlClientProvider locale="en" messages={en}>{ui}</NextIntlClientProvider>);
beforeEach(() => { global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch; });
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("embedded collection panels", () => {
    it("drop the page h1 for an h2 and hide their own property picker when the hub passes one", async () => {
        wrap(<ToDepositPanel embedded propertyId="p9" />);
        await waitFor(() => expect(api.toDeposit).toHaveBeenCalledWith(expect.objectContaining({ propertyId: "p9" })));
        expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
        expect(screen.getByRole("heading", { level: 2 })).toBeInTheDocument();
        expect(screen.queryByTestId("collection-property-filter")).toBeNull();
    });

    it("keep their own picker and h1 as standalone pages", async () => {
        wrap(<ToDepositPanel />);
        expect(await screen.findByTestId("collection-property-filter")).toBeInTheDocument();
        expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
    });

    it.each([
        ["returned", () => <ReturnReplacePanel embedded propertyId="p9" />, "return-replace-property-filter"],
        ["post-dated", () => <PostDatedPanel embedded propertyId="p9" />, "post-dated-property-filter"],
    ])("%s: the hub's property drives the query and the panel's own picker is hidden", async (_id, ui, picker) => {
        wrap(ui());
        await waitFor(() => expect(api.list.mock.calls.length + api.postDated.mock.calls.length).toBeGreaterThan(0));
        const call = (api.list.mock.calls[0] ?? api.postDated.mock.calls[0])[0];
        expect(call).toEqual(expect.objectContaining({ propertyId: "p9" }));
        expect(screen.queryByTestId(picker)).toBeNull();
        expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
        expect(screen.queryByText(en.Cheques.register, { selector: "a" })).toBeNull();
    });

    it("penalties: an h2 and the hub's property on the queue", async () => {
        wrap(<PenaltiesPanel embedded propertyId="p9" />);
        await waitFor(() => expect(api.penalties).toHaveBeenCalledWith(expect.objectContaining({ propertyId: "p9" })));
        expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
        expect(screen.getByRole("heading", { level: 2, name: en.Cheques.penalties })).toBeInTheDocument();
    });

    it("hide the cross-links to the other cheque pages (they are the hub's tabs)", () => {
        const { container } = wrap(<ChequeRegisterPanel embedded />);
        expect(container.querySelector('a[href="/dashboard/finance/cheques/collection"]')).toBeNull();
        expect(container.querySelector('a[href="/dashboard/finance/cheques/return-replace"]')).toBeNull();
    });

    it("keep the cross-links on the standalone register", () => {
        const { container } = wrap(<ChequeRegisterPanel />);
        expect(container.querySelector('a[href="/dashboard/finance/cheques/collection"]')).not.toBeNull();
    });

    it("open the cash receipt straight away for ?receive=1", async () => {
        wrap(<ChequeRegisterPanel embedded />);
        expect(await screen.findByTestId("cash-dialog-open")).toBeInTheDocument();
    });
});
