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

import TourProvider from "../TourProvider";

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

function mount(p: string) {
    path.current = p;
    const r = render(<TourProvider role="TENANT_USER"><div /></TourProvider>);
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
        expect(steps[0].buttons.map(b => b.text)).toContain("Skip");
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
