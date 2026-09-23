import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { Cheque, LeaseDetail, LeaseStatus } from "@/lib/api/leasing";

/**
 * #12: "Raise penalty" on the lease page. Finance roles get it in the header;
 * it posts the same `POST /penalties` proposal the rule engine's rows sit
 * beside, with the category, amount, date of the incident and narration, and
 * nothing is charged until finance approves it.
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

const api = vi.hoisted(() => ({
    get: vi.fn(),
    cheques: vi.fn(),
    notice: vi.fn(),
    settlement: vi.fn(),
    propose: vi.fn(),
}));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: { ...m.chargeTypeApi, list: vi.fn(async () => []) },
        penaltyApi: {
            ...m.penaltyApi,
            list: vi.fn(async () => ({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 })),
            propose: api.propose,
        },
        leaseApi: { ...m.leaseApi, get: api.get, cheques: api.cheques },
        terminationApi: { ...m.terminationApi, notice: api.notice },
        settlementApi: { ...m.settlementApi, get: api.settlement },
    };
});

import LeaseDetailPage from "../page";

function lease(status: LeaseStatus): LeaseDetail {
    return {
        id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
        startDate: "2026-01-01", endDate: "2026-12-31", status,
        rentAmount: 60000, depositAmount: null, ejariNumber: null, paymentTerms: 4,
        installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
        paymentReferenceNumber: null, propertyId: "p1", propertyName: "L'Olivier", propertyCode: "OLV",
        hasContract: false, contractNumber: null, displayContractNumber: "TCO-26/15",
        agreementDate: null, rentVatApplicable: true, contractDate: "2026-01-01", totalDays: 365,
        gracePeriodDays: 5, firstDueDate: "2026-01-01", renterAcceptedAt: null,
        renewedFromLeaseId: null, chainId: "chain-1", receivableAccountId: null, incomeAccountId: null,
        postingJournalId: "j-tco", postedAt: "2026-01-01T00:00:00Z", contractValue: 60000,
        terminatedOn: null, terminationJournalId: null, terminationNotes: null,
        lines: [],
    };
}

const CHEQUES: Cheque[] = [];

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseDetailPage />
        </NextIntlClientProvider>,
    );
}

function onLease(status: LeaseStatus) {
    api.get.mockImplementation(async () => lease(status));
}

beforeEach(() => {
    role = "ACCOUNTANT";
    onLease("ACTIVE");
    api.cheques.mockImplementation(async () => CHEQUES);
    api.settlement.mockRejectedValue(new Error("no settlement"));
    api.notice.mockImplementation(async () => lease("NOTICE_GIVEN"));
    push.mockClear();
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Raise penalty", () => {
    it("lets an accountant raise a dated, narrated penalty through the proposal endpoint", async () => {
        api.propose.mockImplementation(async (b: unknown) => ({ id: "pen-9", ...(b as object), status: "PROPOSED" }));
        renderPage();

        fireEvent.click(await screen.findByTestId("lease-raise-penalty"));
        const confirm = await screen.findByTestId("raise-penalty-confirm");
        expect(confirm).toBeDisabled();

        fireEvent.change(screen.getByLabelText(en.Leasing.amount), { target: { value: "750" } });
        fireEvent.blur(screen.getByLabelText(en.Leasing.amount));
        fireEvent.change(screen.getByLabelText(en.Leasing.penaltyIncidentDate), { target: { value: "2026-09-01" } });
        fireEvent.change(screen.getByLabelText(en.Leasing.narration), { target: { value: "Damaged lobby door" } });
        await waitFor(() => expect(screen.getByTestId("raise-penalty-confirm")).not.toBeDisabled());
        fireEvent.click(screen.getByTestId("raise-penalty-confirm"));

        await waitFor(() => expect(api.propose).toHaveBeenCalledWith({
            leaseId: "lease-1", reason: "OTHER", amount: 750, incidentDate: "2026-09-01",
            description: "Damaged lobby door",
        }));
        expect(await screen.findByText(en.Leasing.penaltyRaisedBanner)).toBeInTheDocument();
    });

    it("refuses a date in the future", async () => {
        renderPage();

        fireEvent.click(await screen.findByTestId("lease-raise-penalty"));
        fireEvent.change(screen.getByLabelText(en.Leasing.amount), { target: { value: "100" } });
        fireEvent.blur(screen.getByLabelText(en.Leasing.amount));
        fireEvent.change(screen.getByLabelText(en.Leasing.penaltyIncidentDate), { target: { value: "2999-01-01" } });

        expect(screen.getByTestId("raise-penalty-confirm")).toBeDisabled();
        expect(api.propose).not.toHaveBeenCalled();
    });

    // Web review M5: nothing before the contract date (2026-01-01 here). Enabled
    // on the contract date first, so the disabled state below is the floor's doing.
    it("refuses a date before the contract", async () => {
        renderPage();

        fireEvent.click(await screen.findByTestId("lease-raise-penalty"));
        fireEvent.change(screen.getByLabelText(en.Leasing.amount), { target: { value: "100" } });
        fireEvent.blur(screen.getByLabelText(en.Leasing.amount));
        const date = screen.getByLabelText(en.Leasing.penaltyIncidentDate);
        expect(date).toHaveAttribute("min", "2026-01-01");
        fireEvent.change(date, { target: { value: "2026-01-01" } });
        await waitFor(() => expect(screen.getByTestId("raise-penalty-confirm")).not.toBeDisabled());

        fireEvent.change(date, { target: { value: "1990-01-01" } });
        expect(screen.getByTestId("raise-penalty-confirm")).toBeDisabled();
    });

    it("is finance's: not in a property manager's header", async () => {
        role = "PROPERTY_MANAGER";
        renderPage();

        await screen.findByTestId("lease-actions");
        expect(screen.queryByTestId("lease-raise-penalty")).not.toBeInTheDocument();
    });

    it("is not offered on a terminated contract", async () => {
        onLease("TERMINATED");
        renderPage();

        await screen.findByTestId("lease-actions");
        expect(screen.queryByTestId("lease-raise-penalty")).not.toBeInTheDocument();
    });
});
