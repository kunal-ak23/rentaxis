import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import ar from "../../../../../../../messages/ar.json";
import en from "../../../../../../../messages/en.json";

/**
 * Finance → Payables, PR 3b (finance-ops spec §2): the payment-run wizard
 * (select → save and preview → post), a posted run, and the issued-cheques
 * register (present / cancel / unpresent, the tie-out, cut-over cheques).
 * The figures follow the spec's worked example: run PR-26/4 pays INV-7781 in
 * full and 600 of INV-7790; PDC 000031 for 20,000 is outstanding.
 */

const session = vi.hoisted(() => ({ role: "ACCOUNTANT" }));
const nav = vi.hoisted(() => ({ push: vi.fn(), id: "run-1" }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: session.role } } }) }));
vi.mock("next/navigation", () => ({ useParams: () => ({ id: nav.id }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: nav.push }),
}));
vi.mock("@/components/finance/AccountPicker", () => ({
    loadAccounts: async () => [
        { id: "bank-1", code: "A-02-02-001", name: "Emirates Islamic - Marina Tower", accountType: "ASSET", accountSubType: "BANK", group: false, active: true },
        { id: "cash-1", code: "A-02-05-001", name: "Cash Account", accountType: "ASSET", accountSubType: "CASH", group: false, active: true },
    ],
}));

import { PaymentRunWizard } from "@/components/finance/PaymentRunWizard";
import PaymentRunsPage from "../payment-runs/page";
import PaymentRunPage from "../payment-runs/[id]/page";
import IssuedChequesPage from "../issued-cheques/page";

const item = (id: string, invoiceNumber: string, open: number, dueDate: string, vendorId = "gulf", vendorName = "Gulf AC Services") => ({
    kind: "PISR", id, vendorId, vendorName, docNumber: `PISR-${id}`, invoiceNumber, docDate: "2026-08-01",
    invoiceDate: "2026-08-01", dueDate, daysOverdue: 10, bucket: "D1_30", gross: open, allocated: 0, open,
    status: "OPEN", propertyId: null,
});

const CANDIDATES = {
    items: [
        { item: item("i81", "INV-7781", 1450, "2026-08-31"), draftRuns: [] },
        { item: item("i90", "INV-7790", 2100, "2026-09-19"), draftRuns: ["PR-26/3"] },
        { item: item("an", "AN-311", 3150, "2026-10-05", "noor", "Al Noor Cleaning"), draftRuns: [] },
    ],
    advances: [{ vendorId: "noor", vendorName: "Al Noor Cleaning", unallocated: 500 }],
};

const RUN = {
    referenceWarnings: [] as string[],
    id: "run-1", runNumber: "PR-26/4", paymentDate: "2026-09-10", paymentAccountId: "bank-1",
    paymentAccountCode: "A-02-02-001", paymentAccountName: "Emirates Islamic - Marina Tower", method: "TRANSFER",
    chequeDate: null, firstChequeNumber: null, narration: null, status: "DRAFT", createdAt: "2026-09-10T08:00:00Z",
    postedAt: null, total: 2050, vendorCount: 1,
    items: [
        { id: "it1", vendorId: "gulf", vendorName: "Gulf AC Services", kind: "PISR", invoiceId: "i81", openingItemId: null,
          docNumber: "PISR-26/20", invoiceNumber: "INV-7781", dueDate: "2026-08-31", amount: 1450, applyAdvance: true,
          bpvId: null, bpvNumber: null, bpvStatus: null, chequeNumber: null, bpvAmount: null },
        { id: "it2", vendorId: "gulf", vendorName: "Gulf AC Services", kind: "PISR", invoiceId: "i90", openingItemId: null,
          docNumber: "PISR-26/21", invoiceNumber: "INV-7790", dueDate: "2026-09-19", amount: 600, applyAdvance: true,
          bpvId: null, bpvNumber: null, bpvStatus: null, chequeNumber: null, bpvAmount: null },
    ],
};

