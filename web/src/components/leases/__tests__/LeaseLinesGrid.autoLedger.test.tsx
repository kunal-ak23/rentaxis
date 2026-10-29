import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useState } from "react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import LeaseLinesGrid from "../LeaseLinesGrid";
import { blankLine, type LineRow } from "../leaseMath";
import type { ChargeType } from "@/lib/api/leasing";

/**
 * Client request (2026-10-28): the Ledger column fills itself in. Choosing the
 * Particulars shows the account the server would credit that line to on this
 * property (`GET /leases/line-accounts`) — before the draft is saved, not after —
 * so the box no longer reads as something the user has to type.
 */

const api = vi.hoisted(() => ({ lineAccounts: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => ({
    ...(await orig<typeof import("@/lib/api/leasing")>()),
    leaseApi: api,
}));
vi.mock("@/components/finance/AccountPicker", () => ({
    default: ({ value, onChange }: { value: string | null; onChange: (id: string) => void }) => (
        <div data-testid="account-picker" data-value={value ?? ""}>
            <button type="button" data-testid="pick-by-hand" onClick={() => onChange("acc-by-hand")} />
        </div>
    ),
}));

const CHARGE_TYPES: ChargeType[] = [
    { id: "ct-rent", code: "RENT", nameEn: "Rent", nameAr: null, role: "RENTAL_INCOME",
        behaviour: "RENT", vatApplicableDefault: false, active: true, displayOrder: 1 },
    { id: "ct-dep", code: "SECURITY_DEPOSIT", nameEn: "Security Deposit", nameAr: null, role: "SECURITY_DEPOSIT",
        behaviour: "DEPOSIT", vatApplicableDefault: false, active: true, displayOrder: 2 },
    { id: "ct-fee", code: "ADMIN_FEE", nameEn: "Admin Fee", nameAr: null, role: "ADMIN_FEE",
        behaviour: "FEE", vatApplicableDefault: true, active: true, displayOrder: 3 },
];
const SUGGESTED = [
    { chargeTypeId: "ct-rent", accountId: "acc-rent-income" },
    { chargeTypeId: "ct-dep", accountId: "acc-sd" },
];

/** Every grid the component handed up, newest last. */
const emitted: LineRow[][] = [];
const latest = () => emitted[emitted.length - 1];
function Harness({ initial, editable = true, propertyId = "p1" }: {
    initial: LineRow[]; editable?: boolean; propertyId?: string | null;
}) {
    const [lines, setLines] = useState(initial);
    return (
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseLinesGrid lines={lines} chargeTypes={CHARGE_TYPES} propertyId={propertyId} editable={editable}
                onChange={rows => { emitted.push(rows); setLines(rows); }} />
        </NextIntlClientProvider>
    );
}

const ledger = (i: number) => screen.getAllByTestId("account-picker")[i].getAttribute("data-value");

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    emitted.length = 0;
});

describe("the Ledger column fills itself in", () => {
    it("shows the property's account for a line that has a Particulars but no account yet", async () => {
        api.lineAccounts.mockResolvedValue(SUGGESTED);
        render(<Harness initial={[{ ...blankLine(0), chargeTypeId: "ct-rent", grossAmount: 60000 }]} />);
        await waitFor(() => expect(ledger(0)).toBe("acc-rent-income"));
        expect(api.lineAccounts).toHaveBeenCalledWith("p1");
        // Shown, not written: the save leaves it to the server, which picks the same account.
        expect(emitted).toHaveLength(0);
    });

    it("follows the Particulars as it is chosen and changed", async () => {
        api.lineAccounts.mockResolvedValue(SUGGESTED);
        render(<Harness initial={[blankLine(0)]} />);
        await waitFor(() => expect(api.lineAccounts).toHaveBeenCalled());
        expect(ledger(0)).toBe("");
        fireEvent.change(screen.getByTestId("lease-line-type-0"), { target: { value: "ct-dep" } });
        await waitFor(() => expect(ledger(0)).toBe("acc-sd"));
        fireEvent.change(screen.getByTestId("lease-line-type-0"), { target: { value: "ct-rent" } });
        expect(ledger(0)).toBe("acc-rent-income");
    });

    it("drops the saved account of a line whose Particulars changes, so the new one's shows", async () => {
        api.lineAccounts.mockResolvedValue(SUGGESTED);
        render(<Harness initial={[{ ...blankLine(0), id: "l1", chargeTypeId: "ct-rent", creditAccountId: "acc-saved",
            creditAccountName: "Rental Income Tower A" }]} />);
        await waitFor(() => expect(api.lineAccounts).toHaveBeenCalled());
        expect(ledger(0)).toBe("acc-saved");
        fireEvent.change(screen.getByTestId("lease-line-type-0"), { target: { value: "ct-dep" } });
        await waitFor(() => expect(ledger(0)).toBe("acc-sd"));
        expect(latest()[0].creditAccountId).toBeNull();
    });

    it("keeps an account the user picked by hand when the Particulars changes", async () => {
        api.lineAccounts.mockResolvedValue(SUGGESTED);
        render(<Harness initial={[{ ...blankLine(0), chargeTypeId: "ct-rent" }]} />);
        await waitFor(() => expect(ledger(0)).toBe("acc-rent-income"));
        fireEvent.click(screen.getByTestId("pick-by-hand"));
        fireEvent.change(screen.getByTestId("lease-line-type-0"), { target: { value: "ct-dep" } });
        expect(ledger(0)).toBe("acc-by-hand");
        expect(latest()[0].creditAccountId).toBe("acc-by-hand");
    });

    it("leaves a type with nothing mapped empty, and still works when the lookup fails", async () => {
        api.lineAccounts.mockRejectedValue(new Error("403"));
        render(<Harness initial={[{ ...blankLine(0), chargeTypeId: "ct-fee" }]} />);
        await waitFor(() => expect(api.lineAccounts).toHaveBeenCalled());
        expect(ledger(0)).toBe("");
    });

    it("never shows another building's account while the new building's answer is on its way", async () => {
        api.lineAccounts.mockResolvedValueOnce(SUGGESTED).mockReturnValueOnce(new Promise(() => {}));
        const row = { ...blankLine(0), chargeTypeId: "ct-rent" };
        const { rerender } = render(<Harness initial={[row]} propertyId="p1" />);
        await waitFor(() => expect(ledger(0)).toBe("acc-rent-income"));
        rerender(<Harness initial={[row]} propertyId="p2" />);
        await waitFor(() => expect(api.lineAccounts).toHaveBeenCalledWith("p2"));
        expect(ledger(0)).toBe("");
    });

    it("asks nothing for a read-only grid or one with no property yet", () => {
        render(<Harness initial={[{ ...blankLine(0), chargeTypeId: "ct-rent" }]} editable={false} />);
        cleanup();
        render(<Harness initial={[{ ...blankLine(0), chargeTypeId: "ct-rent" }]} propertyId={null} />);
        expect(api.lineAccounts).not.toHaveBeenCalled();
    });
});
