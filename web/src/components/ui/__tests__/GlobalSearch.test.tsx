import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import GlobalSearch from "../GlobalSearch";

const push = vi.fn();

vi.mock("next/navigation", () => ({
    useRouter: () => ({ push }),
}));

const response = (body: unknown) => ({ ok: true, json: async () => body });

beforeEach(() => {
    push.mockReset();
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes("/leases/paged")) {
            return response({
                content: [{
                    id: "lease-1",
                    unitIdentifier: "A-101",
                    renterName: "Samira Khan",
                    propertyName: "Marina Tower",
                    status: "ACTIVE",
                }],
            }) as Response;
        }
        if (url.includes("/v1/payments")) {
            return response({
                content: [{
                    id: "payment-1",
                    chequeNumber: "CHQ-7788",
                    renterName: "Samira Khan",
                    unitIdentifier: "A-101",
                    propertyName: "Marina Tower",
                    status: "COLLECTED",
                    installmentNumber: 2,
                }],
            }) as Response;
        }
        return response([{ id: "tenant-1", name: "Samira Properties", status: "ACTIVE" }]) as Response;
    });
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("GlobalSearch", () => {
    it("opens from the advertised button and returns role-aware results", async () => {
        render(<GlobalSearch role="SUPER_ADMIN" locale="en" />);
        fireEvent.click(screen.getByRole("button", { name: "Search leases, tenants, cheques…" }));
        fireEvent.change(screen.getByRole("textbox", { name: "Search RentAxis" }), {
            target: { value: "Samira" },
        });

        expect(await screen.findByText("A-101 · Samira Khan")).toBeTruthy();
        expect(screen.getByText("Cheque CHQ-7788")).toBeTruthy();
        expect(screen.getByText("Samira Properties")).toBeTruthy();
        expect(global.fetch).toHaveBeenCalledWith(
            expect.stringContaining("/leases/paged?search=Samira"),
            expect.objectContaining({ signal: expect.any(AbortSignal) }),
        );
    });

    it("opens with the documented keyboard shortcut and navigates to a lease", async () => {
        render(<GlobalSearch role="TENANT_ADMIN" locale="ar" />);
        fireEvent.keyDown(document, { key: "k", ctrlKey: true });
        fireEvent.change(screen.getByRole("textbox", { name: "Search RentAxis" }), {
            target: { value: "Samira" },
        });
        fireEvent.click(await screen.findByText("A-101 · Samira Khan"));

        await waitFor(() => expect(push).toHaveBeenCalledWith("/ar/dashboard/leases/lease-1"));
    });

    it("does not advertise administrative search to unsupported roles", () => {
        render(<GlobalSearch role="SECURITY_GUARD" locale="en" />);
        expect(screen.queryByRole("button", { name: /Search leases/ })).toBeNull();
    });
});