const PREVIEW = {
    runId: "run-1", runNumber: "PR-26/4", status: "DRAFT", paymentDate: "2026-09-10", method: "TRANSFER", postable: true,
    problems: [{ code: "NO_IBAN", severity: "WARNING", vendorId: "gulf", message: "x", params: { vendor: "Gulf AC Services" } }],
    vendors: [{
        vendorId: "gulf", vendorName: "Gulf AC Services", iban: null, bankName: null,
        items: [
            { itemId: "it1", kind: "PISR", docNumber: "PISR-26/20", invoiceNumber: "INV-7781", dueDate: "2026-08-31", amount: 1450, openNow: 1450, advanceApplied: 0, paid: 1450 },
            { itemId: "it2", kind: "PISR", docNumber: "PISR-26/21", invoiceNumber: "INV-7790", dueDate: "2026-09-19", amount: 600, openNow: 2100, advanceApplied: 0, paid: 600 },
        ],
        itemsTotal: 2050, advanceApplied: 0, netPayment: 2050, chequeNumber: null, chequeDate: null, postDated: false,
        journal: [
            { accountCode: "B-01-04-001", accountName: "Gulf AC Services", debit: 2050, credit: 0 },
            { accountCode: "A-02-02-001", accountName: "Emirates Islamic - Marina Tower", debit: 0, credit: 2050 },
        ],
    }],
    itemsTotal: 2050, advanceApplied: 0, netPayment: 2050,
};

type Route = { method?: string; match: string; body: unknown; status?: number };
let calls: { method: string; url: string; body: unknown }[];

function stubFetch(routes: Route[]) {
    calls = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        const method = init?.method ?? "GET";
        calls.push({ method, url: u, body: init?.body ? JSON.parse(String(init.body)) : undefined });
        const r = routes.find(x => u.includes(x.match) && (!x.method || x.method === method));
        const status = r?.status ?? 200;
        return new Response(JSON.stringify(r ? r.body : []), { status, headers: { "Content-Type": "application/json" } });
    }));
}

function renderIn(locale: "en" | "ar", ui: React.ReactElement) {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <div dir={locale === "ar" ? "rtl" : "ltr"}>{ui}</div>
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    session.role = "ACCOUNTANT";
    nav.push.mockReset();
    nav.id = "run-1";
});
afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
});

