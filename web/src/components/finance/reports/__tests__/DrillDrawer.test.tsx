import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../messages/en.json";

const lines = vi.hoisted(() => vi.fn(async () => ({ lines: [], totalDebit: 0, totalCredit: 0, truncated: false })));
vi.mock("@/lib/api/propertyReports", async orig => {
    const m = await orig<typeof import("@/lib/api/propertyReports")>();
    return { ...m, propertyReportsApi: { ...m.propertyReportsApi, lines } };
});
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));

import DrillDrawer from "../DrillDrawer";

afterEach(() => { cleanup(); lines.mockClear(); });

const column = { key: "TOTAL", propertyId: null, kind: "TOTAL" as const, name: "TOTAL", nameAr: null };

describe("DrillDrawer", () => {
    /** Review P2-6: a NOI over hundreds of leaves must not become a URL of account ids. */
    it("asks for a NOI by key with the report's selection, not by account ids", async () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <DrillDrawer target={{ column, label: "NOI" }} from="2026-09-01" to="2026-09-30"
                    propertyIds={["p1"]} locale="en" canOpenLedger onClose={() => {}} />
            </NextIntlClientProvider>,
        );
        expect(await screen.findByText(en.PropertyReports.noLines)).toBeTruthy();
        expect(lines).toHaveBeenCalledWith({
            from: "2026-09-01", to: "2026-09-30", column: "TOTAL", rowKey: null, groupId: null, propertyIds: ["p1"],
        });
        // No ledger link for a figure that is not one row.
        expect(screen.queryByText(en.PropertyReports.openInLedger)).toBeNull();
    });
});
