import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import PostDatedPanel from "../PostDatedPanel";
import { windowFromParams, windowProblem, writeWindowParams, presetWindow } from "../pdcWindow";
import { addDaysIso, addMonthsIso } from "@/lib/businessDate";

/**
 * Demo feedback 2026-09-29: Post-dated is read by maturity window — Next 1 week,
 * Next 2 weeks (default), Next 1 month, or a custom From–To — not by month. The
 * choice lives in the URL and every window starts on the Dubai business date.
 */

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN", name: "Admin" } } }) }));

const postDated = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, chequeApi: { ...m.chequeApi, postDated: (...a: unknown[]) => postDated(...(a as [])) } };
});

// 20:30 UTC on 29 September is 00:30 on 30 September in Dubai: the business date is the 30th.
const NOW = new Date("2026-09-29T20:30:00Z");
const TODAY = "2026-09-30";

beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(NOW);
    window.history.replaceState(null, "", "/en/dashboard/collections?tab=post-dated");
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    postDated.mockReset();
    postDated.mockResolvedValue([]);
});

afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
});

const renderPanel = (locale: "en" | "ar" = "en") =>
    render(<NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}><PostDatedPanel embedded /></NextIntlClientProvider>);
const lastQuery = () => postDated.mock.calls.at(-1)?.[0];
const params = () => new URL(window.location.href).searchParams;

describe("pdcWindow", () => {
    it("computes the presets from the business date, both ends inclusive", () => {
        expect(presetWindow("1w", TODAY)).toEqual({ from: TODAY, to: "2026-10-07" });
        expect(presetWindow("2w", TODAY)).toEqual({ from: TODAY, to: "2026-10-14" });
        expect(presetWindow("1m", TODAY)).toEqual({ from: TODAY, to: "2026-10-30" });
        expect(addMonthsIso("2026-01-31", 1)).toBe("2026-02-28");
        expect(addDaysIso("2026-12-28", 7)).toBe("2027-01-04");
    });

    it("reads and writes the URL, defaulting to Next 2 weeks", () => {
        expect(windowFromParams(new URLSearchParams(""), TODAY)).toEqual({ preset: "2w", from: TODAY, to: "2026-10-14" });
        expect(windowFromParams(new URLSearchParams("pdcRange=bogus"), TODAY).preset).toBe("2w");
        expect(windowFromParams(new URLSearchParams("pdcRange=custom&pdcFrom=2026-11-01&pdcTo=2026-11-15"), TODAY))
            .toEqual({ preset: "custom", from: "2026-11-01", to: "2026-11-15" });
        // A register's own from/to is not a PDC window.
        expect(windowFromParams(new URLSearchParams("from=2026-11-01&to=2026-11-15"), TODAY).preset).toBe("2w");
        const p = writeWindowParams(new URLSearchParams("tab=post-dated&pdcFrom=x&pdcTo=y"), { preset: "1w", from: TODAY, to: "2026-10-07" });
        expect(p.toString()).toBe("tab=post-dated&pdcRange=1w");
        const c = writeWindowParams(new URLSearchParams("tab=post-dated"), { preset: "custom", from: "2026-11-01", to: "2026-11-15" });
        expect(c.toString()).toBe("tab=post-dated&pdcRange=custom&pdcFrom=2026-11-01&pdcTo=2026-11-15");
    });

    it("still opens an old range=/from=/to= bookmark, and rewrites it under the pdc keys", () => {
        const old = new URLSearchParams("tab=post-dated&range=custom&from=2026-11-01&to=2026-11-15");
        const w = windowFromParams(old, TODAY);
        expect(w).toEqual({ preset: "custom", from: "2026-11-01", to: "2026-11-15" });
        expect(writeWindowParams(old, w).toString()).toBe("tab=post-dated&pdcRange=custom&pdcFrom=2026-11-01&pdcTo=2026-11-15");
    });

    it("flags an inverted or over-long window", () => {
        expect(windowProblem({ from: "2026-10-02", to: "2026-10-01" })).toBe("invalid");
        expect(windowProblem({ from: "2026-10-01", to: "" })).toBe("invalid");
        expect(windowProblem({ from: "2026-01-01", to: "2027-01-02" })).toBe("tooLong");
        expect(windowProblem({ from: "2026-01-01", to: "2027-01-01" })).toBeNull();
    });
});

