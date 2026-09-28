import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import ar from "../../../../../../messages/ar.json";
import type { LeaseDetail } from "@/lib/api/leasing";

/**
 * Break-it R2 silent-mutation sweep: the leases list's Documents modal ignored a
 * refused upload (P1: the renter's paper was never attached and nothing said so)
 * and a refused delete (P2). Each now says so, EN/AR; an upload names the file,
 * and a refused delete keeps the row.
 */

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("../LeaseWizard", () => ({ default: () => null }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));

const LEASE = {
    id: "l1", unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
    startDate: "2026-01-01", endDate: "2026-12-31", status: "ACTIVE", rentAmount: null, depositAmount: null,
    ejariNumber: null, paymentTerms: 4, installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE",
    depositPaymentMethod: "CHEQUE", paymentReferenceNumber: null, propertyId: "p1", propertyName: "L'Olivier",
    propertyCode: "OLV", hasContract: false, contractNumber: null, displayContractNumber: null, agreementDate: null,
    rentVatApplicable: true, contractDate: "2026-01-01", totalDays: 365, gracePeriodDays: 0, firstDueDate: null,
    renterAcceptedAt: null, renewedFromLeaseId: null, chainId: "chain-aaaaaaaa", receivableAccountId: null,
    incomeAccountId: null, postingJournalId: null, postedAt: null, contractValue: 60000,
    terminatedOn: null, terminationJournalId: null, terminationNotes: null, lines: [],
} as unknown as LeaseDetail;
const DOC = { id: "a1", leaseId: "l1", name: "Ejari scan", fileUrl: "x", fileType: "pdf", fileSize: 2048, uploadedAt: "2026-01-02" };

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        leaseApi: { ...m.leaseApi, paged: async () => ({ content: [LEASE], totalElements: 1, totalPages: 1, number: 0, size: 25 }) },
        chequeApi: { ...m.chequeApi, statsByLeases: async () => [] },
    };
});

import LeasesPage from "../page";

let upload: () => Response;
let remove: () => Response;

beforeEach(() => {
    window.history.replaceState(null, "", "/");
    global.fetch = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
        const href = String(url);
        if (href.startsWith("/api/upload")) return upload();
        if (href.includes("/leases/attachments/") && init?.method === "DELETE") return remove();
        if (href.endsWith("/leases/l1/attachments")) return { ok: true, status: 200, json: async () => [DOC] } as Response;
        return { ok: true, status: 200, json: async () => [] } as Response;
    }) as unknown as typeof fetch;
});
afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

async function openDocs(locale: "en" | "ar") {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <LeasesPage />
        </NextIntlClientProvider>,
    );
    const items = await screen.findAllByTestId(/lease-(action|card)-docs-l1/);
    fireEvent.click(items[0]);
    await screen.findByText("Ejari scan");
}

describe("leases list — Documents modal says when a change is refused", () => {
    it("a refused upload names the file (P1)", async () => {
        upload = () => ({ ok: false, status: 413, json: async () => ({}) }) as Response;
        await openDocs("en");
        fireEvent.change(screen.getByPlaceholderText(en.MasterData.documentNamePlaceholder), { target: { value: "Passport" } });
        const input = document.querySelector('input[type="file"]') as HTMLInputElement;
        fireEvent.change(input, { target: { files: [new File(["x"], "passport.pdf", { type: "application/pdf" })] } });
        expect((await screen.findByTestId("leases-doc-error")).textContent)
            .toBe(en.MasterData.documentUploadFailed.replace("{name}", "passport.pdf"));
    });

    it("a refused delete says so in Arabic and keeps the row (P2)", async () => {
        remove = () => ({ ok: false, status: 403, json: async () => ({}) }) as Response;
        await openDocs("ar");
        fireEvent.click(screen.getByText(ar.MasterData.delete, { selector: "button" }));
        expect((await screen.findByTestId("leases-doc-error")).textContent).toBe(ar.MasterData.documentDeleteFailed);
        await waitFor(() => expect(screen.getByText("Ejari scan")).toBeInTheDocument());
    });
});
