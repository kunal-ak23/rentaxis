import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";

/**
 * F14-55: the "Ledger accounts" dialog must never offer a leaf another bank
 * account already owns — each ledger account belongs to exactly one bank
 * account (BankRec.leavesHint says so), so offering one already claimed
 * elsewhere invites assigning it twice.
 */

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
vi.mock("@/components/finance/AccountPicker", () => ({
    loadAccounts: async () => [
        { id: "leaf-1", code: "A-02-02-001", name: "Emirates Islamic - Marina Tower", accountType: "ASSET", accountSubType: "BANK", group: false, active: true },
        { id: "leaf-2", code: "A-02-02-002", name: "Emirates Islamic - Palm Residence", accountType: "ASSET", accountSubType: "BANK", group: false, active: true },
    ],
}));

import BankReconciliationPage from "../page";

let calls: { method: string; url: string; body: unknown }[];

function stubFetch(routes: { match: string; method?: string; body: unknown }[]) {
    calls = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
        const u = String(url);
        const method = init?.method ?? "GET";
        calls.push({ method, url: u, body: init?.body ? JSON.parse(String(init.body)) : undefined });
        const r = routes.find(x => u.includes(x.match) && (!x.method || x.method === method));
        return new Response(JSON.stringify(r ? r.body : []), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
}

function renderIn(ui: React.ReactElement) {
    return render(<NextIntlClientProvider locale="en" messages={en}>{ui}</NextIntlClientProvider>);
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const ROWS = [
    { id: "ba-1", bankName: "Emirates Islamic - Marina", accountNumber: "0123", iban: null, currency: "AED", bankTrn: null,
      active: true, leaves: [{ id: "leaf-1", code: "A-02-02-001", name: "Emirates Islamic - Marina Tower", propertyId: null }],
      needsLeaf: false, lastImportAt: null, lastImportFile: null, lastLineDate: null, unmatchedLines: 0,
      hasProfile: true, reconciledThrough: null, recStartDate: null, draftReconciliationId: null, latestFinalizedReconciliationId: null },
    { id: "ba-2", bankName: "Emirates Islamic - Palm", accountNumber: "0456", iban: null, currency: "AED", bankTrn: null,
      active: true, leaves: [], needsLeaf: true, lastImportAt: null, lastImportFile: null, lastLineDate: null, unmatchedLines: 0,
      hasProfile: false, reconciledThrough: null, recStartDate: null, draftReconciliationId: null, latestFinalizedReconciliationId: null },
];

describe("the ledger-accounts dialog", () => {
    it("disables a leaf already owned by another bank account, and names whose it is", async () => {
        stubFetch([{ match: "/bank-accounts", body: ROWS }]);
        renderIn(<BankReconciliationPage />);
        fireEvent.click(await screen.findByTestId("edit-leaves-0456"));
        await screen.findByTestId("leaves-dialog");

        const owned = screen.getByTestId("leaf-A-02-02-001") as HTMLInputElement;
        expect(owned.disabled).toBe(true);
        expect(screen.getByTestId("leaf-owned-A-02-02-001")).toHaveTextContent("owned by Emirates Islamic - Marina");

        const free = screen.getByTestId("leaf-A-02-02-002") as HTMLInputElement;
        expect(free.disabled).toBe(false);
    });

    it("does not disable a leaf this same bank account already owns", async () => {
        stubFetch([{ match: "/bank-accounts", body: ROWS }]);
        renderIn(<BankReconciliationPage />);
        fireEvent.click(await screen.findByTestId("edit-leaves-0123"));
        await screen.findByTestId("leaves-dialog");

        const own = screen.getByTestId("leaf-A-02-02-001") as HTMLInputElement;
        expect(own.disabled).toBe(false);
        expect(own.checked).toBe(true);
        expect(screen.queryByTestId("leaf-owned-A-02-02-001")).not.toBeInTheDocument();
    });
});
