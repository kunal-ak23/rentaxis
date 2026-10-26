import { act, cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Break round 1 (tour check): the first-time "Welcome" tour auto-opened a
 * click-blocking overlay on whatever page the user landed on (a ticket
 * detail page, for a staff user), and dismissing it did not stick, so it came
 * back on the next page load. It must only auto-start on the dashboard home
 * (where its first step lives), be dismissible with Escape and a visible
 * close/skip, and stay dismissed.
 */
const path = { current: "/en/dashboard" };
vi.mock("next/navigation", () => ({ usePathname: () => path.current }));
const router = { push: vi.fn() };
vi.mock("@/i18n/routing", () => ({ useRouter: () => router }));
vi.mock("shepherd.js/dist/css/shepherd.css", () => ({}));
vi.mock("../tour-styles.css", () => ({}));

type Handler = () => void;
const tours: { options: Record<string, unknown>; handlers: Record<string, Handler[]>; started: boolean }[] = [];
class FakeTour {
    rec: (typeof tours)[number];
    constructor(options: Record<string, unknown>) {
        this.rec = { options, handlers: {}, started: false };
        tours.push(this.rec);
    }
    on(event: string, h: Handler) { (this.rec.handlers[event] ??= []).push(h); }
    start() { this.rec.started = true; }
    cancel() { this.rec.handlers.cancel?.forEach(h => h()); }
}
vi.mock("react-shepherd", () => ({
    ShepherdJourneyProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
    useShepherd: () => ({ Tour: FakeTour }),
}));

import { NextIntlClientProvider } from "next-intl";
import TourProvider, { stepElement, stepPosition } from "../TourProvider";
import type { UserRole } from "@/lib/rbac";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";

const store = new Map<string, string>();
beforeEach(() => {
    vi.useFakeTimers();
    tours.length = 0;
    store.clear();
    Object.defineProperty(window, "localStorage", {
        configurable: true,
        value: { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => { store.set(k, v); }, removeItem: (k: string) => { store.delete(k); } },
    });
});
afterEach(() => { cleanup(); vi.useRealTimers(); });

function mount(p: string, role: UserRole = "TENANT_ADMIN") {
    path.current = p;
    const locale = p.startsWith("/ar") ? "ar" : "en";
    const r = render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <TourProvider role={role}><div /></TourProvider>
        </NextIntlClientProvider>,
    );
    act(() => { vi.advanceTimersByTime(2000); });
    return r;
}

describe("TourProvider auto-start", () => {
    it("does not open on a page that is not in the tour (ticket detail)", () => {
        mount("/en/dashboard/tickets/abc");
        expect(tours.filter(t => t.started)).toHaveLength(0);
    });

    it("opens on the dashboard home, closable with Escape and a visible close button", () => {
        mount("/ar/dashboard");
        expect(tours).toHaveLength(1);
        expect(tours[0].started).toBe(true);
        expect(tours[0].options.exitOnEsc).toBe(true);
        expect((tours[0].options.defaultStepOptions as { cancelIcon: { enabled: boolean } }).cancelIcon.enabled).toBe(true);
        const steps = tours[0].options.steps as { buttons: { text: string }[] }[];
        expect(steps[0].buttons.map(b => b.text)).toContain("تخطي");
        // The admin welcome tour, Leasing and Accounting included.
        expect((tours[0].options.steps as { id: string }[]).map(s => s.id)).toContain("sidebar-finance");
    });

    it("opens a Company User's own tour, which stays off the Leasing and Accounting rail items they do not have", () => {
        mount("/en/dashboard", "TENANT_USER");
        expect(tours).toHaveLength(1);
        expect(tours[0].started).toBe(true);
        const ids = (tours[0].options.steps as { id: string }[]).map(s => s.id);
        expect(ids).toEqual(["welcome", "sidebar-nav", "help-fab", "header-help"]);
        // Finishing it is remembered under its own id, not the admin tour's.
        act(() => { tours[0].handlers.complete?.forEach(h => h()); });
        expect(JSON.parse(store.get("rentaxis_tours_completed")!)).toEqual(["company-user-onboarding"]);
    });

    it("does not auto-start for a role with no welcome tour (accountant)", () => {
        mount("/en/dashboard", "ACCOUNTANT");
        expect(tours.filter(t => t.started)).toHaveLength(0);
    });

    it("stays dismissed once skipped or closed", () => {
        const first = mount("/en/dashboard");
        act(() => { tours[0].handlers.cancel?.forEach(h => h()); });
        first.unmount();
        tours.length = 0;
        mount("/en/dashboard");
        expect(tours.filter(t => t.started)).toHaveLength(0);
    });
});

