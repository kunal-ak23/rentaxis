import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", () => ({
    useTranslations: () => Object.assign((key: string) => key, { has: () => false }),
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
        alias: null,
        accountType: "EXPENSE",
        accountSubType: "DIRECT_EXPENSE",
        // v2 files the tree on parentId; parentCode no longer exists on the entity.
        parentId: null,
        propertyId: null,
        description: null,
        system: false,
        group: false,
        // Deliberately non-default values: the PUT body must carry them through
        // or AccountService.updateAccount resets them to true/0.
        active: false,
        displayOrder: 7,
    },
];

/**
 * A seeded chart: a group root with a leaf under it. The leaf is only on screen
 * when the tree is expanded, so it is the probe for "does an already-seeded
 * chart open closed?".
 */
const seededTree = [
    {
        id: "grp-equity",
        code: "F",
        name: "Equity",
        nameEn: "Equity",
        nameAr: "حقوق الملكية",
        alias: null,
        accountType: "EQUITY",
        accountSubType: null,
        parentId: null,
        propertyId: null,
        description: null,
        system: true,
        group: true,
        active: true,
        displayOrder: 1,
    },
    {
        id: "leaf-capital",
        code: "F-01",
        name: "Capital Account",
        nameEn: "Capital Account",
        nameAr: "رأس المال",
        alias: null,
        accountType: "EQUITY",
        accountSubType: "CAPITAL",
        parentId: "grp-equity",
        propertyId: null,
        description: null,
        system: true,
        group: false,
        active: true,
        displayOrder: 2,
    },
];

/** A root leaf: on screen whatever the tree's groups are doing. */
const suspenseAccount = {
    id: "leaf-suspense",
    code: "A-99",
    name: "Suspense Account",
    nameEn: "Suspense Account",
    nameAr: "حساب معلق",
    alias: null,
    accountType: "ASSET",
    accountSubType: "OTHER_ASSET",
    parentId: null,
    propertyId: null,
    description: null,
    system: false,
    group: false,
    active: true,
    displayOrder: 9,
};

type FetchCall = { url: string; init?: RequestInit };
let calls: FetchCall[] = [];
let failNextWrite: { status: number; message: string } | null = null;
/** What GET /finance/accounts answers — swapped per test. */
let accountsFixture: unknown[] = sampleAccounts;

/** How many times the chart has been READ (the create's POST goes to the same path). */
const accountGets = () =>
    calls.filter(c => c.url.includes("/finance/accounts") && (c.init?.method ?? "GET") === "GET").length;

function stubFetch() {
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        calls.push({ url, init });
        const method = init?.method ?? "GET";
        if (method !== "GET" && failNextWrite) {
            const { status, message } = failNextWrite;
            // ledgerApi reads the error body with text(), not json().
            return {
                ok: false,
                status,
                json: async () => ({ error: true, message, status }),
                text: async () => JSON.stringify({ error: true, message, status }),
            } as unknown as Response;
        }
        if (url.includes("/v1/finance/accounts")) {
            return {
                ok: true,
                json: async () => accountsFixture,
                text: async () => JSON.stringify(accountsFixture),
            } as unknown as Response;
        }
        return { ok: true, json: async () => [], text: async () => "[]" } as unknown as Response;
    }) as unknown as typeof fetch;
}