describe("payment-run wizard", () => {
    beforeEach(() => stubFetch([
        { match: "/payment-runs/candidates", body: CANDIDATES },
        { method: "POST", match: "/payment-runs/run-1/post", body: { ...RUN, status: "POSTED" } },
        { method: "POST", match: "/payment-runs", body: RUN },
        { match: "/payment-runs/run-1/preview", body: PREVIEW },
        { match: "/properties", body: [] },
    ]));

    it("selects invoices across vendors, saves, previews and only then posts", async () => {
        renderIn("en", <PaymentRunWizard />);
        // A draft run already holding INV-7790 is flagged, not blocked.
        expect(await screen.findByTestId("run-held-INV-7790")).toHaveTextContent("Also in draft PR-26/3");
        expect(screen.getByTestId("run-blocker")).toHaveTextContent(en.PaymentRuns.selectSomething);

        fireEvent.click(screen.getByTestId("run-pick-INV-7781"));
        fireEvent.click(screen.getByTestId("run-pick-INV-7790"));
        // The amount defaults to the open amount; a partial payment is typed over it.
        expect(screen.getByTestId("run-amount-INV-7781")).toHaveValue("1450.00");
        fireEvent.change(screen.getByTestId("run-amount-INV-7790"), { target: { value: "600" } });
        expect(screen.getByTestId("run-selected-total")).toHaveTextContent("2 invoices selected, 2,050.00");
        expect(screen.getByTestId("run-blocker")).toHaveTextContent(en.PaymentRuns.chooseAccount);
        await waitFor(() => expect(screen.getByTestId("run-account").querySelectorAll("option").length).toBe(2));
        fireEvent.change(screen.getByTestId("run-account"), { target: { value: "bank-1" } });
        fireEvent.change(screen.getByTestId("run-payment-date"), { target: { value: "2026-09-10" } });

        fireEvent.click(screen.getByTestId("run-save-preview"));
        await screen.findByTestId("run-preview");
        const created = calls.find(c => c.method === "POST" && c.url.endsWith("/payment-runs"))!;
        expect(created.body).toEqual({
            paymentDate: "2026-09-10", paymentAccountId: "bank-1", method: "TRANSFER", chequeDate: null,
            firstChequeNumber: null, narration: null,
            items: [{ invoiceId: "i81", amount: 1450, applyAdvance: true }, { invoiceId: "i90", amount: 600, applyAdvance: true }],
        });
        // Nothing is posted by saving or previewing.
        expect(calls.some(c => c.url.includes("/post"))).toBe(false);

        expect(screen.getByTestId("run-net-Gulf AC Services")).toHaveTextContent("2,050.00");
        expect(screen.getByTestId("problem-NO_IBAN")).toHaveTextContent("Gulf AC Services has no IBAN on file");
        expect(screen.getByTestId("run-journal-Gulf AC Services")).toHaveTextContent("Emirates Islamic - Marina Tower");

        fireEvent.click(screen.getByTestId("run-post"));
        fireEvent.click(await screen.findByTestId("run-confirm-post"));
        await waitFor(() => expect(nav.push).toHaveBeenCalledWith("/dashboard/finance/payables/payment-runs/run-1"));
        const post = calls.filter(c => c.method === "POST" && c.url.endsWith("/payment-runs/run-1/post"));
        expect(post).toHaveLength(1);
        // It sends what the preview showed, for the server to hold it to (review P2-1).
        expect(post[0].body).toEqual({ vendors: [{ vendorId: "gulf", netPayment: 2050, advanceApplied: 0, chequeNumber: null,
            items: [{ itemId: "it1", paid: 1450 }, { itemId: "it2", paid: 600 }] }] });
    });

    it("on a 409 says the run changed, shows the server's diff and the fresh preview, and posts nothing", async () => {
        let previews = 0;
        stubFetch([
            { match: "/payment-runs/candidates", body: CANDIDATES },
            { method: "POST", match: "/payment-runs/run-1/post", status: 409,
              body: { error: true, message: "The run changed since the preview; review it again. Gulf AC Services: net payment 2,050.00 → 2,550.00", status: 409 } },
            { method: "POST", match: "/payment-runs", body: RUN },
            { match: "/properties", body: [] },
        ]);
        const base = (globalThis.fetch as unknown as ReturnType<typeof vi.fn>).getMockImplementation() as
            (url: string, init?: RequestInit) => Promise<Response>;
        vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
            if (String(url).includes("/preview")) {
                previews++;
                calls.push({ method: "GET", url: String(url), body: undefined });
                const body = previews === 1 ? PREVIEW : { ...PREVIEW, netPayment: 2550,
                    vendors: [{ ...PREVIEW.vendors[0], netPayment: 2550 }] };
                return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
            }
            return base(url, init);
        }));
        renderIn("en", <PaymentRunWizard />);
        fireEvent.click(await screen.findByTestId("run-pick-INV-7781"));
        await waitFor(() => expect(screen.getByTestId("run-account").querySelectorAll("option").length).toBe(2));
        fireEvent.change(screen.getByTestId("run-account"), { target: { value: "bank-1" } });
        fireEvent.click(screen.getByTestId("run-save-preview"));
        await screen.findByTestId("run-preview");
        fireEvent.click(screen.getByTestId("run-post"));
        fireEvent.click(await screen.findByTestId("run-confirm-post"));
        expect(await screen.findByTestId("run-error")).toHaveTextContent(en.PaymentRuns.runChanged);
        expect(screen.getByTestId("run-error")).toHaveTextContent("net payment 2,050.00 → 2,550.00");
        await waitFor(() => expect(screen.getByTestId("run-net-total")).toHaveTextContent("2,550.00"));
        expect(nav.push).not.toHaveBeenCalled();
    });

    it("refuses an amount above what the invoice has open", async () => {
        renderIn("en", <PaymentRunWizard />);
        fireEvent.click(await screen.findByTestId("run-pick-INV-7781"));
        fireEvent.change(screen.getByTestId("run-amount-INV-7781"), { target: { value: "1500" } });
        expect(screen.getByTestId("run-blocker")).toHaveTextContent(en.PaymentRuns.amountTooHigh);
        expect(screen.getByTestId("run-save-preview")).toBeDisabled();
    });

    it("offers a vendor's advance, on by default, and sends the choice", async () => {
        renderIn("en", <PaymentRunWizard />);
        fireEvent.click(await screen.findByTestId("run-pick-AN-311"));
        const advance = screen.getByTestId("run-advance-Al Noor Cleaning");
        expect(advance).toBeChecked();
        expect(advance.closest("label")).toHaveTextContent("apply the advance of 500.00 first");
        fireEvent.click(advance);
        await waitFor(() => expect(screen.getByTestId("run-account").querySelectorAll("option").length).toBe(2));
        fireEvent.change(screen.getByTestId("run-account"), { target: { value: "bank-1" } });
        fireEvent.click(screen.getByTestId("run-save-preview"));
        await screen.findByTestId("run-preview");
        const created = calls.find(c => c.method === "POST" && c.url.endsWith("/payment-runs"))!;
        expect((created.body as { items: { applyAdvance: boolean }[] }).items[0].applyAdvance).toBe(false);
    });

    it("for cheques needs a first number ending in digits, and says a later-dated cheque is held in PDC payable", async () => {
        renderIn("en", <PaymentRunWizard />);
        fireEvent.click(await screen.findByTestId("run-pick-INV-7781"));
        fireEvent.change(screen.getByTestId("run-method"), { target: { value: "CHEQUE" } });
        await waitFor(() => expect(screen.getByTestId("run-account").querySelectorAll("option").length).toBe(2));
        fireEvent.change(screen.getByTestId("run-account"), { target: { value: "bank-1" } });
        fireEvent.change(screen.getByTestId("run-payment-date"), { target: { value: "2026-09-10" } });
        fireEvent.change(screen.getByTestId("run-first-cheque"), { target: { value: "CHQ" } });
        expect(screen.getByTestId("run-blocker")).toHaveTextContent(en.PaymentRuns.firstChequeDigits);
        fireEvent.change(screen.getByTestId("run-first-cheque"), { target: { value: "000031" } });
        expect(screen.queryByTestId("run-pdc-note")).not.toBeInTheDocument();
        fireEvent.change(screen.getByTestId("run-cheque-date"), { target: { value: "2026-09-28" } });
        expect(screen.getByTestId("run-pdc-note")).toHaveTextContent(en.PaymentRuns.pdcHeld);
        expect(screen.queryByTestId("run-blocker")).not.toBeInTheDocument();
    });

    it("keeps the post button off while the preview has an error, in Arabic too", async () => {
        stubFetch([
            { match: "/payment-runs/candidates", body: CANDIDATES },
            { method: "POST", match: "/payment-runs", body: RUN },
            { match: "/payment-runs/run-1/preview", body: { ...PREVIEW, postable: false, problems: [
                { code: "OPEN_CHANGED", severity: "ERROR", vendorId: "gulf", message: "x",
                  params: { vendor: "Gulf AC Services", invoice: "INV-7781", open: "1,000.00", amount: "1,450.00" } }] } },
            { match: "/properties", body: [] },
        ]);
        renderIn("ar", <PaymentRunWizard />);
        fireEvent.click(await screen.findByTestId("run-pick-INV-7781"));
        await waitFor(() => expect(screen.getByTestId("run-account").querySelectorAll("option").length).toBe(2));
        fireEvent.change(screen.getByTestId("run-account"), { target: { value: "bank-1" } });
        fireEvent.click(screen.getByTestId("run-save-preview"));
        expect(await screen.findByTestId("problem-OPEN_CHANGED")).toHaveTextContent("المتبقي على INV-7781");
        expect(screen.getByTestId("run-post")).toBeDisabled();
    });
});

