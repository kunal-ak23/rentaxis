import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import * as fs from "node:fs";
import * as path from "node:path";

import ar from "../../../../../../messages/ar.json";
import en from "../../../../../../messages/en.json";

vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock("@/components/cheques/ChequeScanner", () => ({ default: () => null }));
vi.mock("@/components/cheques/BulkChequeUploadFlow", () => ({ default: () => null }));
vi.mock("@/hooks/useLeasePartyOptions", () => ({
    useLeasePartyOptions: () => ({ unitOptions: [], renterOptions: [], loading: false }),
}));

import LeaseWizard from "../LeaseWizard";

/**
 * The lease wizard and its payment-schedule editor are the flow this product is
 * demoed on, and neither had a single useTranslations call — every label, step
 * name, validation message and button was an English literal.
 */

const WIZARD = path.join(__dirname, "..", "LeaseWizard.tsx");
const EDITOR = path.join(__dirname, "..", "PaymentScheduleEditor.tsx");

function renderWizard(locale: "en" | "ar") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <LeaseWizard open units={[]} renters={[]} onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => ({}) })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("lease wizard localization", () => {
    it("renders its title, steps and controls in Arabic", async () => {
        // Step labels render as "1. <label>", so the text is split across nodes
        // and an exact-string matcher never finds them.
        const { container } = renderWizard("ar");
        await waitFor(() => expect(container.textContent).toContain(ar.LeaseWizard.stepParties));

        const text = container.textContent ?? "";
        expect(text).toContain(ar.LeaseWizard.stepTerms);
        expect(text).toContain(ar.LeaseWizard.stepPaymentPlan);
        expect(text).toContain(ar.LeaseWizard.titlePrefix);
        expect(text).toContain(ar.LeaseWizard.unitRequired);
        expect(text).toContain(ar.LeaseWizard.renterRequired);
        expect(screen.getByLabelText(ar.LeaseWizard.closeWizard)).toBeInTheDocument();
    });

    it("leaves no English wizard literal on an Arabic page", async () => {
        const { container } = renderWizard("ar");
        await waitFor(() => expect(container.textContent).toContain(ar.LeaseWizard.stepParties));
        const text = container.textContent ?? "";

        for (const literal of [
            "Parties", "Terms", "Charges & VAT", "Payment plan", "Schedule & finalize",
            "New Lease", "Unit *", "Renter *", "Next", "Back",
        ]) {
            expect(text, `"${literal}" should not appear in the Arabic wizard`).not.toContain(literal);
        }
    });

    it("still renders English in the English locale", async () => {
        const { container } = renderWizard("en");
        await waitFor(() => expect(container.textContent).toContain("Parties"));
        expect(container.textContent).toContain("Payment plan");
        expect(container.textContent).toContain("Unit *");
    });

    it("resolves every key both files call, in both catalogues", () => {
        const cases: [string, string, Record<string, string>, Record<string, string>][] = [
            ["LeaseWizard", WIZARD, en.LeaseWizard, ar.LeaseWizard],
            ["PaymentSchedule", EDITOR, en.PaymentSchedule, ar.PaymentSchedule],
        ];

        for (const [ns, file, enNs, arNs] of cases) {
            const source = fs.readFileSync(file, "utf8");
            const keys = [...new Set(
                [...source.matchAll(/\bt(?:\.rich)?\("([^"]+)"/g)].map((m) => m[1]),
            )];
            expect(keys.length, `${ns}: expected the file to call translations`).toBeGreaterThan(10);

            expect(keys.filter((k) => !(k in enNs)), `${ns}: keys absent from en.json`).toEqual([]);
            expect(keys.filter((k) => !(k in arNs)), `${ns}: keys absent from ar.json`).toEqual([]);
            // A key present but still holding the English string renders English.
            expect(keys.filter((k) => arNs[k] === enNs[k]), `${ns}: keys still holding English`).toEqual([]);
        }
    });

    it("has no bare English literal left in either source file", () => {
        for (const file of [WIZARD, EDITOR]) {
            const source = fs.readFileSync(file, "utf8");
            const jsxText = [...source.matchAll(/>\s*([A-Z][A-Za-z0-9 ,&.'%/()#*—:-]{2,70}?)\s*</g)]
                .map((m) => m[1]);
            const attrs = [...source.matchAll(/(?:placeholder|title|aria-label|label)="([^"]{3,70})"/g)]
                .map((m) => m[1]);
            expect([...jsxText, ...attrs], `${path.basename(file)} still has English literals`).toEqual([]);
        }
    });
});