beforeEach(() => {
    calls = [];
    failNextWrite = null;
    accountsFixture = sampleAccounts;
    stubFetch();
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("AccountsPage", () => {
    it("opens an already-seeded chart expanded, not on five collapsed roots", async () => {
        accountsFixture = seededTree;
        render(<AccountsPage />);

        // The seed path calls expandAll; a tenant whose chart was seeded
        // earlier (or by another door — the e2e global setup, the onboarding
        // flow) never takes that path, and used to land on a tree whose only
        // visible rows were the five PACT types.
        expect(await screen.findByText("Capital Account")).toBeTruthy();
        expect(screen.getByText("Equity")).toBeTruthy();
    });

    it("leaves a collapsed group collapsed when the list is refetched", async () => {
        accountsFixture = seededTree;
        render(<AccountsPage />);
        await screen.findByText("Capital Account");
        expect(accountGets()).toBe(1);

        // The chevron on the group row.
        const groupRow = document.querySelector('[data-code="F"]') as HTMLElement;
        fireEvent.click(within(groupRow).getAllByRole("button")[0]);
        await waitFor(() => expect(screen.queryByText("Capital Account")).toBeNull());

        // A create refetches the list; the auto-expand must not fire twice and
        // undo what the user just did.
        //
        // `handleCreate` does not await that refetch, so waiting on the POST
        // alone re-checks a condition that is already true and never observes
        // the re-render this test exists to check. The second answer carries
        // one MORE account instead — a root leaf, visible whatever the tree is
        // doing — so the list landing is a positive event to wait for.
        // `setAccounts` and the would-be `setExpandedIds` are two writes in one
        // continuation, so React commits them together: when the new row is on
        // screen, a re-expand would be on screen with it.
        accountsFixture = [...seededTree, suspenseAccount];
        fireEvent.click(screen.getByRole("button", { name: "addAccount" }));
        fireEvent.submit(document.querySelector("form") as HTMLFormElement);
        await waitFor(() => expect(calls.some(c => c.init?.method === "POST")).toBe(true));

        expect(await screen.findByText("Suspense Account")).toBeTruthy();
        // At least two: the mount's read and the create's. Not exactly two —
        // the Add modal's own parent AccountPicker reads the chart as well.
        expect(accountGets()).toBeGreaterThanOrEqual(2);
        expect(screen.queryByText("Capital Account")).toBeNull();
    });

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

    it("posts the v2 create body: parentId, no parentCode, blank code omitted", async () => {
        render(<AccountsPage />);
        await screen.findByText("Landscaping");

        fireEvent.click(screen.getByRole("button", { name: "addAccount" }));
        const form = document.querySelector("form");
        fireEvent.submit(form as HTMLFormElement);

        await waitFor(() => expect(calls.some((c) => c.init?.method === "POST")).toBe(true));
        const post = calls.find((c) => c.init?.method === "POST");
        const body = JSON.parse(String(post?.init?.body));
        // CreateAccountRequest 400s on any field it doesn't declare.
        expect(Object.keys(body)).not.toContain("parentCode");
        expect(Object.keys(body)).not.toContain("hierarchyLevel");
        // A blank code is left out so the backend assigns the next numeric code.
        expect(Object.keys(body)).not.toContain("code");
        expect(body).toMatchObject({
            accountType: "ASSET",
            accountSubType: null,
            parentId: null,
            propertyId: null,
            alias: null,
            group: false,
        });
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

        // UpdateAccountRequest carries none of code/accountType/parentId/group
        // (and 400s on an unknown field), so the edit form must not offer them.
        const codeInput = screen.getByDisplayValue("D-99") as HTMLInputElement;
        expect(codeInput.disabled).toBe(true);

        fireEvent.submit(form as HTMLFormElement);

        const put = calls.find((c) => c.init?.method === "PUT");
        expect(put).toBeTruthy();
        const body = JSON.parse(String(put?.init?.body));
        expect(Object.keys(body)).not.toContain("code");
        expect(Object.keys(body)).not.toContain("accountType");
        expect(Object.keys(body)).not.toContain("parentCode");
        expect(Object.keys(body)).not.toContain("parentId");
        expect(Object.keys(body)).not.toContain("group");
        // propertyId is always applied by the backend, so the current value
        // must be sent or the account silently loses its property tag.
        expect(Object.keys(body)).toContain("propertyId");
        // Passthrough so updateAccount doesn't reset them to defaults.
        expect(body.active).toBe(false);
        expect(body.displayOrder).toBe(7);
        expect(body.nameEn).toBe("Landscaping");
    });
});
