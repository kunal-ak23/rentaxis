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
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/hooks/useLeasePartyOptions", () => ({
    useLeasePartyOptions: () => ({ unitOptions: [], renterOptions: [], loading: false }),
}));
vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));

import LeaseWizard from "../LeaseWizard";

/**
 * The contract wizard is the screen this product is demoed on, and every label
 * in it has to reach Arabic. It used to talk to the `LeaseWizard` namespace,
 * which described a payment-plan flow that no longer exists; it now speaks
 * `Leasing`, alongside the grids and the detail page.
 *
 * The old version of this file also grepped the source for bare English
 * literals. That heuristic reads `=> Promise<Cheque[]>` as the English word
 * "Promise" between a `>` and a `<`, so it fails on any file with a generic in
 * a return type. The assertion that matters — no English on an Arabic page —
 * is made by rendering, which cannot be fooled that way. What is still read
 * from the source is the list of keys the file calls, because a key that
 * resolves in English and is missing in Arabic renders English without
 * throwing.
 */

const WIZARD = path.join(__dirname, "..", "LeaseWizard.tsx");

function renderWizard(locale: "en" | "ar") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <LeaseWizard open units={[]} renters={[]} onClose={() => {}} onCreated={() => {}} />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("contract wizard localization", () => {
    it("renders its title, every step and its controls in Arabic", async () => {
        const { container } = renderWizard("ar");
        await waitFor(() => expect(container.textContent).toContain(ar.Leasing.stepParties));

        const text = container.textContent ?? "";
        for (const key of ["stepTerms", "stepLines", "stepCheques", "stepReview", "newContract", "next", "back"] as const) {
            expect(text, `${key} should render in Arabic`).toContain(ar.Leasing[key]);
        }
        expect(screen.getByLabelText(ar.Leasing.close)).toBeInTheDocument();
    });

    it("leaves no English wizard literal on an Arabic page", async () => {
        const { container } = renderWizard("ar");
        await waitFor(() => expect(container.textContent).toContain(ar.Leasing.stepParties));
        const text = container.textContent ?? "";

        for (const literal of ["Parties", "Terms", "Charges", "Cheques", "Review", "New Contract", "Next", "Back"]) {
            expect(text, `"${literal}" should not appear in the Arabic wizard`).not.toContain(literal);
        }
    });

    it("still renders English in the English locale", async () => {
        const { container } = renderWizard("en");
        await waitFor(() => expect(container.textContent).toContain("Parties"));
        expect(container.textContent).toContain("New Contract");
        expect(container.textContent).toContain("Review");
    });

    it("resolves every Leasing key it calls, in both catalogues", () => {
        const source = fs.readFileSync(WIZARD, "utf8");
        const keys = [...new Set([...source.matchAll(/\bt(?:\.rich)?\("([^"]+)"/g)].map(m => m[1]))]
            // Template keys (`mode.${m}`) are covered by the grids' own tests.
            .filter(k => !k.includes("${"));
        expect(keys.length, "expected the wizard to call translations").toBeGreaterThan(20);

        const enNs = en.Leasing as Record<string, unknown>;
        const arNs = ar.Leasing as Record<string, unknown>;
        expect(keys.filter(k => !(k.split(".")[0] in enNs)), "keys absent from en.json").toEqual([]);
        expect(keys.filter(k => !(k.split(".")[0] in arNs)), "keys absent from ar.json").toEqual([]);
        expect(
            keys.filter(k => typeof enNs[k] === "string" && arNs[k] === enNs[k]),
            "keys still holding English",
        ).toEqual([]);
    });
});
