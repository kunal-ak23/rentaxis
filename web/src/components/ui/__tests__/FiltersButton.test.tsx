import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { FilterChip, FiltersButton, fitFiltersPanel } from "../FiltersButton";

afterEach(cleanup);

describe("fitFiltersPanel (R1 P2-1)", () => {
    it("keeps the panel inside a 390 px screen whichever edge the button sits at", () => {
        // English phone: the button wrapped to the start (left) of its row.
        const en = fitFiltersPanel({ left: 16, right: 110, bottom: 200 }, false, 390);
        expect(en.left).toBeGreaterThanOrEqual(8);
        expect(en.left + en.width).toBeLessThanOrEqual(390 - 8);
        // Arabic phone: it wrapped to the start (right) of its row.
        const ar = fitFiltersPanel({ left: 280, right: 374, bottom: 200 }, true, 390);
        expect(ar.left).toBeGreaterThanOrEqual(8);
        expect(ar.left + ar.width).toBeLessThanOrEqual(390 - 8);
        expect(ar.top).toBe(204);
    });

    it("lines up with the button's inline end when there is room", () => {
        expect(fitFiltersPanel({ left: 900, right: 1000, bottom: 50 }, false, 1366)).toMatchObject({ left: 744, width: 256 });
        expect(fitFiltersPanel({ left: 400, right: 500, bottom: 50 }, true, 1366)).toMatchObject({ left: 400, width: 256 });
    });

    it("narrows to the screen on a very small one", () => {
        expect(fitFiltersPanel({ left: 10, right: 60, bottom: 50 }, false, 240)).toMatchObject({ left: 8, width: 224 });
    });
});

describe("FiltersButton", () => {
    it("opens fixed at the measured place, hidden until opened", () => {
        Object.defineProperty(window, "innerWidth", { configurable: true, value: 390 });
        render(<FiltersButton label="Filters" activeCount={1}><select data-testid="inner" /></FiltersButton>);
        expect(screen.getByTestId("filters-panel")).not.toBeVisible();
        const b = screen.getByTestId("filters-button");
        b.getBoundingClientRect = () => ({ left: 16, right: 110, top: 170, bottom: 200, width: 94, height: 30, x: 16, y: 170, toJSON: () => ({}) }) as DOMRect;
        fireEvent.click(b);
        const panel = screen.getByTestId("filters-panel");
        expect(panel).toBeVisible();
        expect(panel.style.position).toBe("fixed");
        expect(parseFloat(panel.style.left)).toBeGreaterThanOrEqual(8);
        expect(b).toHaveTextContent("1");
    });

    it("renders a removable chip", () => {
        let removed = false;
        render(<FilterChip label="Expired" removeLabel="Remove" testId="chip" onRemove={() => { removed = true; }} />);
        fireEvent.click(screen.getByTestId("chip-remove"));
        expect(removed).toBe(true);
    });
});
