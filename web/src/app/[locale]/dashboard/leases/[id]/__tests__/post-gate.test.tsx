import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { Cheque, LeaseDetail, PostLeaseDryRunResponse } from "@/lib/api/leasing";

/**
 * Posting is the act that turns a contract into journals, so the button is
 * gated twice over: by the role, and by the server's own dry run.
 *
 * A PROPERTY_MANAGER may run a building and work the cheque register but may
 * not post — LeaseController's `POST /{id}/post` is
 * hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT') — so they must not see
 * the button at all. They see instead what the state actually is: saved as a
 * draft, waiting on an accountant.
 */

const push = vi.fn();
let role = "ACCOUNTANT";

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "lease-1" }),
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role } } }) }));
vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));
vi.mock("@/components/leases/LeaseInteractionsPanel", () => ({ default: () => null }));

const DRAFT: LeaseDetail = {
    id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
    startDate: "2026-01-01", endDate: "2026-12-31", status: "DRAFT",
    rentAmount: null, depositAmount: null, ejariNumber: null, paymentTerms: 4,
    installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
    paymentReferenceNumber: null, propertyId: "p1", propertyName: "L'Olivier", propertyCode: "OLV",
    hasContract: false, contractNumber: null, displayContractNumber: "TCO-26/15",
    agreementDate: null, rentVatApplicable: true, contractDate: "2026-01-01", totalDays: 365,
    gracePeriodDays: 5, firstDueDate: "2026-01-01", renterAcceptedAt: null,
    renewedFromLeaseId: null, chainId: "chain-1", receivableAccountId: null, incomeAccountId: null,
    postingJournalId: null, postedAt: null, contractValue: 60000,
    lines: [{
        id: "ln1", seqNo: 1, chargeTypeId: "ct-rent", chargeTypeCode: "RENT", chargeTypeName: "Rent",
        behaviour: "RENT", creditAccountId: "acc-1", creditAccountCode: "210100", creditAccountName: "Advance Rent",
        grossAmount: 60000, discountAmount: 0, netAmount: 60000, narration: null,
        vatApplicable: true, periodStart: null, periodEnd: null,
    }],
};

const CHEQUES: Cheque[] = [];


// `vi.mock`'s factory is hoisted above every const in this file, so the spies
// it closes over have to be hoisted with it.
const api = vi.hoisted(() => ({
    dryRun: null as unknown as PostLeaseDryRunResponse,
    dryRunPost: vi.fn(),
    post: vi.fn(),
    get: vi.fn(),
    cheques: vi.fn(),
}));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: { ...m.chargeTypeApi, list: vi.fn(async () => []) },
        penaltyApi: { ...m.penaltyApi, list: vi.fn(async () => ({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 })) },
        leaseApi: {
            ...m.leaseApi,
            get: api.get,
            cheques: api.cheques,
            dryRunPost: api.dryRunPost,
            post: api.post,
        },
    };
});

import LeaseDetailPage from "../page";

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseDetailPage />
        </NextIntlClientProvider>,
    );
}

function setDryRun(next: PostLeaseDryRunResponse) {
    api.dryRun = next;
}

beforeEach(() => {
    role = "ACCOUNTANT";
    setDryRun({
        ok: true, errors: [], contractValue: 60000, contractValueInclVat: 63000,
        chequeTotal: 63000, depositCarriedForward: 0, journals: { tco: 1, tcoLines: 2, pdr: 4 },
    });
    api.get.mockImplementation(async () => DRAFT);
    api.cheques.mockImplementation(async () => CHEQUES);
    api.dryRunPost.mockImplementation(async () => api.dryRun);
    api.post.mockImplementation(async () => ({
        lease: { ...DRAFT, status: "ACTIVE" as const }, tcoJournalId: "j1",
        tcoEntryNumber: "TCO-26/15", cheques: [],
    }));
    push.mockClear();
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(cleanup);

describe("Post gate on a draft contract", () => {
    it("shows Post to an ACCOUNTANT", async () => {
        renderPage();
        expect(await screen.findByTestId("lease-post")).toBeInTheDocument();
        expect(screen.queryByTestId("lease-needs-accountant")).not.toBeInTheDocument();
    });

    it("hides Post from a PROPERTY_MANAGER and says who has to do it", async () => {
        role = "PROPERTY_MANAGER";
        renderPage();
        expect(await screen.findByTestId("lease-needs-accountant")).toHaveTextContent(
            "Saved as a draft — an accountant must post it.",
        );
        expect(screen.queryByTestId("lease-post")).not.toBeInTheDocument();
    });

    it("keeps the dialog's Post disabled until the server's dry run comes back clean", async () => {
        const failing: PostLeaseDryRunResponse = {
            ok: false,
            errors: [
                "Line 2 (ADMIN_FEE): credit account 400100 is inactive.",
                "Cheques total 27,400.00 but contract value is 63,000.00.",
                "Cannot post on 2026-01-01: books are locked through 2026-03-31.",
            ],
            contractValue: 60000, contractValueInclVat: 63000, chequeTotal: 27400,
            depositCarriedForward: 0, journals: { tco: 1, tcoLines: 2, pdr: 2 },
        };
        setDryRun(failing);
        renderPage();
        fireEvent.click(await screen.findByTestId("lease-post"));

        await waitFor(() => expect(screen.getByTestId("post-dry-run-errors")).toBeInTheDocument());
        expect(screen.getByTestId("post-lease-confirm")).toBeDisabled();

        // Every error, not just the first — otherwise one round of corrections
        // becomes three.
        const shown = screen.getAllByRole("listitem").map(li => li.textContent);
        for (const e of failing.errors) expect(shown).toContain(e);

        fireEvent.click(screen.getByTestId("post-lease-confirm"));
        expect(api.post).not.toHaveBeenCalled();
    });

    it("enables Post and calls the endpoint once the dry run says ok", async () => {
        renderPage();
        fireEvent.click(await screen.findByTestId("lease-post"));

        await waitFor(() => expect(screen.getByTestId("post-dry-run-ok")).toBeInTheDocument());
        expect(screen.getByTestId("post-journals")).toHaveTextContent("1 TCO with 2 lines, 4 PDR");
        expect(screen.getByTestId("post-cheque-total")).toHaveTextContent("63,000.00");

        const confirm = screen.getByTestId("post-lease-confirm");
        expect(confirm).toBeEnabled();
        fireEvent.click(confirm);
        await waitFor(() => expect(api.post).toHaveBeenCalledWith("lease-1"));
        await waitFor(() => expect(screen.getByTestId("lease-banner")).toHaveTextContent("Posted as TCO-26/15"));
    });
});
