import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ScanChequesLauncher from "../ScanChequesLauncher";

const paged = vi.fn();
const cheques = vi.fn();
vi.mock("@/lib/api/leasing", async (orig) => {
    const real = await orig<typeof import("@/lib/api/leasing")>();
    return { ...real, leaseApi: { ...real.leaseApi, paged: (...a: unknown[]) => paged(...a), cheques: (...a: unknown[]) => cheques(...a) } };
});
// The flow itself has its own tests; here it only has to receive the right contract.
vi.mock("../BulkChequeUploadFlow", () => ({
    default: (p: { leaseId: string; rows: unknown[]; onlyChequeId?: string | null }) => (
        <div data-testid="flow" data-lease={p.leaseId} data-rows={p.rows.length} data-only={p.onlyChequeId ?? ""} />
    ),
}));

const lease = (id: string, status: string) => ({ id, status, unitIdentifier: `U-${id}`, renterName: "Renter", propertyName: "Palm" });
const wrap = (ui: React.ReactNode) => <NextIntlClientProvider locale="en" messages={en}>{ui}</NextIntlClientProvider>;

beforeEach(() => {
    paged.mockReset();
    cheques.mockReset();
});
afterEach(cleanup);

describe("ScanChequesLauncher", () => {
    it("finds a contract, then opens the scan on its cheques; a closed contract is not offered", async () => {
        paged.mockResolvedValue({ content: [lease("a", "ACTIVE"), lease("d", "DRAFT"), lease("c", "CLOSED")] });
        cheques.mockResolvedValue([{ id: "x" }, { id: "y" }]);
        render(wrap(<ScanChequesLauncher open onClose={() => {}} onDone={() => {}} />));
        fireEvent.change(screen.getByTestId("scan-cheques-lease-search"), { target: { value: "U-" } });
        await waitFor(() => screen.getByTestId("scan-cheques-lease-option-a"));
        expect(screen.getByTestId("scan-cheques-lease-option-d")).toBeTruthy();
        expect(screen.queryByTestId("scan-cheques-lease-option-c")).toBeNull();
        fireEvent.click(screen.getByTestId("scan-cheques-lease-option-a"));
        fireEvent.click(screen.getByTestId("scan-cheques-continue"));
        const flow = await screen.findByTestId("flow");
        expect(cheques).toHaveBeenCalledWith("a");
        expect(flow.dataset.lease).toBe("a");
        expect(flow.dataset.rows).toBe("2");
    });

    it("a register row goes straight to the scan, narrowed to its cheque", async () => {
        cheques.mockResolvedValue([{ id: "x" }]);
        render(wrap(<ScanChequesLauncher open leaseId="L9" chequeId="x" onClose={() => {}} onDone={() => {}} />));
        const flow = await screen.findByTestId("flow");
        expect(flow.dataset.lease).toBe("L9");
        expect(flow.dataset.only).toBe("x");
        expect(paged).not.toHaveBeenCalled();
    });
});
