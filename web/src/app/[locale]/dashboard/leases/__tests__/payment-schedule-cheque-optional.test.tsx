import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// The scanner pulls in camera/upload machinery that is irrelevant here.
vi.mock("@/components/cheques/ChequeScanner", () => ({ default: () => null }));

import PaymentScheduleEditor from "../PaymentScheduleEditor";

/**
 * Cheque details are optional on a payment plan.
 *
 * A plan is agreed at signing but cheques arrive on the renter's own schedule.
 * The editor used to refuse to save while any CHEQUE row lacked cheque #, date
 * and bank — and CHEQUE is the default method, so the only way to save a
 * partially-collected plan was to relabel the outstanding rows as CASH, which
 * recorded a payment method that was not true.
 */

const LEASE_ID = "lease-1";

function row(n: number, overrides: Record<string, unknown> = {}) {
    return {
        id: `row-${n}`,
        leaseId: LEASE_ID,
        installmentNumber: n,
        dueDate: `2026-0${n}-01`,
        amount: 6000,
        status: "PENDING",
        paymentMethod: "CHEQUE",
        chequeNumber: null,
        chequeDate: null,
        bankName: null,
        payerName: null,
        chequeImageUrl: null,
        chequeImageBlobPath: null,
        chequeImageUploadedAt: null,
        purposeLabel: null,
        ...overrides,
    };
}

let putBodies: unknown[] = [];

function mockFetch(rows: unknown[]) {
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (init?.method === "PUT") {
            putBodies.push(JSON.parse(String(init.body)));
            return { ok: true, json: async () => rows } as Response;
        }
        if (u.includes(`/payments/lease/${LEASE_ID}`)) {
            return { ok: true, json: async () => rows } as Response;
        }
        return { ok: true, json: async () => ({}) } as Response;
    }) as unknown as typeof fetch;
}

async function renderEditor(rows: unknown[]) {
    render(
        <PaymentScheduleEditor leaseId={LEASE_ID} leaseStatus="DRAFT" canManage={true} />,
    );
    await waitFor(() => expect(screen.queryByText(/loading payment schedule/i)).toBeNull());
}

/**
 * Save is disabled until the form is dirty, so every test has to make an edit
 * first. Nudging the first row's amount dirties the form without touching any
 * cheque field — which matters, since what these tests assert is what happens
 * when cheque fields are left alone.
 */
function dirtyTheForm() {
    const amounts = screen.getAllByRole("spinbutton");
    fireEvent.change(amounts[0], { target: { value: "6001" } });
}

function save() {
    dirtyTheForm();
    fireEvent.click(screen.getByRole("button", { name: /save schedule/i }));
}

beforeEach(() => {
    putBodies = [];
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("payment schedule — cheque details optional", () => {
    it("saves a plan where no cheque has been received yet", async () => {
        mockFetch([row(1), row(2), row(3)]);
        await renderEditor([row(1), row(2), row(3)]);

        save();

        await waitFor(() => expect(putBodies).toHaveLength(1));
        expect(screen.queryByText(/cheque rows need/i)).toBeNull();

        // The rows stay CHEQUE rather than being downgraded to CASH.
        const body = putBodies[0] as { rows: Array<{ paymentMethod: string; chequeNumber: string | null }> };
        expect(body.rows).toHaveLength(3);
        expect(body.rows.every((r) => r.paymentMethod === "CHEQUE")).toBe(true);
        expect(body.rows.every((r) => r.chequeNumber === null)).toBe(true);
    });

    it("saves a plan where only some cheques have been received", async () => {
        const rows = [
            row(1, { chequeNumber: "100200", chequeDate: "2026-01-05", bankName: "Emirates NBD" }),
            row(2),
            row(3),
        ];
        mockFetch(rows);
        await renderEditor(rows);

        save();

        await waitFor(() => expect(putBodies).toHaveLength(1));
        expect(screen.queryByText(/cheque rows need/i)).toBeNull();

        const body = putBodies[0] as { rows: Array<{ paymentMethod: string; chequeNumber: string | null }> };
        expect(body.rows.filter((r) => r.chequeNumber !== null)).toHaveLength(1);
        expect(body.rows.every((r) => r.paymentMethod === "CHEQUE")).toBe(true);
    });

    it("still refuses a row with no due date", async () => {
        const rows = [row(1, { dueDate: "" })];
        mockFetch(rows);
        await renderEditor(rows);

        save();

        await waitFor(() => expect(screen.getByText(/due date is required/i)).toBeTruthy());
        expect(putBodies).toHaveLength(0);
    });

    it("still requires bank and date on a bank-transfer row", async () => {
        const rows = [row(1, { paymentMethod: "BANK_TRANSFER" })];
        mockFetch(rows);
        await renderEditor(rows);

        save();

        await waitFor(() => expect(screen.getByText(/rows need bank and date/i)).toBeTruthy());
        expect(putBodies).toHaveLength(0);
    });
});