describe("payment runs list and a posted run", () => {
    it("lists runs with their status", async () => {
        stubFetch([{ match: "/payment-runs", body: [RUN, { ...RUN, id: "run-2", runNumber: "PR-26/5", status: "POSTED" }] }]);
        renderIn("en", <PaymentRunsPage />);
        const table = await screen.findByTestId("runs-table");
        await waitFor(() => expect(within(table).getByText("PR-26/5")).toBeInTheDocument());
        expect(within(table).getByText("Posted")).toBeInTheDocument();
        expect(within(table).getByText("Draft")).toBeInTheDocument();
    });

    it("refuses a property manager", () => {
        session.role = "PROPERTY_MANAGER";
        stubFetch([]);
        renderIn("en", <PaymentRunsPage />);
        expect(screen.getByText(en.PaymentRuns.accessDenied)).toBeInTheDocument();
        expect(calls).toHaveLength(0);
    });

    it("shows a posted run's vouchers, a reversed one, and the bank file", async () => {
        const posted = {
            ...RUN, status: "POSTED",
            items: RUN.items.map(i => ({ ...i, bpvId: "bpv-55", bpvNumber: "BPV-26/55", bpvStatus: "REVERSED", bpvAmount: 2050 })),
        };
        stubFetch([{ match: "/payment-runs/run-1", body: posted }]);
        renderIn("en", <PaymentRunPage />);
        expect(await screen.findByTestId("run-reversed-BPV-26/55")).toHaveTextContent("Reversed");
        expect(screen.getByTestId("run-bank-file")).toHaveAttribute("href", "/api/proxy/v1/finance/payment-runs/run-1/bank-file.csv");
        expect(screen.getByRole("link", { name: "BPV-26/55" })).toHaveAttribute("href", "/dashboard/finance/vouchers/payment?id=bpv-55");
        expect(screen.queryByTestId("run-cancel")).not.toBeInTheDocument();
    });
});

