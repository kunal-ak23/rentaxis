// src/components/collections/__tests__/DueChequesPanel.test.tsx
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
const due = vi.hoisted(() => vi.fn());
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, chequeApi: { ...m.chequeApi, due } };
});
import DueChequesPanel, { OVERDUE_SCAN_SIZE } from "../DueChequesPanel";

const row = (id: string, overdue: boolean) => ({ id, seqNo: 1, leaseId: `l-${id}`, unitIdentifier: `U-${id}`, renterName: `T ${id}`, chequeNumber: `C${id}`, postingDate: "2026-09-01", chequeDate: "2026-09-01", amount: 1000, overdue, daysOverdue: overdue ? 12 : 0 });
const page = (content: unknown[], totalElements: number, number = 0, size = 25) => ({ content, totalElements, totalPages: Math.ceil(totalElements / size), number, size });
afterEach(() => { cleanup(); due.mockReset(); });

describe("DueChequesPanel", () => {
    it("Due lists every due row and pages on the server", async () => {
        due.mockResolvedValue(page([row("1", true), row("2", false)], 60));
        render(<DueChequesPanel overdueOnly={false} propertyId="p1" />);
        expect(await screen.findByText("U-1")).toBeInTheDocument();
        expect(screen.getByText("U-2")).toBeInTheDocument();
        expect(due).toHaveBeenLastCalledWith({ propertyId: "p1", page: 0, size: 25 });
        fireEvent.click(screen.getByRole("button", { name: "2" }));
        await waitFor(() => expect(due).toHaveBeenLastCalledWith({ propertyId: "p1", page: 1, size: 25 }));
    });

    it("Overdue shows only overdue rows and says how far it has scanned", async () => {
        due.mockResolvedValueOnce(page([row("1", true), row("2", false)], 2, 0, OVERDUE_SCAN_SIZE));
        render(<DueChequesPanel overdueOnly propertyId="p1" />);
        expect(await screen.findByText("U-1")).toBeInTheDocument();
        expect(screen.queryByText("U-2")).toBeNull();
        expect(due).toHaveBeenLastCalledWith({ propertyId: "p1", page: 0, size: OVERDUE_SCAN_SIZE });
        expect(screen.getByTestId("overdue-scanned")).toHaveTextContent("1 overdue found in the first 2 of 2 due cheques");
        expect(screen.queryByTestId("overdue-load-more")).toBeNull();
    });

    it("Overdue loads the next due page on demand instead of stopping silently", async () => {
        const first = Array.from({ length: OVERDUE_SCAN_SIZE }, (_, i) => row(`a${i}`, i < 3));
        due.mockResolvedValueOnce(page(first, OVERDUE_SCAN_SIZE + 1, 0, OVERDUE_SCAN_SIZE))
            .mockResolvedValueOnce(page([row("z", true)], OVERDUE_SCAN_SIZE + 1, 1, OVERDUE_SCAN_SIZE));
        render(<DueChequesPanel overdueOnly />);
        expect(await screen.findByText("U-a0")).toBeInTheDocument();
        fireEvent.click(screen.getByTestId("overdue-load-more"));
        expect(await screen.findByText("U-z")).toBeInTheDocument();
        expect(due).toHaveBeenLastCalledWith({ propertyId: undefined, page: 1, size: OVERDUE_SCAN_SIZE });
        expect(screen.getByTestId("overdue-scanned")).toHaveTextContent(`4 overdue found in the first ${OVERDUE_SCAN_SIZE + 1} of ${OVERDUE_SCAN_SIZE + 1}`);
        expect(screen.queryByTestId("overdue-load-more")).toBeNull();
    });

    it("links each row to its contract and its cheque in the register", async () => {
        due.mockResolvedValue(page([row("1", true)], 1));
        render(<DueChequesPanel overdueOnly />);
        expect((await screen.findByText("U-1")).closest("a")).toHaveAttribute("href", "/dashboard/leases/l-1");
        expect(screen.getByText("C1").closest("a")).toHaveAttribute("href", "/dashboard/collections?tab=all&search=C1");
    });
});
