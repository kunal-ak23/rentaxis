import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";

const recordAddendumEjari = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, recordAddendumEjari: (...a: unknown[]) => recordAddendumEjari(...a) } };
});

import LeaseAddendaPanel from "../LeaseAddendaPanel";

const PENDING = {
    id: "a1", addendumNumber: "ADD-27/1", effectiveFrom: "2027-02-15", contractDate: "2027-02-10",
    ejariNumber: null, ejariPending: true, reason: "Parking", value: 6000,
    tcoJournalId: "j1", tcoEntryNumber: "TCO-27/9", createdAt: "2027-02-10T00:00:00Z",
};

afterEach(() => { cleanup(); recordAddendumEjari.mockReset(); });

function renderPanel(canRecord: boolean, onChanged = vi.fn()) {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseAddendaPanel leaseId="lease-1" addenda={[PENDING]} canRecordEjari={canRecord} onChanged={onChanged} />
        </NextIntlClientProvider>,
    );
    return onChanged;
}

describe("LeaseAddendaPanel", () => {
    it("flags an addendum with no Ejari as pending and lets finance record it", async () => {
        recordAddendumEjari.mockResolvedValue({ ...PENDING, ejariNumber: "EJ-1", ejariPending: false });
        const onChanged = renderPanel(true);
        expect(screen.getByText("ADD-27/1")).toBeInTheDocument();
        expect(screen.getByText("Ejari pending")).toBeInTheDocument();

        fireEvent.change(screen.getByTestId("addendum-ejari-a1"), { target: { value: "EJ-1" } });
        fireEvent.click(screen.getByTestId("addendum-ejari-save-a1"));
        await waitFor(() => expect(onChanged).toHaveBeenCalled());
        expect(recordAddendumEjari).toHaveBeenCalledWith("lease-1", "a1", "EJ-1");
    });

    it("shows no record control to a role that cannot post", () => {
        renderPanel(false);
        expect(screen.queryByTestId("addendum-ejari-a1")).not.toBeInTheDocument();
    });
});
