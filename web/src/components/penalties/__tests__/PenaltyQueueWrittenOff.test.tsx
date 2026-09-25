import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../messages/ar.json";

const list = vi.hoisted(() => vi.fn());
vi.mock("@/lib/api/leasing", async (orig) => {
    const real = await orig<typeof import("@/lib/api/leasing")>();
    return { ...real, penaltyApi: { ...real.penaltyApi, list: (q: unknown) => list(q) } };
});

import PenaltyQueue from "../PenaltyQueue";

/** PR #361 R2: the queue lists charges taken by a bad-debt write-off (translated tab). */
describe("PenaltyQueue written-off tab", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("asks the server for WRITTEN_OFF charges", async () => {
        list.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 200 });
        render(
            <NextIntlClientProvider locale="ar" messages={ar}>
                <PenaltyQueue userRole="TENANT_ADMIN" />
            </NextIntlClientProvider>,
        );
        const tab = await screen.findByTestId("penalty-tab-WRITTEN_OFF");
        expect(tab.textContent).toBe("مشطوب");
        fireEvent.click(tab);
        await waitFor(() => expect(list).toHaveBeenCalledWith(expect.objectContaining({ status: "WRITTEN_OFF" })));
    });
});
