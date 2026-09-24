import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { LeaseAddendum } from "@/lib/api/leasing";

const recordAddendumEjari = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, recordAddendumEjari: (...a: unknown[]) => recordAddendumEjari(...a) } };
});

import LeaseAddendaPanel from "../LeaseAddendaPanel";

const PENDING = {
    id: "a1", addendumNumber: "ADD-27/1", effectiveFrom: "2027-02-15", contractDate: "2027-02-10",
    ejariNumber: null, ejariPending: true, reason: "Parking", value: 6000,
    tcoJournalId: "j1", tcoEntryNumber: "TCO-27/9", superseded: false, createdAt: "2027-02-10T00:00:00Z",
};

const SUPERSEDED = {
    ...PENDING, id: "a2", addendumNumber: "ADD-27/2", tcoEntryNumber: "TCO-27/11", superseded: true,
};

const RECORDED = {
    ...PENDING, id: "a3", addendumNumber: "ADD-27/3", ejariNumber: "EJ-9", ejariPending: false,
};

afterEach(() => { cleanup(); recordAddendumEjari.mockReset(); });

function renderPanel(canRecord: boolean, onChanged = vi.fn(), addenda: LeaseAddendum[] = [PENDING]) {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseAddendaPanel leaseId="lease-1" addenda={addenda} canRecordEjari={canRecord} onChanged={onChanged} />
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

    it("flags a superseded addendum and hides its record-Ejari control", () => {
        renderPanel(true, vi.fn(), [SUPERSEDED]);
        expect(screen.getByText("ADD-27/2")).toBeInTheDocument();
        expect(screen.getByText("TCO-27/11")).toBeInTheDocument();
        expect(screen.getByText("Superseded by amendment")).toBeInTheDocument();
        // Its own TCO was reversed by the amend; re-registering Ejari for it makes no sense.
        expect(screen.queryByTestId("addendum-ejari-a2")).not.toBeInTheDocument();
    });

    it("still offers the record control to a live (non-superseded) pending addendum", () => {
        renderPanel(true);
        expect(screen.queryByText("Superseded by amendment")).not.toBeInTheDocument();
        expect(screen.getByTestId("addendum-ejari-a1")).toBeInTheDocument();
    });

    it("lets an already-recorded Ejari be edited (F14-33)", async () => {
        recordAddendumEjari.mockResolvedValue({ ...RECORDED, ejariNumber: "EJ-10" });
        const onChanged = renderPanel(true, vi.fn(), [RECORDED]);
        // Read-only by default: the number shown, no input yet.
        expect(screen.getByText("EJ-9")).toBeInTheDocument();
        expect(screen.queryByTestId("addendum-ejari-a3")).not.toBeInTheDocument();

        fireEvent.click(screen.getByTestId("addendum-ejari-edit-a3"));
        const input = screen.getByTestId("addendum-ejari-a3") as HTMLInputElement;
        expect(input.value).toBe("EJ-9"); // seeded from the current number
        fireEvent.change(input, { target: { value: "EJ-10" } });
        fireEvent.click(screen.getByTestId("addendum-ejari-save-a3"));

        await waitFor(() => expect(onChanged).toHaveBeenCalled());
        expect(recordAddendumEjari).toHaveBeenCalledWith("lease-1", "a3", "EJ-10");
    });

    it("hides the edit pencil on a recorded Ejari from a role that cannot post, and on a superseded addendum", () => {
        renderPanel(false, vi.fn(), [RECORDED]);
        expect(screen.queryByTestId("addendum-ejari-edit-a3")).not.toBeInTheDocument();
        cleanup();
        renderPanel(true, vi.fn(), [{ ...RECORDED, superseded: true }]);
        expect(screen.queryByTestId("addendum-ejari-edit-a3")).not.toBeInTheDocument();
    });
});
