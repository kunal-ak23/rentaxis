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

type FetchCall = { url: string; init?: RequestInit };
let calls: FetchCall[] = [];
let failNextWrite: { status: number; message: string } | null = null;

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
        if (url.includes("/v1/bank-accounts")) {
            return { ok: true, json: async () => sampleAccounts } as unknown as Response;
        }
        return { ok: true, json: async () => [] } as unknown as Response;
    }) as unknown as typeof fetch;
}

beforeEach(() => {
    calls = [];
    failNextWrite = null;
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
        fireEvent.click(screen.getByRole("button", { name: "Delete" }));

        expect(await screen.findByText("Cannot delete bank account")).toBeTruthy();
        expect(screen.getByRole("alert").textContent).toContain(
            "Cannot delete bank account",
        );
    });
});