type Step = { title: string; text: string; attachTo: { on: string }; buttons: { text: string }[] };

describe("TourProvider copy comes from the catalogue in the page's language", () => {
    it("shows an English tour under /en", () => {
        mount("/en/dashboard");
        const steps = tours[0].options.steps as Step[];
        expect(steps[0].title).toBe("Welcome to Miftah!");
        expect(steps[0].text).toContain("Step 1 of 6");
        expect(steps[0].buttons.map(b => b.text)).toEqual(["Skip", "Next"]);
        expect(steps[1].buttons.map(b => b.text)).toEqual(["Back", "Next"]);
        expect(steps[5].buttons.map(b => b.text)).toEqual(["Back", "Done"]);
        expect(steps[1].attachTo.on).toBe("right");
    });

    it("shows an Arabic tour under /ar, opening beside the rail on its left", () => {
        mount("/ar/dashboard");
        const steps = tours[0].options.steps as Step[];
        expect(steps[0].title).toBe(ar.Tours["admin-onboarding"].steps.welcome.title);
        expect(steps[0].text).toContain(ar.Tours["admin-onboarding"].steps.welcome.text);
        expect(steps[0].text).toContain("الخطوة 1 من 6");
        expect(steps[5].buttons.map(b => b.text)).toEqual(["رجوع", "تم"]);
        expect(steps[1].attachTo.on).toBe("left");
        expect(JSON.stringify(steps.map(s => [s.title, s.text, s.buttons.map(b => b.text)]))).not.toMatch(/Step|Next|Back|Skip|Done|Welcome/);
    });
});

describe("tour step targets", () => {
    const shown = (el: HTMLElement, on: boolean) => { el.getClientRects = () => (on ? [{}] : []) as unknown as DOMRectList; return el; };
    afterEach(() => { document.body.innerHTML = ""; });

    it("skips a matching element that is not on screen, falls back to the phone target, else centres", () => {
        // Below 768 px: the desktop rail is in the DOM but hidden, the header's menu button shows.
        const rail = shown(document.createElement("nav"), false);
        rail.setAttribute("data-tour", "sidebar-nav");
        const menu = shown(document.createElement("button"), true);
        menu.setAttribute("data-testid", "header-menu");
        document.body.append(rail, menu);
        const step = { target: '[data-tour="sidebar-nav"]', mobileTarget: '[data-testid="header-menu"]' };
        expect(stepElement(step)).toBe(menu);
        shown(rail, true);
        expect(stepElement(step)).toBe(rail);
        shown(rail, false);
        expect(stepElement({ target: step.target })).toBeUndefined();
    });

    it("mirrors left and right for a right-to-left page", () => {
        expect(stepPosition({ position: "right" }, true)).toBe("left");
        expect(stepPosition({ position: "left" }, true)).toBe("right");
        expect(stepPosition({ position: "bottom" }, true)).toBe("bottom");
        expect(stepPosition({ position: "right" }, false)).toBe("right");
    });

    it("opens a step below its phone target on a phone, where beside it would cover it", () => {
        const rail = { position: "right" as const, mobileTarget: '[data-testid="header-menu"]' };
        expect(stepPosition(rail, true, true)).toBe("bottom");
        expect(stepPosition(rail, false, true)).toBe("bottom");
        expect(stepPosition(rail, true, false)).toBe("left");
        expect(stepPosition({ position: "right" }, false, true)).toBe("right");
    });
});

describe("TourProvider autoStart=false (review fix 5)", () => {
    it("does not open on the dashboard home while the layout says the page is not usable", () => {
        path.current = "/en/dashboard";
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <TourProvider role="TENANT_ADMIN" autoStart={false}><div /></TourProvider>
            </NextIntlClientProvider>,
        );
        act(() => { vi.advanceTimersByTime(2000); });
        expect(tours.filter(t => t.started)).toHaveLength(0);
    });
});
