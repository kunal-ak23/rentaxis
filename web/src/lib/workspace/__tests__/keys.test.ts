import { describe, expect, it } from "vitest";
import { arrowStep, workspaceKey } from "../keys";

const key = (code: string, mods: Partial<Record<"altKey" | "ctrlKey" | "metaKey" | "shiftKey", boolean>> = {}) =>
    workspaceKey({ code, altKey: false, ctrlKey: false, metaKey: false, shiftKey: false, ...mods });

/** Workspace shortcuts (spec §10): Alt-based, matched on `code` so an Arabic layout works. */
describe("workspace keys", () => {
    it("maps Alt+digits, Alt+PgUp/PgDn, Alt+W and Alt+Shift+T", () => {
        expect(key("Digit1", { altKey: true })).toEqual({ action: "goto", index: 0 });
        expect(key("Digit8", { altKey: true })).toEqual({ action: "goto", index: 7 });
        expect(key("Digit9", { altKey: true })).toEqual({ action: "last" });
        expect(key("PageDown", { altKey: true })).toEqual({ action: "next" });
        expect(key("PageUp", { altKey: true })).toEqual({ action: "prev" });
        expect(key("KeyW", { altKey: true })).toEqual({ action: "close" });
        expect(key("KeyT", { altKey: true, shiftKey: true })).toEqual({ action: "reopen" });
    });

    it("ignores keys without Alt, with Ctrl or Cmd, and other letters", () => {
        expect(key("Digit1")).toBeNull();
        expect(key("Digit1", { altKey: true, ctrlKey: true })).toBeNull();
        expect(key("KeyW", { altKey: true, metaKey: true })).toBeNull();
        expect(key("KeyQ", { altKey: true })).toBeNull();
        expect(key("Digit0", { altKey: true })).toBeNull();
    });

    it("follows the visual order with arrows: ArrowLeft is next in Arabic", () => {
        expect(arrowStep("ArrowRight", false)).toBe(1);
        expect(arrowStep("ArrowLeft", false)).toBe(-1);
        expect(arrowStep("ArrowLeft", true)).toBe(1);
        expect(arrowStep("ArrowRight", true)).toBe(-1);
        expect(arrowStep("ArrowUp", true)).toBeNull();
    });
});
