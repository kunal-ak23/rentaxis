import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
    useLocale: () => "en",
}));

import AccountsPage from "../page";

/** Shaped like the serialized Account entity returned by AccountController. */
const sampleAccounts = [
    {
        id: "acc-1",
        code: "D-99",
        name: "Landscaping",
        nameEn: "Landscaping",
        nameAr: "تنسيق الحدائق",
        accountType: "EXPENSE",
        accountSubType: "DIRECT_EXPENSE",
        parentCode: null,
        description: null,
        system: false,
        group: false,
        // Deliberately non-default values: the PUT body must carry them through
        // or AccountService.updateAccount resets them to true/0.
        active: false,
        hierarchyLevel: 1,
        displayOrder: 7,
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
                json: async () => ({ error: true, message, status }),
            } as unknown as Response;
        }
        if (url.includes("/v1/finance/accounts")) {
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

describe("AccountsPage", () => {
    it("surfaces the backend message on a failed create and keeps the modal open", async () => {
        render(<AccountsPage />);
        await screen.findByText("Landscaping");

        fireEvent.click(screen.getByRole("button", { name: "addAccount" }));
        const form = document.querySelector("form");
        expect(form).not.toBeNull();

        failNextWrite = { status: 400, message: "Validation failed: code: must not be blank" };
        fireEvent.submit(form as HTMLFormElement);

        expect(
            await screen.findByText("Validation failed: code: must not be blank"),
        ).toBeTruthy();
        // Modal must stay open so the user can correct and retry.
        expect(document.querySelector("form")).not.toBeNull();
    });

    it("shows a page-level alert when a delete is rejected", async () => {
        render(<AccountsPage />);
        await screen.findByText("Landscaping");

        fireEvent.click(screen.getByTitle("deleteAccount"));
        const confirmCopy = await screen.findByText("confirmDeleteAccount");
        const dialog = confirmCopy.closest("div") as HTMLElement;

        failNextWrite = { status: 400, message: "Cannot delete account with child accounts" };
        fireEvent.click(within(dialog).getByRole("button", { name: "deleteAccount" }));

        expect(
            await screen.findByText("Cannot delete account with child accounts"),
        ).toBeTruthy();
        expect(screen.getByRole("alert").textContent).toContain(
            "Cannot delete account with child accounts",
        );
    });

    it("disables immutable fields in the edit form and sends only fields the backend applies", async () => {
        render(<AccountsPage />);
        await screen.findByText("Landscaping");

        fireEvent.click(screen.getByTitle("editAccount"));
        const form = document.querySelector("form");
        expect(form).not.toBeNull();

        // The backend ignores code/accountType/parentCode/group on update, so
        // the edit form must not offer them as editable.
        const codeInput = screen.getByDisplayValue("D-99") as HTMLInputElement;
        expect(codeInput.disabled).toBe(true);

        fireEvent.submit(form as HTMLFormElement);

        const put = calls.find((c) => c.init?.method === "PUT");
        expect(put).toBeTruthy();
        const body = JSON.parse(String(put?.init?.body));
        expect(Object.keys(body)).not.toContain("code");
        expect(Object.keys(body)).not.toContain("accountType");
        expect(Object.keys(body)).not.toContain("parentCode");
        expect(Object.keys(body)).not.toContain("group");
        // Passthrough so updateAccount doesn't reset them to defaults.
        expect(body.active).toBe(false);
        expect(body.displayOrder).toBe(7);
        expect(body.nameEn).toBe("Landscaping");
    });
});
