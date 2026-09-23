import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
    useLocale: () => "en",
}));

import BankAccountsPage from "../page";

/** Shaped like the serialized BankAccount entity (see BankAccountJsonTest). */
const sampleAccounts = [
    {
        id: "ba-1",
        bankName: "Emirates NBD",
        accountNumber: "1234567890",
        iban: "AE070331234567890123456",
        branchName: "Deira",
        currency: "AED",
        property: { id: "p1", nameEn: "Belle Vue", nameAr: "بيل فيو" },
        coaAccount: null,
        // The backend's @JsonProperty("isDefault") accessors emit this key —
        // not the `default` Lombok/Jackson would otherwise derive.
        isDefault: true,
        active: true,
    },
];

/** The chart of accounts as /finance/accounts returns it: groups and leaves of every subtype. */
const chartOfAccounts = [
    { id: "g-bank", code: "A-02-02", name: "Bank", accountType: "ASSET", accountSubType: "BANK", group: true },
    { id: "l-bank", code: "100005", name: "Bank - Miftah Residences", accountType: "ASSET", accountSubType: "BANK", group: false },
    { id: "l-rr", code: "100001", name: "Rent Receivable - Miftah Residences", accountType: "ASSET", accountSubType: "RECEIVABLE", group: false },
    { id: "l-pdc", code: "100004", name: "PDC Receivable - Miftah Residences", accountType: "ASSET", accountSubType: "PDC_RECEIVABLE", group: false },
    { id: "l-cash", code: "A-02-05-001", name: "Cash Account", accountType: "ASSET", accountSubType: "CASH", group: false },
    { id: "l-bank-old", code: "100009", name: "Bank - Closed Branch", accountType: "ASSET", accountSubType: "BANK", group: false, active: false },
];

type FetchCall = { url: string; init?: RequestInit };
let calls: FetchCall[] = [];
let failNextWrite: { status: number; message: string } | null = null;
let bankAccounts: unknown[] = sampleAccounts;

function stubFetch() {
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        calls.push({ url, init });
        const method = init?.method ?? "GET";
        if (method !== "GET" && failNextWrite) {
            const { status, message } = failNextWrite;
            return {
                ok: false,
                status,
                json: async () => ({ error: "Internal Server Error", message, status }),
            } as unknown as Response;
        }
        if (url.includes("/v1/finance/accounts")) {
            return { ok: true, json: async () => chartOfAccounts } as unknown as Response;
        }
        if (url.includes("/v1/bank-accounts")) {
            return { ok: true, json: async () => bankAccounts } as unknown as Response;
        }
        return { ok: true, json: async () => [] } as unknown as Response;
    }) as unknown as typeof fetch;
}

beforeEach(() => {
    calls = [];
    failNextWrite = null;
    bankAccounts = sampleAccounts;
    stubFetch();
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("BankAccountsPage", () => {
    it("renders the Default badge from the backend's isDefault property", async () => {
        render(<BankAccountsPage />);

        const cell = await screen.findByText("Emirates NBD");
        const row = cell.closest("tr");
        expect(row).not.toBeNull();
        // One "isDefault" in the table header, one badge inside the row.
        expect(within(row as HTMLElement).getByText("isDefault")).toBeTruthy();
    });

    it("surfaces the backend message on a failed save, keeps the modal open, and sends no notes field", async () => {
        render(<BankAccountsPage />);
        await screen.findByText("Emirates NBD");

        fireEvent.click(screen.getByRole("button", { name: "addAccount" }));
        const form = document.querySelector("form");
        expect(form).not.toBeNull();

        failNextWrite = { status: 500, message: "Bank account not found" };
        fireEvent.submit(form as HTMLFormElement);

        expect(await screen.findByText("Bank account not found")).toBeTruthy();
        // Modal must stay open so the user can correct and retry.
        expect(document.querySelector("form")).not.toBeNull();

        const post = calls.find((c) => c.init?.method === "POST");
        expect(post).toBeTruthy();
        const body = JSON.parse(String(post?.init?.body));
        expect(Object.keys(body)).not.toContain("notes");
        expect(body.isDefault).toBe(false);
    });

    it("shows a page-level error when a delete is rejected", async () => {
        render(<BankAccountsPage />);
        await screen.findByText("Emirates NBD");

        fireEvent.click(screen.getByLabelText("deleteAccount"));
        failNextWrite = { status: 500, message: "Cannot delete bank account" };
        fireEvent.click(screen.getByRole("button", { name: "delete" }));

        expect(await screen.findByText("Cannot delete bank account")).toBeTruthy();
        expect(screen.getByRole("alert").textContent).toContain(
            "Cannot delete bank account",
        );
    });

    it("offers only BANK-subtype leaves in the ledger account picker (gap #66)", async () => {
        render(<BankAccountsPage />);
        await screen.findByText("Emirates NBD");

        fireEvent.click(screen.getByRole("button", { name: "addAccount" }));
        const select = await screen.findByDisplayValue("selectAccount");
        // Wait for the chart of accounts to arrive before reading the options.
        await within(select as HTMLElement).findByText(/100005/);
        const values = Array.from((select as HTMLSelectElement).options).map((o) => o.value);
        expect(values).toEqual(["", "l-bank"]);
    });

    it("marks a legacy non-bank link on edit and re-sends it unchanged when only the IBAN changes (I-2)", async () => {
        // Linked under the old ASSET-wide picker to the PDC receivable.
        bankAccounts = [{
            ...sampleAccounts[0],
            coaAccount: { id: "l-pdc", code: "100004", name: "PDC Receivable - Miftah Residences", accountType: "ASSET", accountSubType: "PDC_RECEIVABLE", group: false },
        }];
        render(<BankAccountsPage />);
        await screen.findByText("Emirates NBD");

        fireEvent.click(screen.getByLabelText("editAccount"));
        const legacy = await screen.findByTestId("bank-account-legacy-link");
        expect(legacy.textContent).toContain("100004");
        expect(legacy.textContent).toContain("legacyLinkSuffix");
        await within(legacy.parentElement as HTMLElement).findByText(/100005/);
        const select = legacy.parentElement as HTMLSelectElement;
        expect(select.value).toBe("l-pdc");
        expect(screen.getByTestId("bank-account-legacy-link-hint").textContent).toBe("legacyLinkHint");

        fireEvent.change(screen.getByDisplayValue("AE070331234567890123456"), { target: { value: "AE070331234567890999999" } });
        fireEvent.submit(document.querySelector("form") as HTMLFormElement);
        await screen.findByText("Emirates NBD");
        const put = calls.find((c) => c.init?.method === "PUT");
        expect(put).toBeTruthy();
        const body = JSON.parse(String(put?.init?.body));
        expect(body.coaAccount).toEqual({ id: "l-pdc" });
        expect(body.iban).toBe("AE070331234567890999999");

        // Picking a real bank leaf drops the warning.
        fireEvent.click(screen.getByLabelText("editAccount"));
        const again = (await screen.findByTestId("bank-account-legacy-link")).parentElement as HTMLSelectElement;
        fireEvent.change(again, { target: { value: "l-bank" } });
        expect(screen.queryByTestId("bank-account-legacy-link-hint")).toBeNull();
    });
});
