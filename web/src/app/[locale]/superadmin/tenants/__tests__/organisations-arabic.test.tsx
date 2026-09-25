import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import ar from "../../../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";

vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));
vi.mock("@/components/ui/FileUpload", () => ({ FileUpload: ({ label }: { label: string }) => <span>{label}</span> }));
import TenantsPage from "../page";

/** PR #363 follow-up: the super-admin Organisations page was hard-coded English. */
afterEach(cleanup);

describe("Organisations page in Arabic", () => {
    it("shows no English chrome in the list or the provisioning form", async () => {
        global.fetch = vi.fn(async () => ({ ok: true, json: async () => [{ id: "t-1", name: "Acme Co", status: "INACTIVE", createdAt: "2026-01-01T00:00:00Z", trn: "100200300400500" }] })) as unknown as typeof fetch;
        const { container } = render(<NextIntlClientProvider locale="ar" messages={ar}><TenantsPage /></NextIntlClientProvider>);
        await screen.findByText("Acme Co");
        expect(screen.getByText(ar.SuperAdmin.orgStatusINACTIVE)).toBeInTheDocument();
        fireEvent.click(screen.getByText(ar.SuperAdmin.orgProvision));
        // "TRN" is the UAE's own acronym, kept in the Arabic label.
        expect(leftoverLatinWords(visibleText(container), ["Acme Co", "t-1", "TRN", "OTP", "XXXXXXXXX"])).toEqual([]);
    });
});
