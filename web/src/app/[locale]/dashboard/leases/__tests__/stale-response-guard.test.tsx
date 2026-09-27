import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { LeaseDetail, LeaseStatus } from "@/lib/api/leasing";

/**
 * #105 N1: the Contract list's "multi" read (Draft, Ended — several statuses
 * read back to back: a totals round, then a paged round per status) had no
 * request-sequence guard, unlike the "single"/"bounded" reads and unlike the
 * Units/Tickets lists after PR #373's R1. A slow multi-round response landing
 * after a faster, newer selection's response must never overwrite it.
 */

const push = vi.fn();
let role = "TENANT_ADMIN";

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role } } }) }));
vi.mock("../LeaseWizard", () => ({ default: () => null }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => <div data-testid="pagination" /> }));
vi.mock("@/lib/businessDate", () => ({ businessTodayIso: () => "2026-09-25" }));

const api = vi.hoisted(() => ({ paged: vi.fn(), statsByLeases: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, paged: api.paged }, chequeApi: { ...m.chequeApi, statsByLeases: api.statsByLeases } };
});

import LeasesPage from "../page";

function lease(id: string, status: LeaseStatus, unit: string): LeaseDetail {
    return {
        id, unitId: `u-${id}`, renterId: `r-${id}`, unitIdentifier: unit, renterName: `Renter ${unit}`,
        startDate: "2026-01-01", endDate: "2026-12-31", status,
        rentAmount: 60000, depositAmount: null, ejariNumber: null, paymentTerms: 4,
        installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
        paymentReferenceNumber: null, propertyId: "p1", propertyName: "L'Olivier", propertyCode: "OLV",
        hasContract: false, contractNumber: null, displayContractNumber: null,
        agreementDate: null, rentVatApplicable: true, contractDate: "2026-01-01", totalDays: 365,
        gracePeriodDays: 0, firstDueDate: null, renterAcceptedAt: null,
        renewedFromLeaseId: null, chainId: "chain-a", receivableAccountId: null,
        incomeAccountId: null, postingJournalId: null, postedAt: "2026-01-01T00:00:00Z",
        contractValue: 60000, terminatedOn: null, terminationJournalId: null, terminationNotes: null,
        lines: [],
    };
}

const ACTIVE_ROW = lease("l-active", "ACTIVE", "A-999");

function renderPage() {
    return render(<NextIntlClientProvider locale="en" messages={en}><LeasesPage /></NextIntlClientProvider>);
}

beforeEach(() => {
    window.history.replaceState(null, "", "/en/dashboard/leases");
    role = "TENANT_ADMIN";
    api.statsByLeases.mockImplementation(async () => []);
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Contract list — #105 N1: the multi-round (Draft/Ended) read is stale-guarded too", () => {
    it("never lets a slow Ended (multi-status) read overwrite a faster, newer Active selection", async () => {
        // Ended's per-status totals round hangs; Active's single read resolves
        // immediately. Without a sequence guard, Ended's totals (and the paged
        // reads that follow them) land after Active's and overwrite the screen.
        let releaseEnded: () => void = () => {};
        const endedGate = new Promise<void>(r => { releaseEnded = r; });
        api.paged.mockImplementation(async (q: { status?: string; size?: number } = {}) => {
            if (["TERMINATED", "EXPIRED", "RENEWED", "CLOSED"].includes(q.status ?? "")) {
                await endedGate;
                return { content: q.status === "TERMINATED" ? [lease("l-term", "TERMINATED", "Z-1")] : [], totalElements: q.status === "TERMINATED" ? 1 : 0, totalPages: 1, number: 0, size: q.size ?? 25 };
            }
            if (q.status === "ACTIVE") {
                return { content: [ACTIVE_ROW], totalElements: 1, totalPages: 1, number: 0, size: q.size ?? 25 };
            }
            return { content: [], totalElements: 0, totalPages: 1, number: 0, size: q.size ?? 25 };
        });

        renderPage();
        await waitFor(() => expect(screen.getByTestId("contract-pill-all")).toBeInTheDocument());

        fireEvent.click(screen.getByTestId("contract-pill-ended")); // starts the slow multi-round read
        await act(async () => { await Promise.resolve(); });
        fireEvent.click(screen.getByTestId("contract-pill-active")); // faster, newer single-status read

        await screen.findByTestId("lease-row-l-active");

        // Now let Ended's stale, slow read land.
        releaseEnded();
        await act(async () => { await new Promise(r => setTimeout(r, 20)); });

        expect(screen.getByTestId("lease-row-l-active")).toBeInTheDocument();
        expect(screen.queryByTestId("lease-row-l-term")).toBeNull();
        expect(screen.getByTestId("contract-pill-active")).toHaveAttribute("aria-pressed", "true");
    });
});
