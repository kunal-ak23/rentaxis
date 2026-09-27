import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SearchableSelect, type SearchableSelectOption } from "@/components/ui/SearchableSelect";

/** Characterisation of the client-side select, pinned before its panel was shared with AsyncSearchSelect. */
const options: SearchableSelectOption[] = [
    { value: "a", label: "Alpha", sublabel: "alpha@x", searchText: "alpha alpha@x" },
    { value: "b", label: "Beta", searchText: "beta" },
];

function setup(value = "") {
    const onChange = vi.fn();
    render(<SearchableSelect options={options} value={value} onChange={onChange} placeholder="Pick" searchPlaceholder="Find" />);
    const trigger = screen.getAllByRole("combobox")[0];
    return { onChange, trigger };
}

// jsdom has no layout, so no scrollIntoView; the components call it on highlight.
Element.prototype.scrollIntoView = vi.fn();

afterEach(cleanup);

describe("SearchableSelect", () => {
    it("shows the placeholder, or the selected label with its sublabel", () => {
        setup();
        expect(screen.getAllByRole("combobox")[0]).toHaveTextContent("Pick");
        cleanup();
        setup("a");
        expect(screen.getAllByRole("combobox")[0]).toHaveTextContent("Alpha · alpha@x");
    });

    it("filters by searchText and shows 'No matches'", () => {
        const { trigger } = setup();
        fireEvent.click(trigger);
        fireEvent.change(screen.getByPlaceholderText("Find"), { target: { value: "bet" } });
        expect(screen.queryByText("Alpha")).not.toBeInTheDocument();
        expect(screen.getByText("Beta")).toBeInTheDocument();
        fireEvent.change(screen.getByPlaceholderText("Find"), { target: { value: "zzz" } });
        expect(screen.getByText("No matches")).toBeInTheDocument();
    });

    it("ArrowDown + Enter selects; Escape closes; both return focus to the trigger", () => {
        const { trigger, onChange } = setup();
        fireEvent.click(trigger);
        fireEvent.keyDown(screen.getByPlaceholderText("Find"), { key: "ArrowDown" });
        fireEvent.keyDown(screen.getByPlaceholderText("Find"), { key: "Enter" });
        expect(onChange).toHaveBeenCalledWith("a");
        expect(document.activeElement).toBe(trigger);
        fireEvent.click(trigger);
        fireEvent.keyDown(screen.getByPlaceholderText("Find"), { key: "Escape" });
        expect(screen.queryByPlaceholderText("Find")).not.toBeInTheDocument();
        expect(document.activeElement).toBe(trigger);
        expect(onChange).toHaveBeenCalledTimes(1);
    });

    it("the clear row and a click select work", () => {
        const { trigger, onChange } = setup("a");
        fireEvent.click(trigger);
        fireEvent.click(screen.getAllByRole("option")[0]);
        expect(onChange).toHaveBeenLastCalledWith("");
        fireEvent.click(trigger);
        fireEvent.click(screen.getByText("Beta"));
        expect(onChange).toHaveBeenLastCalledWith("b");
    });

    it("clicking the overlay closes without changing the value", () => {
        const { trigger, onChange } = setup();
        fireEvent.click(trigger);
        const overlay = document.querySelector(".fixed.inset-0") as HTMLElement;
        fireEvent.click(overlay);
        expect(screen.queryByPlaceholderText("Find")).not.toBeInTheDocument();
        expect(onChange).not.toHaveBeenCalled();
    });
});
