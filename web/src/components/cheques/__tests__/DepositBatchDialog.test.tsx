import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";

// #10: the deposit run can bank each cheque on its own date instead of one
// date for the whole selection. Default stays one date.

const api = vi.hoisted(() => ({ depositBatch: vi.fn(async () => []) }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, chequeApi: { ...m.chequeApi, depositBatch: api.depositBatch } };
});
vi.mock("@/components/finance/SettlementAccountPicker", () => ({ default: () => <div /> }));

import DepositBatchDialog from "../DepositBatchDialog";

const renderDialog = () => render(
    <NextIntlClientProvider locale="en" messages={en}>
        <DepositBatchDialog open chequeIds={["c1", "c2"]} total={2000} onClose={() => {}} onDone={() => {}} />
    </NextIntlClientProvider>,
);

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("DepositBatchDialog", () => {
    it("banks the selection on one date by default", async () => {
        renderDialog();
        fireEvent.change(screen.getByTestId("deposit-batch-date"), { target: { value: "2026-09-01" } });
        fireEvent.click(screen.getByTestId("deposit-batch-confirm"));

        await waitFor(() => expect(api.depositBatch).toHaveBeenCalledWith({
            chequeIds: ["c1", "c2"], date: "2026-09-01", debitAccountId: null,
        }));
    });

    it("can bank each cheque on its own date", async () => {
        renderDialog();
        fireEvent.click(screen.getByTestId("deposit-batch-own-dates"));
        expect(screen.queryByTestId("deposit-batch-date")).toBeNull();
        fireEvent.click(screen.getByTestId("deposit-batch-confirm"));

        await waitFor(() => expect(api.depositBatch).toHaveBeenCalledWith(
            expect.objectContaining({ chequeIds: ["c1", "c2"], useChequeDates: true }),
        ));
    });
});