describe("Post-dated panel — maturity window", () => {
    it("defaults to Next 2 weeks from the Dubai business date, and has no month picker", async () => {
        renderPanel();
        await waitFor(() => expect(postDated).toHaveBeenCalled());
        expect(lastQuery()).toEqual({ from: TODAY, to: "2026-10-14", propertyId: undefined });
        expect(screen.getByTestId("post-dated-preset-2w")).toHaveAttribute("aria-pressed", "true");
        expect(screen.queryByTestId("post-dated-month")).not.toBeInTheDocument();
        expect(screen.getByText("Next 1 week")).toBeInTheDocument();
        expect(screen.getByText("Next 1 month")).toBeInTheDocument();
    });

    it("re-queries on a preset and keeps the choice in the URL", async () => {
        renderPanel();
        await waitFor(() => expect(postDated).toHaveBeenCalled());
        fireEvent.click(screen.getByTestId("post-dated-preset-1w"));
        await waitFor(() => expect(lastQuery()).toEqual({ from: TODAY, to: "2026-10-07", propertyId: undefined }));
        expect(params().get("pdcRange")).toBe("1w");
        expect(params().get("tab")).toBe("post-dated");

        fireEvent.click(screen.getByTestId("post-dated-preset-1m"));
        await waitFor(() => expect(lastQuery()).toEqual({ from: TODAY, to: "2026-10-30", propertyId: undefined }));
        expect(params().get("pdcRange")).toBe("1m");
    });

    it("takes a custom From–To range and writes it to the URL", async () => {
        renderPanel();
        await waitFor(() => expect(postDated).toHaveBeenCalled());
        fireEvent.click(screen.getByTestId("post-dated-preset-custom"));
        fireEvent.change(screen.getByTestId("post-dated-from"), { target: { value: "2026-11-01" } });
        fireEvent.change(screen.getByTestId("post-dated-to"), { target: { value: "2026-11-20" } });
        await waitFor(() => expect(lastQuery()).toEqual({ from: "2026-11-01", to: "2026-11-20", propertyId: undefined }));
        expect(params().get("pdcRange")).toBe("custom");
        expect(params().get("pdcFrom")).toBe("2026-11-01");
        expect(params().get("pdcTo")).toBe("2026-11-20");
        // Nothing the cheque register would read as its own date filter.
        expect(params().has("from")).toBe(false);
        expect(params().has("to")).toBe(false);
    });

    it("opens on the window a bookmarked URL names", async () => {
        window.history.replaceState(null, "", "/en/dashboard/collections?tab=post-dated&pdcRange=custom&pdcFrom=2026-12-01&pdcTo=2026-12-31");
        renderPanel();
        await waitFor(() => expect(lastQuery()).toEqual({ from: "2026-12-01", to: "2026-12-31", propertyId: undefined }));
        expect(screen.getByTestId("post-dated-from")).toHaveValue("2026-12-01");
    });

    it("refuses an inverted custom range without asking the server", async () => {
        renderPanel();
        await waitFor(() => expect(postDated).toHaveBeenCalledTimes(1));
        fireEvent.click(screen.getByTestId("post-dated-preset-custom"));
        fireEvent.change(screen.getByTestId("post-dated-from"), { target: { value: "2026-11-20" } });
        fireEvent.change(screen.getByTestId("post-dated-to"), { target: { value: "2026-11-01" } });
        expect(await screen.findByTestId("post-dated-window-error")).toHaveTextContent("From must be on or before To.");
        expect(lastQuery()).not.toEqual(expect.objectContaining({ from: "2026-11-20" }));
    });

    it("labels the presets in Arabic", async () => {
        renderPanel("ar");
        await waitFor(() => expect(postDated).toHaveBeenCalled());
        expect(screen.getByText("الأسبوعان القادمان")).toBeInTheDocument();
        expect(screen.getByText("مخصص")).toBeInTheDocument();
    });
});