describe("issued cheques", () => {
    const CHEQUE = {
        id: "c31", voucherId: "bpv-50", voucherNumber: "BPV-26/50", vendorId: "gulf", vendorName: "Gulf AC Services",
        bankAccountId: "bank-1", bankAccountCode: "A-02-02-001", bankAccountName: "Emirates Islamic - Marina Tower",
        chequeNumber: "000031", chequeDate: "2026-09-20", amount: 20000, status: "ISSUED", presentedOn: null,
        bpcNumber: null, cancelledOn: null, cancelReason: null, opening: false, duePresent: true,
    };
    const SUMMARY = {
        outstandingTotal: 20000, pdcPayableBalance: 20000, difference: 0,
        perBank: [{ bankAccountId: "bank-1", bankAccountCode: "A-02-02-001", bankAccountName: "Emirates Islamic - Marina Tower", count: 1, amount: 20000 }],
        openingTotal: 0, openingBalance: 0, openingDifference: 0, duePresentCount: 1,
    };
    beforeEach(() => stubFetch([
        { match: "/issued-cheques/summary", body: SUMMARY },
        { method: "POST", match: "/issued-cheques/c31/present", body: { ...CHEQUE, status: "PRESENTED" } },
        { method: "POST", match: "/issued-cheques/c31/cancel", body: { ...CHEQUE, status: "CANCELLED" } },
        { match: "/issued-cheques", body: [CHEQUE] },
        { match: "/vendors", body: [{ id: "gulf", nameEn: "Gulf AC Services", active: true }] },
    ]));

    it("shows the tie-out and the past-date flag, and presents on a date not before the cheque's", async () => {
        renderIn("en", <IssuedChequesPage />);
        expect(await screen.findByTestId("cheque-outstanding")).toHaveTextContent("20,000.00");
        expect(screen.getByTestId("cheque-difference")).toHaveTextContent("0.00");
        expect(await screen.findByTestId("cheque-due-000031")).toHaveTextContent(en.IssuedCheques.pastDate);

        fireEvent.click(screen.getByTestId("present-000031"));
        fireEvent.change(await screen.findByTestId("cheque-action-date"), { target: { value: "2026-09-19" } });
        expect(screen.getByTestId("cheque-early")).toHaveTextContent("A cheque cannot be presented before 2026-09-20");
        expect(screen.getByTestId("cheque-confirm")).toBeDisabled();
        fireEvent.change(screen.getByTestId("cheque-action-date"), { target: { value: "2026-09-20" } });
        fireEvent.click(screen.getByTestId("cheque-confirm"));
        await waitFor(() => expect(calls.some(c => c.method === "POST" && c.url.includes("/c31/present"))).toBe(true));
        expect(calls.find(c => c.url.includes("/c31/present"))!.body).toEqual({ date: "2026-09-20" });
    });

    it("needs a reason to cancel, and sends it", async () => {
        renderIn("en", <IssuedChequesPage />);
        fireEvent.click(await screen.findByTestId("cancel-000031"));
        expect(await screen.findByTestId("cheque-confirm")).toBeDisabled();
        fireEvent.change(screen.getByTestId("cheque-action-reason"), { target: { value: "stopped" } });
        fireEvent.click(screen.getByTestId("cheque-confirm"));
        await waitFor(() => expect(calls.some(c => c.url.includes("/c31/cancel"))).toBe(true));
        expect(calls.find(c => c.url.includes("/c31/cancel"))!.body).toMatchObject({ reason: "stopped" });
    });

    it("renders in Arabic", async () => {
        renderIn("ar", <IssuedChequesPage />);
        expect(await screen.findByText(ar.IssuedCheques.title)).toBeInTheDocument();
        expect(await screen.findByTestId("present-000031")).toHaveTextContent(ar.IssuedCheques.present);
    });
});

describe("editing and posting a draft from its page (review P3-3, P3-4)", () => {
    it("lists a held item due after the default filter, and shows the run as posted after posting", async () => {
        const late = { ...RUN, items: [{ ...RUN.items[0], invoiceId: "late", invoiceNumber: "INV-LATE", dueDate: "2099-01-31" }] };
        let posted = false;
        stubFetch([]);
        vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
            const u = String(url);
            const method = init?.method ?? "GET";
            calls.push({ method, url: u, body: init?.body ? JSON.parse(String(init.body)) : undefined });
            const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { "Content-Type": "application/json" } });
            if (u.includes("/candidates")) {
                // The draft's item is listed only when the due filter reaches it.
                const due = new URL(u, "http://x").searchParams.get("dueBefore") ?? "";
                return json({ items: due >= "2099-01-31" ? [{ item: item("late", "INV-LATE", 1450, "2099-01-31"), draftRuns: [] }] : [], advances: [] });
            }
            if (u.includes("/preview")) return json(PREVIEW);
            if (u.endsWith("/post")) { posted = true; return json({ ...late, status: "POSTED" }); }
            if (method === "PUT") return json(late);
            if (u.includes("/payment-runs/run-1")) return json(posted ? { ...late, status: "POSTED",
                items: late.items.map(i => ({ ...i, bpvId: "b1", bpvNumber: "BPV-26/60", bpvStatus: "POSTED", bpvAmount: 1450 })) } : late);
            return json([]);
        }));
        renderIn("en", <PaymentRunPage />);
        expect(await screen.findByTestId("run-amount-INV-LATE")).toHaveValue("1450.00");
        expect(screen.queryByTestId("run-missing")).not.toBeInTheDocument();
        fireEvent.click(screen.getByTestId("run-save-preview"));
        await screen.findByTestId("run-preview");
        fireEvent.click(screen.getByTestId("run-post"));
        fireEvent.click(await screen.findByTestId("run-confirm-post"));
        expect(await screen.findByRole("link", { name: "BPV-26/60" })).toBeInTheDocument();
        expect(screen.queryByTestId("run-post")).not.toBeInTheDocument();
    });

    it("says when a selected item is no longer open, and blocks until it is dropped", async () => {
        stubFetch([
            { match: "/payment-runs/candidates", body: { items: [], advances: [] } },
            { match: "/properties", body: [] },
        ]);
        renderIn("en", <PaymentRunWizard run={RUN as never} />);
        expect(await screen.findByTestId("run-missing-gone")).toHaveTextContent("2 selected invoices are no longer open");
        expect(screen.queryByTestId("run-missing-hidden")).not.toBeInTheDocument();
        expect(screen.getByTestId("run-save-preview")).toBeDisabled();
        fireEvent.click(screen.getByTestId("run-drop-missing"));
        expect(screen.queryByTestId("run-missing")).not.toBeInTheDocument();
        expect(screen.getByTestId("run-blocker")).toHaveTextContent(en.PaymentRuns.selectSomething);
    });

    it("tells a selection hidden by the filters from one no longer open, and drops only the latter (re-review R3)", async () => {
        calls = [];
        vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
            const u = String(url);
            calls.push({ method: init?.method ?? "GET", url: u });
            const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { "Content-Type": "application/json" } });
            if (u.includes("/candidates")) {
                // Filtered by due date: nothing. Unfiltered: INV-7790 is still open; INV-7781 was paid elsewhere.
                const filtered = new URL(u, "http://x").searchParams.has("dueBefore");
                return json({ items: filtered ? [] : [{ item: item("i90", "INV-7790", 600, "2026-09-19"), draftRuns: [] }], advances: [] });
            }
            return json([]);
        }));
        renderIn("en", <PaymentRunWizard run={RUN as never} />);
        expect(await screen.findByTestId("run-missing-gone")).toHaveTextContent("1 selected invoice is no longer open");
        expect(screen.getByTestId("run-missing-hidden")).toHaveTextContent("1 selected invoice is still open but hidden");
        fireEvent.click(screen.getByTestId("run-drop-missing"));
        expect(screen.queryByTestId("run-missing-gone")).not.toBeInTheDocument();
        // INV-7790 is still selected, and clearing the filters brings it back.
        expect(screen.getByTestId("run-missing-hidden")).toBeInTheDocument();
        fireEvent.click(screen.getByTestId("run-widen-filters"));
        expect(await screen.findByTestId("run-amount-INV-7790")).toHaveValue("600.00");
        expect(screen.queryByTestId("run-missing")).not.toBeInTheDocument();
    });

    it("lists the bank references the file shortens, and offers a copy for Excel", async () => {
        stubFetch([{ match: "/payment-runs/run-1", body: { ...RUN, status: "POSTED",
            referenceWarnings: ["PR-26/4/BPV-26/55-VERY-LONG-REFERENCE-X → PR-26/4/BPV-26/55-VERY-LONG-REFERE"] } }]);
        renderIn("en", <PaymentRunPage />);
        expect(await screen.findByTestId("run-reference-warnings")).toHaveTextContent("up to 35 characters");
        expect(screen.getByTestId("run-bank-file-excel")).toHaveAttribute("href",
            "/api/proxy/v1/finance/payment-runs/run-1/bank-file.csv?bom=true");
    });
});
